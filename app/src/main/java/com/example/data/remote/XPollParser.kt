package com.example.data.remote

import com.example.domain.model.Poll
import com.example.domain.model.PollChoice
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Dedicated parser for X poll payloads across all wire formats:
 * 1. X GraphQL `card.legacy.binding_values` (array of `{key, value}` pairs)
 * 2. X CAPI (`caps.x.com/v2/capi/passthrough/1`) & REST v1.1 `binding_values` (keyed JSONObject map)
 * 3. X Syndication / FxTwitter / API v2 `poll` JSON (`choices` or `options`)
 *
 * Also supports merging CAPI vote responses directly into an existing [Poll] so local UI
 * updates accurately without re-fetching the entire tweet.
 */
object XPollParser {

    /**
     * Parses any raw JSON string representing an X poll card, tweet wrapper, or public poll payload.
     */
    fun parse(rawJson: String, fallbackCreatedAtMs: Long = 0L): Poll? {
        if (rawJson.isBlank()) return null
        val json = runCatching { JSONObject(rawJson.trim()) }.getOrNull() ?: return null
        return parse(json, fallbackCreatedAtMs)
    }

    /**
     * Auto-detects the payload shape and parses an X [Poll] from a [JSONObject].
     */
    fun parse(json: JSONObject?, fallbackCreatedAtMs: Long = 0L): Poll? {
        if (json == null) return null

        // Direct GraphQL/CAPI card or tweet wrapper containing `card`
        val cardCandidate = json.optJSONObject("card") ?: json
        parseCardJson(cardCandidate, fallbackCreatedAtMs)?.let { return it }

        // Public / FxTwitter / Syndication / API v2 poll object or tweet wrapper containing `poll`
        val pollCandidate = json.optJSONObject("poll") ?: json
        return parsePublicPollJson(pollCandidate)
    }

    /**
     * Parses X's GraphQL or CAPI card JSON (`poll2choice_text_only` .. `poll4choice_video`, etc.).
     */
    fun parseCardJson(cardObj: JSONObject?, fallbackCreatedAtMs: Long = 0L): Poll? {
        if (cardObj == null) return null

        val legacy = cardObj.optJSONObject("legacy") ?: cardObj
        val cardName = legacy.optString("name")
            .ifBlank { cardObj.optString("name") }
            .ifBlank { cardObj.optString("card_name") }
        val bindingValuesNode = legacy.opt("binding_values") ?: cardObj.opt("binding_values")

        val values = extractBindingValues(bindingValuesNode)
        if (values.isEmpty()) return null

        // Confirm this card is actually a poll (either by card name or presence of choice1_label)
        val looksLikePoll = cardName.startsWith("poll", ignoreCase = true) ||
            values.containsKey("choice1_label")
        if (!looksLikePoll) return null

        val choices = (1..4).mapNotNull { idx ->
            val label = values["choice${idx}_label"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val votes = values["choice${idx}_count"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            PollChoice(index = idx, label = label, votes = votes)
        }
        if (choices.isEmpty()) return null

        val totalVotes = choices.sumOf { it.votes }
        val selectedChoice = values["selected_choice"]?.toIntOrNull()?.takeIf { idx ->
            choices.any { it.index == idx }
        }

        val endsAtMs = resolveEndTimeEpochMs(
            rawEnd = values["end_datetime_utc"],
            rawLastUpdated = values["last_updated_datetime_utc"],
            durationMinutes = values["duration_minutes"]?.toLongOrNull(),
            fallbackCreatedAtMs = fallbackCreatedAtMs,
        )

        val countsAreFinal = values["counts_are_final"].equals("true", ignoreCase = true)
        val cardUri = cardObj.optString("rest_id")
            .ifBlank { legacy.optString("url") }
            .ifBlank { cardObj.optString("url") }
            .ifBlank { values["card_uri"].orEmpty() }

        return Poll(
            cardUri = cardUri,
            choices = choices,
            totalVotes = totalVotes,
            endsAtEpochMs = endsAtMs,
            isFinal = countsAreFinal,
            votedChoiceIndex = selectedChoice,
            cardName = cardName,
        )
    }

    /**
     * Parses FxTwitter, Syndication, or X API v2 poll payloads (`choices` / `options`).
     */
    fun parsePublicPollJson(pollObj: JSONObject?): Poll? {
        if (pollObj == null) return null

        val choicesArr = pollObj.optJSONArray("choices")
            ?: pollObj.optJSONArray("options")
            ?: return null

        val choices = mutableListOf<PollChoice>()
        for (i in 0 until choicesArr.length()) {
            val c = choicesArr.optJSONObject(i) ?: continue
            val label = c.optString("label").ifBlank { c.optString("text") }.trim()
            if (label.isBlank()) continue
            val index = c.optInt("position", c.optInt("index", i + 1)).coerceAtLeast(1)
            val votes = when {
                c.has("count") -> c.optLong("count", 0L)
                c.has("votes") -> c.optLong("votes", 0L)
                else -> 0L
            }.coerceAtLeast(0L)
            choices.add(PollChoice(index = index, label = label, votes = votes))
        }
        if (choices.isEmpty()) return null

        val summedVotes = choices.sumOf { it.votes }
        val totalVotes = maxOf(
            pollObj.optLong("total_votes", 0L),
            summedVotes,
        )

        val rawEndsAt = pollObj.optString("ends_at")
            .ifBlank { pollObj.optString("end_datetime") }
            .ifBlank { pollObj.optString("end_datetime_utc") }
        val endsAtMs = parseFlexibleTimestamp(rawEndsAt)

        val timeLeftLabel = pollObj.optString("time_left_en")
        val votingStatus = pollObj.optString("voting_status")
        val isFinal = pollObj.optBoolean("counts_are_final", false) ||
            votingStatus.equals("closed", ignoreCase = true) ||
            timeLeftLabel.contains("final", ignoreCase = true) ||
            (endsAtMs > 0L && endsAtMs <= System.currentTimeMillis())

        val selected = if (pollObj.has("selected_choice") && !pollObj.isNull("selected_choice")) {
            pollObj.optInt("selected_choice", -1).takeIf { idx -> choices.any { it.index == idx } }
        } else {
            null
        }

        return Poll(
            cardUri = pollObj.optString("card_uri").ifBlank { pollObj.optString("id") },
            choices = choices,
            totalVotes = totalVotes,
            endsAtEpochMs = endsAtMs,
            isFinal = isFinal,
            votedChoiceIndex = selected,
            cardName = pollObj.optString("card_name"),
        )
    }

    /**
     * Merges a CAPI vote response body (`caps.x.com/v2/capi/passthrough/1`) directly into
     * [previous] so live vote counts update in-place without re-fetching the tweet.
     * Falls back cleanly to [Poll.withLocalVote] when the response body is empty or partial.
     */
    fun mergeVoteResponse(
        previous: Poll,
        responseBody: String,
        votedChoiceIndex: Int,
    ): Poll {
        val parsed = parse(responseBody)
            ?: return previous.withLocalVote(votedChoiceIndex)

        // Preserve labels/metadata from previous poll if CAPI returned count-only binding values
        val parsedByIndex = parsed.choices.associateBy { it.index }
        val mergedChoices = previous.choices.map { prevChoice ->
            val remote = parsedByIndex[prevChoice.index]
            if (remote != null) {
                val floorVotes = if (prevChoice.index == votedChoiceIndex) {
                    maxOf(remote.votes, prevChoice.votes + 1L)
                } else {
                    maxOf(remote.votes, prevChoice.votes)
                }
                prevChoice.copy(
                    label = remote.label.ifBlank { prevChoice.label },
                    votes = floorVotes,
                )
            } else if (prevChoice.index == votedChoiceIndex) {
                prevChoice.copy(votes = prevChoice.votes + 1L)
            } else {
                prevChoice
            }
        }
        val mergedTotal = maxOf(parsed.totalVotes, mergedChoices.sumOf { it.votes })
        return previous.copy(
            cardUri = parsed.cardUri.ifBlank { previous.cardUri },
            cardName = parsed.cardName.ifBlank { previous.cardName },
            choices = mergedChoices,
            totalVotes = mergedTotal,
            endsAtEpochMs = if (parsed.endsAtEpochMs > 0L) parsed.endsAtEpochMs else previous.endsAtEpochMs,
            isFinal = parsed.isFinal || previous.isFinal,
            votedChoiceIndex = parsed.votedChoiceIndex ?: votedChoiceIndex,
        )
    }

    private fun extractBindingValues(node: Any?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val entry = node.optJSONObject(i) ?: continue
                    val key = entry.optString("key")
                    val valueObj = entry.optJSONObject("value") ?: continue
                    val extracted = extractTypedBindingValue(valueObj)
                    if (key.isNotBlank() && extracted.isNotBlank()) {
                        map[key] = extracted
                    }
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val valueObj = node.optJSONObject(key)
                    val extracted = if (valueObj != null) {
                        extractTypedBindingValue(valueObj)
                    } else {
                        node.optString(key)
                    }
                    if (key.isNotBlank() && extracted.isNotBlank()) {
                        map[key] = extracted
                    }
                }
            }
        }
        return map
    }

    private fun extractTypedBindingValue(vObj: JSONObject): String {
        val strVal = vObj.optString("string_value")
        if (strVal.isNotBlank()) return strVal
        if (vObj.has("boolean_value") && !vObj.isNull("boolean_value")) {
            return vObj.optBoolean("boolean_value").toString()
        }
        if (vObj.has("integer_value") && !vObj.isNull("integer_value")) {
            return vObj.optLong("integer_value").toString()
        }
        return ""
    }

    private fun resolveEndTimeEpochMs(
        rawEnd: String?,
        rawLastUpdated: String?,
        durationMinutes: Long?,
        fallbackCreatedAtMs: Long,
    ): Long {
        val parsedEnd = parseFlexibleTimestamp(rawEnd.orEmpty())
        if (parsedEnd > 0L) return parsedEnd

        if (durationMinutes != null && durationMinutes > 0L) {
            val baseMs = fallbackCreatedAtMs.takeIf { it > 0L }
                ?: parseFlexibleTimestamp(rawLastUpdated.orEmpty())
            if (baseMs > 0L) {
                return baseMs + durationMinutes * 60_000L
            }
        }
        return 0L
    }

    internal fun parseFlexibleTimestamp(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0L

        // Epoch seconds or milliseconds
        trimmed.toLongOrNull()?.let { numeric ->
            return if (numeric in 1_000_000_000L..9_999_999_999L) numeric * 1000L else numeric
        }

        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd HH:mm:ss",
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(trimmed)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return 0L
    }
}
