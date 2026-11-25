package com.example.data.remote

import android.text.format.DateFormat
import com.example.domain.model.ArticleBlock
import com.example.domain.model.LinkPreview
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.NotificationItem
import com.example.domain.model.NotificationType
import com.example.domain.model.QuotedTweet
import com.example.domain.model.ThreadConversation
import com.example.domain.model.TimelinePage
import com.example.domain.model.TrendItem
import com.example.domain.model.Tweet
import com.example.domain.model.TweetMetrics
import com.example.domain.model.UniversalSearchSuggestions
import com.example.domain.model.ViewerState
import com.example.domain.model.XArticle
import com.example.domain.model.XUser
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/**
 * Schema-resilient parser for X's internal GraphQL timelines, tweets, threads, users,
 * adaptive search, typeahead search, notifications, polls, link cards, articles, and trends.
 * Handles both modern 2025/2026 X user schema (`core`/`avatar`/`location`) and legacy fields.
 */
object XGraphqlParser {

    private val twitterDateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("EEE MMM dd HH:mm:ss ZZZZZ yyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun parseTwitterDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            twitterDateFormat.get()?.parse(dateStr)?.time ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    fun parseTwitterDateOrZero(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            twitterDateFormat.get()?.parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * Detects X HTTP 200 GraphQL payloads that contain `"errors":[...]` with empty/missing `data`,
     * allowing the client to self-heal by refreshing queryIds and retrying instead of silently
     * returning an empty search timeline.
     */
    fun hasGraphqlErrorsWithoutData(rawJson: String): Boolean {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return true
        val errors = root.optJSONArray("errors")
        if (errors == null || errors.length() == 0) return false
        val data = root.optJSONObject("data") ?: return true
        if (data.length() == 0) return true
        // For timeline/search responses, if there are errors AND no instructions anywhere, retry
        val hasInstructions = findInstructionsArray(data) != null
        val hasUser = data.optJSONObject("user") != null ||
            data.optJSONObject("user_result_by_screen_name") != null ||
            data.optJSONObject("viewer") != null
        return !hasInstructions && !hasUser
    }

    fun parseTimeline(
        rawJson: String,
        sourceInfo: String = "X GraphQL",
        sortBySortIndexDesc: Boolean = false,
    ): TimelinePage {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return TimelinePage(emptyList(), sourceInfo = sourceInfo)
        val instructions = findInstructionsArray(root)
            ?: return TimelinePage(emptyList(), sourceInfo = sourceInfo)

        data class IndexedTweet(
            val tweet: Tweet,
            val sortIndex: String,
            val order: Int,
        )

        val entriesList = mutableListOf<IndexedTweet>()
        var bottomCursor: String? = null
        var seq = 0

        for (i in 0 until instructions.length()) {
            val instruction = instructions.optJSONObject(i) ?: continue

            // Handle TimelineAddToModule (used in search & thread reply modules)
            val directModuleItems = instruction.optJSONArray("moduleItems")
            if (directModuleItems != null) {
                for (k in 0 until directModuleItems.length()) {
                    val modEntry = directModuleItems.optJSONObject(k) ?: continue
                    val modEntryId = modEntry.optString("entryId")
                    val modItemWrapper = modEntry.optJSONObject("item")
                    val modItemContent = modItemWrapper?.optJSONObject("itemContent")
                    val modItem = modItemContent
                        ?.optJSONObject("tweet_results")
                        ?.optJSONObject("result")
                    if (isPromotedTimelineNode(modEntryId, modItemContent, modItemWrapper, modItem)) {
                        continue
                    }
                    if (modItem != null) {
                        parseTweetResult(modItem, false)?.takeUnless { it.isPromoted }?.let {
                            entriesList.add(IndexedTweet(it, "", seq++))
                        }
                    }
                }
            }

            val entries = instruction.optJSONArray("entries")
                ?: instruction.optJSONObject("entry")?.let { JSONArray().put(it) }
                ?: continue

            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val entryId = entry.optString("entryId")
                val sortIndex = entry.optString("sortIndex").trim()
                val content = entry.optJSONObject("content") ?: continue

                if (content.optString("cursorType") == "Bottom" ||
                    entryId.startsWith("cursor-bottom-")
                ) {
                    val v = content.optString("value")
                    if (v.isNotBlank()) bottomCursor = v
                    continue
                }

                val outerItemContent = content.optJSONObject("itemContent")
                val itemTweetResult = outerItemContent
                    ?.optJSONObject("tweet_results")
                    ?.optJSONObject("result")

                if (isPromotedTimelineNode(entryId, outerItemContent, content, itemTweetResult)) {
                    continue
                }

                // Single item
                if (itemTweetResult != null) {
                    parseTweetResult(itemTweetResult, false)?.takeUnless { it.isPromoted }?.let {
                        entriesList.add(IndexedTweet(it, sortIndex, seq++))
                    }
                    continue
                }

                // Module items (conversation threads, profile replies & search modules inside timeline)
                val moduleItems = content.optJSONArray("items")
                if (moduleItems != null) {
                    for (k in 0 until moduleItems.length()) {
                        val modEntry = moduleItems.optJSONObject(k) ?: continue
                        val modEntryId = modEntry.optString("entryId")
                        val modItemWrapper = modEntry.optJSONObject("item")
                        val modItemContent = modItemWrapper?.optJSONObject("itemContent")
                            ?: modEntry.optJSONObject("itemContent")
                        val modItem = modItemContent
                            ?.optJSONObject("tweet_results")
                            ?.optJSONObject("result")
                        if (isPromotedTimelineNode(modEntryId, modItemContent, modItemWrapper, modItem)) {
                            continue
                        }
                        if (modItem != null) {
                            parseTweetResult(modItem, false)?.takeUnless { it.isPromoted }?.let {
                                entriesList.add(IndexedTweet(it, sortIndex, seq++))
                            }
                        }
                    }
                }
            }
        }

        val orderedTweets = if (sortBySortIndexDesc && entriesList.any { it.sortIndex.isNotBlank() && it.sortIndex.all(Char::isDigit) }) {
            entriesList.sortedWith(
                compareByDescending<IndexedTweet> { item ->
                    if (item.sortIndex.isNotBlank() && item.sortIndex.all(Char::isDigit)) {
                        item.sortIndex.padStart(32, '0')
                    } else {
                        ""
                    }
                }.thenBy { it.order }
            ).map { it.tweet }
        } else {
            entriesList.map { it.tweet }
        }

        return TimelinePage(
            tweets = orderedTweets.distinctBy { it.id },
            nextCursor = bottomCursor,
            isFromPublicFallback = false,
            sourceInfo = sourceInfo,
        )
    }

    /**
     * Parses X's REST v2 adaptive search (`i/api/2/search/adaptive.json`) which searches
     * real tweets by keywords without relying on rotating GraphQL queryId hashes.
     */
    fun parseAdaptiveSearch(rawJson: String): TimelinePage {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return TimelinePage(emptyList(), sourceInfo = "X Adaptive Search")
        val globalObjects = root.optJSONObject("globalObjects")
            ?: return TimelinePage(emptyList(), sourceInfo = "X Adaptive Search")
        val tweetsObj = globalObjects.optJSONObject("tweets") ?: JSONObject()
        val usersObj = globalObjects.optJSONObject("users") ?: JSONObject()

        val orderedIds = mutableListOf<String>()
        val promotedIds = mutableSetOf<String>()
        var bottomCursor: String? = null
        val instructions = root.optJSONObject("timeline")?.optJSONArray("instructions")
        if (instructions != null) {
            for (i in 0 until instructions.length()) {
                val entries = instructions.optJSONObject(i)?.optJSONObject("addEntries")?.optJSONArray("entries")
                    ?: continue
                for (j in 0 until entries.length()) {
                    val entry = entries.optJSONObject(j) ?: continue
                    val entryId = entry.optString("entryId")
                    val content = entry.optJSONObject("content") ?: continue
                    val opCursor = content.optJSONObject("operation")?.optJSONObject("cursor")
                    if (opCursor?.optString("cursorType") == "Bottom") {
                        bottomCursor = opCursor.optString("value").takeIf { it.isNotBlank() }
                        continue
                    }
                    val itemObj = content.optJSONObject("item")
                    val tweetItemObj = itemObj?.optJSONObject("content")?.optJSONObject("tweet")
                    val tid = tweetItemObj?.optString("id")
                    val entryIsPromoted = isPromotedTimelineNode(
                        entryId = entryId,
                        itemContent = tweetItemObj,
                        wrapperObj = itemObj ?: content,
                        tweetResult = null,
                    )
                    if (entryIsPromoted) {
                        if (!tid.isNullOrBlank()) promotedIds.add(tid)
                        continue
                    }
                    if (!tid.isNullOrBlank()) orderedIds.add(tid)
                }
            }
        }

        val idsToParse = if (orderedIds.isNotEmpty()) {
            orderedIds
        } else {
            val allKeys = mutableListOf<String>()
            val it = tweetsObj.keys()
            while (it.hasNext()) allKeys.add(it.next())
            allKeys
        }

        val parsed = idsToParse.mapNotNull { id ->
            if (id in promotedIds) return@mapNotNull null
            val tJson = tweetsObj.optJSONObject(id) ?: return@mapNotNull null
            if (tJson.optJSONObject("promotedMetadata") != null ||
                tJson.optJSONObject("promoted_metadata") != null ||
                tJson.optBoolean("is_promoted")
            ) {
                return@mapNotNull null
            }
            val uJson = usersObj.optJSONObject(tJson.optString("user_id_str"))
            val quotedId = tJson.optString("quoted_status_id_str")
            val quotedTweet = if (quotedId.isNotBlank()) {
                tweetsObj.optJSONObject(quotedId)?.let { qtJson ->
                    val qUser = usersObj.optJSONObject(qtJson.optString("user_id_str"))
                    val qtParsed = parseLegacyTweet(qtJson, qUser, null)
                    QuotedTweet.fromTweet(qtParsed)
                }
            } else null
            parseLegacyTweet(tJson, uJson, quotedTweet)
        }.distinctBy { it.id }

        return TimelinePage(
            tweets = parsed,
            nextCursor = bottomCursor,
            isFromPublicFallback = false,
            sourceInfo = "X Live Search",
        )
    }

    /**
     * Parses X's v1.1 universal typeahead (`i/api/1.1/search/typeahead.json?result_type=events,users,topics`)
     * returning matching #hashtags, trending topics/events, AND user accounts.
     */
    fun parseUniversalTypeahead(rawJson: String): UniversalSearchSuggestions {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return UniversalSearchSuggestions()

        val hashtags = LinkedHashSet<String>()
        val topics = LinkedHashSet<String>()

        val topicsArr = root.optJSONArray("topics")
        if (topicsArr != null) {
            for (i in 0 until topicsArr.length()) {
                val t = topicsArr.optJSONObject(i)?.optString("topic")?.trim().orEmpty()
                if (t.isBlank()) continue
                if (t.startsWith("#")) {
                    hashtags.add(t)
                } else {
                    topics.add(t)
                }
            }
        }

        val eventsArr = root.optJSONArray("events")
        if (eventsArr != null) {
            for (i in 0 until eventsArr.length()) {
                val e = eventsArr.optJSONObject(i)?.optString("topic")?.trim().orEmpty()
                if (e.isNotBlank()) {
                    if (e.startsWith("#")) hashtags.add(e) else topics.add(e)
                }
            }
        }

        val users = parseTypeaheadUsers(rawJson)
        return UniversalSearchSuggestions(
            hashtags = hashtags.toList(),
            topics = topics.toList(),
            users = users,
        )
    }

    /**
     * Parses X's v1.1 typeahead search (`i/api/1.1/search/typeahead.json`) to return
     * all accounts whose handle or display name matches a partial keyword.
     */
    fun parseTypeaheadUsers(rawJson: String): List<XUser> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("users") ?: return emptyList()
        val users = mutableListOf<XUser>()
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i) ?: continue
            val handle = u.optString("screen_name")
            if (handle.isBlank()) continue
            val followers = maxOf(
                u.optLong("followers_count", 0L),
                u.optLong("normal_followers_count", 0L),
            )
            users.add(
                XUser(
                    id = u.optString("id_str").ifBlank { u.optString("id", handle) },
                    handle = handle,
                    displayName = u.optString("name").ifBlank { handle },
                    avatarUrl = u.optString("profile_image_url_https").replace("_normal", "_400x400"),
                    bio = u.optString("description"),
                    isVerified = u.optBoolean("verified") || u.optBoolean("ext_is_blue_verified"),
                    followersCount = followers,
                    followingCount = u.optLong("friends_count", 0L),
                    tweetsCount = u.optLong("statuses_count", 0L),
                    isFollowing = u.optJSONObject("social_context")?.optBoolean("following") == true,
                )
            )
        }
        return users.distinctBy { it.handle.lowercase() }
    }

    fun parseThreadDetail(rawJson: String, focalTweetId: String): ThreadConversation? {
        val page = parseTimeline(rawJson, "X GraphQL ThreadDetail")
        val organicTweets = page.tweets.filterNot { it.isPromoted }
        if (organicTweets.isEmpty()) return null
        val focalIdx = organicTweets.indexOfFirst { it.id == focalTweetId }
        return if (focalIdx >= 0) {
            ThreadConversation(
                ancestors = organicTweets.subList(0, focalIdx),
                focalTweet = organicTweets[focalIdx],
                replies = organicTweets.drop(focalIdx + 1),
                bottomCursor = page.nextCursor,
            )
        } else {
            ThreadConversation(
                ancestors = emptyList(),
                focalTweet = organicTweets.first(),
                replies = organicTweets.drop(1),
                bottomCursor = page.nextCursor,
            )
        }
    }

    fun parseUserResult(rawJson: String): XUser? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val data = root.optJSONObject("data") ?: return null
        val userResult = data.optJSONObject("user")?.optJSONObject("result")
            ?: data.optJSONObject("user_result_by_screen_name")?.optJSONObject("result")
            ?: data.optJSONObject("viewer")?.optJSONObject("user_results")?.optJSONObject("result")
            ?: return null
        return parseUserObject(userResult)
    }

    fun parseUsersTimeline(rawJson: String): List<XUser> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val instructions = findInstructionsArray(root) ?: return emptyList()
        val users = mutableListOf<XUser>()

        fun extractFromItemContent(itemContent: JSONObject?) {
            val userObj = itemContent
                ?.optJSONObject("user_results")
                ?.optJSONObject("result")
                ?: return
            parseUserObject(userObj)?.let { users.add(it) }
        }

        for (i in 0 until instructions.length()) {
            val instruction = instructions.optJSONObject(i) ?: continue

            val directModuleItems = instruction.optJSONArray("moduleItems")
            if (directModuleItems != null) {
                for (k in 0 until directModuleItems.length()) {
                    extractFromItemContent(
                        directModuleItems.optJSONObject(k)
                            ?.optJSONObject("item")
                            ?.optJSONObject("itemContent")
                    )
                }
            }

            val entries = instruction.optJSONArray("entries")
                ?: instruction.optJSONObject("entry")?.let { JSONArray().put(it) }
                ?: continue
            for (j in 0 until entries.length()) {
                val content = entries.optJSONObject(j)?.optJSONObject("content") ?: continue
                extractFromItemContent(content.optJSONObject("itemContent"))
                val moduleItems = content.optJSONArray("items")
                if (moduleItems != null) {
                    for (k in 0 until moduleItems.length()) {
                        extractFromItemContent(
                            moduleItems.optJSONObject(k)
                                ?.optJSONObject("item")
                                ?.optJSONObject("itemContent")
                        )
                    }
                }
            }
        }
        return users.distinctBy { it.handle.lowercase() }
    }

    /**
     * Parses X REST v1.1 batch user lookup (`i/api/1.1/users/lookup.json`) returning full
     * follower counts, bios, and verification flags for up to 100 accounts in one call.
     */
    fun parseLegacyUsersArray(rawJson: String): List<XUser> {
        val arr = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()
        val users = mutableListOf<XUser>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("screen_name").isBlank()) continue
            users.add(parseLegacyUser(obj))
        }
        return users
    }

    /**
     * Parses X REST v1.1 authenticated `statuses/user_timeline.json` arrays as a resilient
     * fallback when GraphQL `UserTweetsAndReplies` or `UserTweets` rotates hashes.
     */
    fun parseLegacyTweetsArray(rawJson: String): List<Tweet> {
        val arr = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()
        val tweets = mutableListOf<Tweet>()
        for (i in 0 until arr.length()) {
            val raw = arr.optJSONObject(i) ?: continue
            if (raw.optJSONObject("promotedMetadata") != null ||
                raw.optJSONObject("promoted_metadata") != null ||
                raw.optBoolean("is_promoted")
            ) {
                continue
            }
            val rt = raw.optJSONObject("retweeted_status")
            val target = rt ?: raw
            val userObj = target.optJSONObject("user")
            val quotedObj = target.optJSONObject("quoted_status")
            val quotedTweet = quotedObj?.let { qt ->
                QuotedTweet.fromTweet(parseLegacyTweet(qt, qt.optJSONObject("user"), null))
            }
            val parsed = parseLegacyTweet(target, userObj, quotedTweet)
            val withRepost = if (rt != null) {
                val reposter = raw.optJSONObject("user")?.optString("name").orEmpty()
                    .ifBlank { raw.optJSONObject("user")?.optString("screen_name").orEmpty() }
                parsed.copy(repostedBy = reposter.takeIf { it.isNotBlank() })
            } else {
                parsed
            }
            if (withRepost.id.isNotBlank()) tweets.add(withRepost)
        }
        return tweets.distinctBy { it.id }
    }

    /**
     * Parses X's native long-form Article payload (`tweet.article.article_results.result`)
     * including cover image, preview text, plain text, publication timestamp, Draft.js
     * `content_state.blocks`, and inline `atomic` media blocks resolved via `entityMap` + `media_entities`.
     */
    private fun parseArticle(unwrapped: JSONObject, tweetId: String, authorHandle: String): XArticle? {
        val articleRoot = unwrapped.optJSONObject("article") ?: return null
        val result = articleRoot
            .optJSONObject("article_results")
            ?.optJSONObject("result")
            ?: articleRoot
        return parseArticleNode(result, tweetId, authorHandle)
    }

    /**
     * Parses an X Article node from either X GraphQL (`article.article_results.result`)
     * or FxTwitter (`tweet.article`) JSON, preserving headings, prose, blockquotes, lists,
     * code blocks, dividers, and inline embedded media figures.
     */
    fun parseArticleNode(result: JSONObject, tweetId: String, authorHandle: String): XArticle? {
        val restId = result.optString("rest_id").ifBlank { result.optString("id") }.ifBlank { tweetId }
        val title = result.optString("title").trim()
        val previewText = result.optString("preview_text").trim()
        val plainText = result.optString("plain_text").trim()

        val coverUrl = result.optJSONObject("cover_media")
            ?.optJSONObject("media_info")
            ?.optString("original_img_url")
            .orEmpty()
            .ifBlank {
                result.optJSONObject("cover_media")
                    ?.optString("media_url_https")
                    .orEmpty()
            }
            .ifBlank {
                result.optJSONObject("cover_media")
                    ?.optString("url")
                    .orEmpty()
            }

        val publishedSecs = result.optJSONObject("metadata")
            ?.optLong("first_published_at_secs", 0L)
            ?: 0L
        val publishedAtMs = when {
            publishedSecs > 0L -> publishedSecs * 1000L
            result.optString("created_at").isNotBlank() -> parseTwitterDate(result.optString("created_at"))
            else -> 0L
        }

        // Index media_entities by media_id so atomic Draft.js MEDIA blocks resolve inline image URLs
        val mediaById = LinkedHashMap<String, String>()
        val mediaEntities = result.optJSONArray("media_entities")
        if (mediaEntities != null) {
            for (i in 0 until mediaEntities.length()) {
                val ent = mediaEntities.optJSONObject(i) ?: continue
                val mId = ent.optString("media_id").ifBlank { ent.optString("id") }.trim()
                val mUrl = ent.optJSONObject("media_info")?.optString("original_img_url").orEmpty()
                    .ifBlank { ent.optJSONObject("media_info")?.optString("media_url_https").orEmpty() }
                    .ifBlank { ent.optString("media_url_https") }
                    .ifBlank { ent.optString("url") }
                    .trim()
                if (mId.isNotBlank() && mUrl.isNotBlank()) {
                    mediaById[mId] = mUrl
                }
            }
        }

        // Support both X GraphQL (`content_state`) and FxTwitter (`content`) Draft.js containers
        val contentState = result.optJSONObject("content_state")
            ?: result.optJSONObject("content")

        // Index entityMap (can be either JSONObject keyed by "0","1"... or JSONArray of {key, value})
        val entityByKey = HashMap<String, JSONObject>()
        val entityMapObj = contentState?.optJSONObject("entityMap")
        if (entityMapObj != null) {
            val keys = entityMapObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                entityMapObj.optJSONObject(k)?.let { entityByKey[k] = it }
            }
        } else {
            val entityMapArr = contentState?.optJSONArray("entityMap")
            if (entityMapArr != null) {
                for (i in 0 until entityMapArr.length()) {
                    val entry = entityMapArr.optJSONObject(i) ?: continue
                    val k = entry.optString("key").ifBlank { i.toString() }
                    val v = entry.optJSONObject("value") ?: entry
                    entityByKey[k] = v
                }
            }
        }

        val blocks = mutableListOf<ArticleBlock>()
        val rawBlocks = contentState?.optJSONArray("blocks")
        if (rawBlocks != null) {
            for (i in 0 until rawBlocks.length()) {
                val b = rawBlocks.optJSONObject(i) ?: continue
                val type = b.optString("type").ifBlank { "unstyled" }
                val text = b.optString("text").trim()

                if (type.equals("atomic", ignoreCase = true)) {
                    val entityRanges = b.optJSONArray("entityRanges")
                    var addedAtomic = false
                    if (entityRanges != null) {
                        for (r in 0 until entityRanges.length()) {
                            val key = entityRanges.optJSONObject(r)?.opt("key")?.toString() ?: continue
                            val entity = entityByKey[key] ?: continue
                            val entType = entity.optString("type").uppercase(Locale.US)
                            val data = entity.optJSONObject("data")
                            when {
                                entType == "MEDIA" || entType == "IMAGE" -> {
                                    val caption = data?.optString("caption").orEmpty().trim()
                                    val mediaItems = data?.optJSONArray("mediaItems")
                                    if (mediaItems != null && mediaItems.length() > 0) {
                                        for (m in 0 until mediaItems.length()) {
                                            val item = mediaItems.optJSONObject(m) ?: continue
                                            val mId = item.optString("mediaId").ifBlank { item.optString("media_id") }
                                            val imgUrl = mediaById[mId].orEmpty()
                                                .ifBlank { item.optString("url") }
                                                .trim()
                                            if (imgUrl.isNotBlank()) {
                                                blocks.add(
                                                    ArticleBlock(
                                                        type = "media",
                                                        text = caption,
                                                        imageUrl = imgUrl,
                                                        caption = caption,
                                                    )
                                                )
                                                addedAtomic = true
                                            }
                                        }
                                    } else {
                                        val directUrl = data?.optString("url").orEmpty()
                                            .ifBlank { data?.optString("src").orEmpty() }
                                            .ifBlank {
                                                val mid = data?.optString("mediaId").orEmpty()
                                                    .ifBlank { data?.optString("media_id").orEmpty() }
                                                mediaById[mid].orEmpty()
                                            }
                                            .trim()
                                        if (directUrl.isNotBlank()) {
                                            blocks.add(
                                                ArticleBlock(
                                                    type = "media",
                                                    text = caption,
                                                    imageUrl = directUrl,
                                                    caption = caption,
                                                )
                                            )
                                            addedAtomic = true
                                        }
                                    }
                                }
                                entType == "DIVIDER" -> {
                                    blocks.add(ArticleBlock(type = "divider"))
                                    addedAtomic = true
                                }
                                entType == "MARKDOWN" -> {
                                    val md = data?.optString("markdown").orEmpty().trim()
                                    if (md.isNotBlank()) {
                                        blocks.add(ArticleBlock(type = "code-block", text = md))
                                        addedAtomic = true
                                    }
                                }
                            }
                        }
                    }
                    if (!addedAtomic && text.isNotBlank()) {
                        blocks.add(ArticleBlock(type = "unstyled", text = text))
                    }
                    continue
                }

                if (text.isBlank()) continue
                // Attach first LINK entity URL if the block is a standalone link callout
                var blockLinkUrl = ""
                val ranges = b.optJSONArray("entityRanges")
                if (ranges != null && ranges.length() == 1) {
                    val range = ranges.optJSONObject(0)
                    val key = range?.opt("key")?.toString()
                    val entity = key?.let { entityByKey[it] }
                    if (entity != null && entity.optString("type").equals("LINK", ignoreCase = true)) {
                        val url = entity.optJSONObject("data")?.optString("url").orEmpty().trim()
                        if (url.isNotBlank() && range.optInt("length", 0) >= (text.length - 4).coerceAtLeast(1)) {
                            blockLinkUrl = url
                        }
                    }
                }
                blocks.add(ArticleBlock(type = type, text = text, linkUrl = blockLinkUrl))
            }
        }
        if (blocks.isEmpty() && plainText.isNotBlank()) {
            plainText.split(Regex("\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { para -> blocks.add(ArticleBlock(type = "unstyled", text = para)) }
        }

        if (title.isBlank() && previewText.isBlank() && blocks.isEmpty()) return null

        val wordSource = when {
            blocks.any { it.text.isNotBlank() } -> blocks.joinToString(" ") { it.text }
            plainText.isNotBlank() -> plainText
            else -> "$title $previewText"
        }
        val words = wordSource.split(Regex("\\s+")).count { it.isNotBlank() }
        val readTimeMinutes = ceil(words / 220.0).toInt().coerceAtLeast(1)
        val cleanHandle = authorHandle.removePrefix("@").ifBlank { "i" }
        val url = "https://x.com/$cleanHandle/article/$restId"

        return XArticle(
            id = restId,
            title = title.ifBlank { previewText.take(80) },
            previewText = previewText.ifBlank { blocks.firstOrNull { it.text.isNotBlank() }?.text.orEmpty().take(240) },
            bodyBlocks = blocks,
            coverImageUrl = coverUrl,
            readTimeMinutes = readTimeMinutes,
            publishedAtEpochMs = publishedAtMs,
            articleUrl = url,
        )
    }

    fun parseTrendsFromGuide(rawJson: String): List<TrendItem> {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.startsWith("[")) {
            return parseTrendsRestV1(trimmed)
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val trends = mutableListOf<TrendItem>()
        val seenNames = HashSet<String>()

        fun addTrend(
            name: String,
            category: String,
            volume: String,
            rawQuery: String = name,
        ) {
            val cleanName = name.trim()
            if (cleanName.isBlank()) return
            val key = cleanName.lowercase()
            if (!seenNames.add(key)) return
            val cleanQuery = rawQuery.trim().ifBlank { cleanName }
            trends.add(
                TrendItem(
                    id = key,
                    name = cleanName,
                    category = category.trim().ifBlank { "Trending on X" },
                    tweetVolumeLabel = volume.trim(),
                    query = cleanQuery,
                )
            )
        }

        fun extractQueryFromUrl(urlObj: JSONObject?): String {
            if (urlObj == null) return ""
            val rawUrl = urlObj.optString("url")
            if (rawUrl.isBlank()) return ""
            val match = Regex("""[?&]q=([^&]+)""").find(rawUrl)?.groupValues?.getOrNull(1)
            return if (!match.isNullOrBlank()) {
                runCatching { java.net.URLDecoder.decode(match, "UTF-8") }.getOrDefault("")
            } else {
                ""
            }
        }

        fun extractTrendObject(obj: JSONObject) {
            // Skip promoted trends/ads
            if (obj.has("promotedMetadata") || obj.has("promoted_metadata")) return

            val typeName = obj.optString("__typename")
            val itemType = obj.optString("itemType")
            val isGraphqlTrend = typeName.equals("TimelineTrend", ignoreCase = true) ||
                itemType.equals("TimelineTrend", ignoreCase = true) ||
                (obj.has("trend_metadata") && obj.has("name"))

            if (isGraphqlTrend) {
                val name = obj.optString("name")
                val meta = obj.optJSONObject("trend_metadata") ?: obj.optJSONObject("trendMetadata")
                val domain = meta?.optString("domain_context")
                    .orEmpty()
                    .ifBlank { meta?.optString("domainContext").orEmpty() }
                val volume = meta?.optString("meta_description")
                    .orEmpty()
                    .ifBlank { meta?.optString("metaDescription").orEmpty() }
                val urlQuery = extractQueryFromUrl(obj.optJSONObject("trend_url") ?: obj.optJSONObject("url"))
                addTrend(name, domain, volume, urlQuery.ifBlank { name })
                return
            }

            // Legacy guide.json `trend` sub-object
            val legacyTrend = obj.optJSONObject("trend")
            if (legacyTrend != null && !legacyTrend.has("promotedMetadata") && !legacyTrend.has("promoted_metadata")) {
                val name = legacyTrend.optString("name")
                val meta = legacyTrend.optJSONObject("trendMetadata") ?: legacyTrend.optJSONObject("trend_metadata")
                val domain = meta?.optString("domainContext")
                    .orEmpty()
                    .ifBlank { meta?.optString("domain_context").orEmpty() }
                val volume = meta?.optString("metaDescription")
                    .orEmpty()
                    .ifBlank { meta?.optString("meta_description").orEmpty() }
                val urlQuery = extractQueryFromUrl(
                    legacyTrend.optJSONObject("url") ?: legacyTrend.optJSONObject("trend_url")
                )
                addTrend(name, domain, volume, urlQuery.ifBlank { name })
            }
        }

        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > 14 || trends.size >= 30) return
            when (node) {
                is JSONObject -> {
                    extractTrendObject(node)
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "tweets" || k == "users" || k == "tweet_results") continue
                        walk(node.opt(k), depth + 1)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walk(node.opt(i), depth + 1)
                    }
                }
            }
        }

        walk(root, 0)
        return trends
    }

    fun parseTrendsRestV1(rawJson: String): List<TrendItem> {
        val arr = runCatching { JSONArray(rawJson.trim()) }.getOrNull() ?: return emptyList()
        val trends = mutableListOf<TrendItem>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val loc = arr.optJSONObject(i) ?: continue
            val locationName = loc.optJSONArray("locations")?.optJSONObject(0)?.optString("name").orEmpty()
            val category = if (locationName.isNotBlank()) "Trending · $locationName" else "Trending Worldwide"
            val list = loc.optJSONArray("trends") ?: continue
            for (j in 0 until list.length()) {
                val t = list.optJSONObject(j) ?: continue
                if (!t.isNull("promoted_content") && t.optString("promoted_content").isNotBlank()) continue
                val name = t.optString("name").trim()
                if (name.isBlank() || !seen.add(name.lowercase())) continue
                val vol = if (!t.isNull("tweet_volume")) t.optLong("tweet_volume", 0L) else 0L
                val volLabel = if (vol > 0L) "${formatCompactTrendVolume(vol)} posts" else ""
                val rawQuery = t.optString("query").let { q ->
                    if (q.isNotBlank()) runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrDefault(name) else name
                }
                trends.add(
                    TrendItem(
                        id = name.lowercase(),
                        name = name,
                        category = category,
                        tweetVolumeLabel = volLabel,
                        query = rawQuery.ifBlank { name },
                    )
                )
                if (trends.size >= 25) return trends
            }
        }
        return trends
    }

    private fun formatCompactTrendVolume(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }

    fun parseNotificationsV2(rawJson: String): List<NotificationItem> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val globalObjects = root.optJSONObject("globalObjects") ?: return emptyList()
        val notificationsObj = globalObjects.optJSONObject("notifications") ?: return emptyList()
        val usersObj = globalObjects.optJSONObject("users") ?: JSONObject()
        val tweetsObj = globalObjects.optJSONObject("tweets") ?: JSONObject()

        val list = mutableListOf<NotificationItem>()
        val keys = notificationsObj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val n = notificationsObj.optJSONObject(id) ?: continue
            val msg = n.optJSONObject("message")?.optString("text").orEmpty()
            val iconId = n.optJSONObject("icon")?.optString("id").orEmpty()
            val timestamp = n.optString("timestampMs").toLongOrNull() ?: System.currentTimeMillis()
            val fromUsers = n.optJSONObject("template")
                ?.optJSONObject("aggregateUserActionsV1")
                ?.optJSONArray("fromUsers")
            val firstUserId = fromUsers?.optJSONObject(0)
                ?.optJSONObject("user")?.optString("id").orEmpty()
            val userJson = usersObj.optJSONObject(firstUserId)
            val actor = if (userJson != null) {
                parseLegacyUser(userJson)
            } else {
                XUser(id = firstUserId.ifBlank { id }, handle = "x", displayName = "X Activity")
            }

            val targetTweetId = n.optJSONObject("template")
                ?.optJSONObject("aggregateUserActionsV1")
                ?.optJSONArray("targetObjects")
                ?.optJSONObject(0)?.optJSONObject("tweet")?.optString("id").orEmpty()
            val targetTweet = tweetsObj.optJSONObject(targetTweetId)?.let { tJson ->
                val authorJson = usersObj.optJSONObject(tJson.optString("user_id_str"))
                parseLegacyTweet(tJson, authorJson, null)
            }

            val type = when {
                iconId.contains("heart", true) || msg.contains("liked", true) -> NotificationType.LIKE
                iconId.contains("retweet", true) || msg.contains("repost", true) -> NotificationType.REPOST
                iconId.contains("person", true) || msg.contains("followed", true) -> NotificationType.FOLLOW
                msg.contains("quoted", true) -> NotificationType.QUOTE
                msg.contains("replied", true) -> NotificationType.REPLY
                else -> NotificationType.MENTION
            }

            list.add(
                NotificationItem(
                    id = id,
                    type = type,
                    message = msg.ifBlank { "${actor.displayName} ${type.label.lowercase()}" },
                    actor = actor,
                    targetTweet = targetTweet,
                    timestampEpochMs = timestamp,
                )
            )
        }
        return list.sortedByDescending { it.timestampEpochMs }
    }

    fun parseCreatedTweet(rawJson: String): Tweet? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val result = root.optJSONObject("data")
            ?.optJSONObject("create_tweet")
            ?.optJSONObject("tweet_results")
            ?.optJSONObject("result")
            ?: return null
        return parseTweetResult(result, false)
    }

    fun parseTweetResult(rawResult: JSONObject, isPromoted: Boolean = false): Tweet? {
        val unwrapped = when (rawResult.optString("__typename")) {
            "TweetWithVisibilityResults" -> rawResult.optJSONObject("tweet") ?: return null
            "TweetTombstone", "TweetUnavailable" -> return null
            else -> rawResult
        }

        val legacy = unwrapped.optJSONObject("legacy") ?: return null
        val outerAuthor = unwrapped.optJSONObject("core")
            ?.optJSONObject("user_results")
            ?.optJSONObject("result")
            ?.let { parseUserObject(it) }

        // Handle Retweet wrapper cleanly so the inner tweet is shown with a Reposted header
        val retweetedResult = legacy.optJSONObject("retweeted_status_result")
            ?.optJSONObject("result")
        if (retweetedResult != null) {
            val inner = parseTweetResult(retweetedResult, isPromoted) ?: return null
            return inner.copy(
                repostedBy = outerAuthor?.displayName?.ifBlank { "@${outerAuthor.handle}" }
            )
        }

        val id = unwrapped.optString("rest_id").ifBlank { legacy.optString("id_str") }
        if (id.isBlank()) return null

        val author = outerAuthor ?: XUser(
            id = legacy.optString("user_id_str", "0"),
            handle = "x_user",
            displayName = "X User",
        )

        // Prefer full long-form note_tweet text when present
        val noteText = unwrapped.optJSONObject("note_tweet")
            ?.optJSONObject("note_tweet_results")
            ?.optJSONObject("result")
            ?.optString("text")
        val rawText = noteText?.takeIf { it.isNotBlank() } ?: legacy.optString("full_text")
        val quotedPermalinkUrl = legacy.optJSONObject("quoted_status_permalink")?.optString("url").orEmpty()
        val entitiesObj = legacy.optJSONObject("entities")

        val mediaList = parseMediaList(
            legacy.optJSONObject("extended_entities") ?: entitiesObj
        )
        val createdAtMs = parseTwitterDate(legacy.optString("created_at"))
        val poll = XPollParser.parseCardJson(
            cardObj = unwrapped.optJSONObject("card"),
            fallbackCreatedAtMs = createdAtMs,
        )
        val article = parseArticle(unwrapped, id, author.handle)

        // Parse Quote Tweet cleanly from either top-level or legacy node (including TweetTombstone states)
        val quotedStatusId = legacy.optString("quoted_status_id_str").takeIf { it.isNotBlank() }
        val quotedObj = unwrapped.optJSONObject("quoted_status_result")?.optJSONObject("result")
            ?: legacy.optJSONObject("quoted_status_result")?.optJSONObject("result")
        val quoted = when {
            quotedObj == null -> null
            quotedObj.optString("__typename") == "TweetTombstone" -> {
                val reason = quotedObj.optJSONObject("tombstone")
                    ?.optJSONObject("text")
                    ?.optString("text")
                    .orEmpty()
                    .ifBlank { "This post is unavailable." }
                QuotedTweet.unavailable(quotedStatusId.orEmpty(), reason)
            }
            else -> parseTweetResult(quotedObj, false)?.let { qt ->
                QuotedTweet.fromTweet(qt)
            } ?: quotedStatusId?.let { QuotedTweet.unavailable(it) }
        }

        // Parse rich Link Preview card (skip when tweet is a poll or already carries an X Article card)
        val (linkPreview, strippedShortUrl) = if (poll == null && article == null) {
            parseLinkPreview(unwrapped.optJSONObject("card"), entitiesObj, quotedPermalinkUrl)
        } else {
            null to ""
        }

        val expandedText = expandUrls(
            text = rawText,
            entities = entitiesObj,
            quotedShortUrl = quotedPermalinkUrl,
            strippedPreviewShortUrl = strippedShortUrl,
            strippedPreviewExpandedUrl = linkPreview?.url.orEmpty(),
        )

        val viewsCount = unwrapped.optJSONObject("views")?.optString("count")?.toLongOrNull() ?: 0L

        return Tweet(
            id = id,
            conversationId = legacy.optString("conversation_id_str").ifBlank { id },
            author = author,
            text = expandedText,
            createdAtEpochMs = createdAtMs,
            media = mediaList,
            poll = poll,
            quotedTweet = quoted,
            quotedTweetId = quoted?.id?.takeIf { it.isNotBlank() } ?: quotedStatusId,
            linkPreview = linkPreview,
            article = article,
            replyToHandle = legacy.optString("in_reply_to_screen_name").takeIf { it.isNotBlank() },
            replyToTweetId = legacy.optString("in_reply_to_status_id_str").takeIf { it.isNotBlank() },
            metrics = TweetMetrics(
                replies = legacy.optLong("reply_count"),
                reposts = legacy.optLong("retweet_count"),
                quotes = legacy.optLong("quote_count"),
                likes = legacy.optLong("favorite_count"),
                bookmarks = legacy.optLong("bookmark_count"),
                views = viewsCount,
            ),
            viewerState = ViewerState(
                isLiked = legacy.optBoolean("favorited"),
                isReposted = legacy.optBoolean("retweeted"),
                isBookmarked = legacy.optBoolean("bookmarked"),
            ),
            isSensitive = legacy.optBoolean("possibly_sensitive"),
            isPromoted = isPromoted,
            lang = legacy.optString("lang"),
            sourceLabel = stripHtml(unwrapped.optString("source")),
        )
    }

    /**
     * Extracts the logged-in viewer's full XUser directly from authenticated `https://x.com/home`
     * HTML (`window.__INITIAL_STATE__`), immune to GraphQL Viewer/UserByRestId hash rotations.
     */
    fun parseInitialStateViewerUser(html: String, preferredUserId: String? = null): XUser? {
        val marker = "window.__INITIAL_STATE__="
        val startIdx = html.indexOf(marker)
        if (startIdx < 0) return null
        val jsonStart = startIdx + marker.length
        val endIdx = html.indexOf(";</script>", jsonStart)
            .takeIf { it > jsonStart }
            ?: html.indexOf("\n", jsonStart).takeIf { it > jsonStart }
            ?: return null
        val rawState = html.substring(jsonStart, endIdx).trim().removeSuffix(";")
        val stateObj = runCatching { JSONObject(rawState) }.getOrNull() ?: return null

        val sessionUserId = stateObj.optJSONObject("session")?.optString("user_id").orEmpty()
            .ifBlank { preferredUserId.orEmpty() }
        val usersMap = stateObj.optJSONObject("entities")
            ?.optJSONObject("users")
            ?.optJSONObject("entities")
            ?: return null

        val userObj = sessionUserId.takeIf { it.isNotBlank() }?.let { usersMap.optJSONObject(it) }
            ?: run {
                val keys = usersMap.keys()
                var first: JSONObject? = null
                while (keys.hasNext()) {
                    val candidate = usersMap.optJSONObject(keys.next())
                    if (candidate != null && candidate.optString("screen_name").isNotBlank()) {
                        first = candidate
                        break
                    }
                }
                first
            }
            ?: return null

        return parseLegacyUser(userObj)
    }

    /**
     * Parses BOTH X's modern 2025/2026 GraphQL user schema (`core`, `avatar`, `location`,
     * `relationship_perspectives`, `relationship_counts`, `verification`) and the classic `legacy` schema.
     */
    internal fun parseUserObject(rawUser: JSONObject): XUser? {
        val core = rawUser.optJSONObject("core")
        val legacy = rawUser.optJSONObject("legacy")
        val avatarObj = rawUser.optJSONObject("avatar")
        val locationObj = rawUser.optJSONObject("location")
        val verificationObj = rawUser.optJSONObject("verification")
        val relationshipObj = rawUser.optJSONObject("relationship_perspectives")
        val relCounts = rawUser.optJSONObject("relationship_counts")
        val profileBioObj = rawUser.optJSONObject("profile_bio")

        val handle = core?.optString("screen_name").orEmpty()
            .ifBlank { legacy?.optString("screen_name").orEmpty() }
            .ifBlank { rawUser.optString("screen_name") }
        if (handle.isBlank()) return null

        val displayName = core?.optString("name").orEmpty()
            .ifBlank { legacy?.optString("name").orEmpty() }
            .ifBlank { rawUser.optString("name") }
            .ifBlank { handle }

        val id = rawUser.optString("rest_id")
            .ifBlank { legacy?.optString("id_str").orEmpty() }
            .ifBlank { rawUser.optString("id_str") }
            .ifBlank { handle }

        val avatarRaw = avatarObj?.optString("image_url").orEmpty()
            .ifBlank { legacy?.optString("profile_image_url_https").orEmpty() }
            .ifBlank { rawUser.optString("profile_image_url_https") }
        val avatar = avatarRaw.replace("_normal", "_400x400")

        val banner = listOf(
            legacy?.optString("profile_banner_url"),
            rawUser.optJSONObject("profile_banner")?.optString("image_url"),
            rawUser.optJSONObject("profile_banner")?.optString("url"),
            rawUser.optJSONObject("banner")?.optString("image_url"),
            rawUser.optJSONObject("banner")?.optString("url"),
            rawUser.optString("profile_banner_url"),
            rawUser.optString("banner_url"),
        ).firstNotNullOfOrNull { candidate ->
            candidate?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        }.orEmpty()

        val rawBio = profileBioObj?.optString("description").orEmpty()
            .ifBlank { legacy?.optString("description").orEmpty() }
            .ifBlank { rawUser.optString("description") }
        val descriptionUrls = profileBioObj?.optJSONObject("entities")
            ?.optJSONObject("description")?.optJSONArray("urls")
            ?: legacy?.optJSONObject("entities")
                ?.optJSONObject("description")?.optJSONArray("urls")
            ?: rawUser.optJSONObject("entities")
                ?.optJSONObject("description")?.optJSONArray("urls")
        val bio = expandBioUrls(rawBio, descriptionUrls)

        val websiteUrl = legacy?.optJSONObject("entities")
            ?.optJSONObject("url")?.optJSONArray("urls")
            ?.optJSONObject(0)
            ?.let { it.optString("expanded_url").ifBlank { it.optString("url") } }
            .orEmpty()
            .ifBlank {
                rawUser.optJSONObject("entities")
                    ?.optJSONObject("url")?.optJSONArray("urls")
                    ?.optJSONObject(0)
                    ?.let { it.optString("expanded_url").ifBlank { it.optString("url") } }
                    .orEmpty()
            }
            .ifBlank { legacy?.optString("url").orEmpty() }

        val createdAt = core?.optString("created_at").orEmpty()
            .ifBlank { legacy?.optString("created_at").orEmpty() }
            .ifBlank { rawUser.optString("created_at") }

        val location = locationObj?.optString("location").orEmpty()
            .ifBlank { legacy?.optString("location").orEmpty() }
            .ifBlank { rawUser.optString("location") }

        val followers = listOfNotNull(
            legacy?.optLong("followers_count", -1L)?.takeIf { it >= 0L },
            legacy?.optLong("normal_followers_count", -1L)?.takeIf { it >= 0L },
            relCounts?.optLong("followers", -1L)?.takeIf { it >= 0L },
            rawUser.optLong("followers_count", -1L).takeIf { it >= 0L },
        ).maxOrNull() ?: 0L

        val following = listOfNotNull(
            legacy?.optLong("friends_count", -1L)?.takeIf { it >= 0L },
            relCounts?.optLong("following", -1L)?.takeIf { it >= 0L },
            rawUser.optLong("friends_count", -1L).takeIf { it >= 0L },
        ).maxOrNull() ?: 0L

        val statuses = listOfNotNull(
            legacy?.optLong("statuses_count", -1L)?.takeIf { it >= 0L },
            rawUser.optLong("statuses_count", -1L).takeIf { it >= 0L },
        ).maxOrNull() ?: 0L

        return XUser(
            id = id,
            handle = handle,
            displayName = displayName,
            avatarUrl = avatar,
            bannerUrl = banner,
            bio = bio,
            location = location,
            websiteUrl = websiteUrl,
            isVerified = rawUser.optBoolean("is_blue_verified") ||
                verificationObj?.optBoolean("verified") == true ||
                legacy?.optBoolean("verified") == true,
            followersCount = followers,
            followingCount = following,
            tweetsCount = statuses,
            joinedLabel = formatJoinedMonth(createdAt),
            joinedAtEpochMs = parseTwitterDateOrZero(createdAt),
            isFollowing = relationshipObj?.optBoolean("following") == true ||
                legacy?.optBoolean("following") == true,
            isMuted = relationshipObj?.optBoolean("muting") == true ||
                legacy?.optBoolean("muting") == true,
            isBlocked = relationshipObj?.optBoolean("blocking") == true ||
                legacy?.optBoolean("blocking") == true,
        )
    }

    internal fun expandBioUrls(bio: String, descriptionUrls: JSONArray?): String {
        if (bio.isBlank() || descriptionUrls == null || descriptionUrls.length() == 0) return bio
        var expanded = bio
        for (i in 0 until descriptionUrls.length()) {
            val u = descriptionUrls.optJSONObject(i) ?: continue
            val shortUrl = u.optString("url")
            val expandedUrl = u.optString("expanded_url").ifBlank { u.optString("display_url") }
            if (shortUrl.isNotBlank() && expandedUrl.isNotBlank()) {
                expanded = expanded.replace(shortUrl, expandedUrl)
            }
        }
        return expanded
    }

    fun parseLegacyUser(json: JSONObject): XUser {
        val handle = json.optString("screen_name", "user")
        val followers = json.optLong("followers_count", 0L)
            .coerceAtLeast(json.optLong("normal_followers_count", 0L))
        val createdAt = json.optString("created_at")
        val entities = json.optJSONObject("entities")
        val expandedBio = expandBioUrls(
            json.optString("description"),
            entities?.optJSONObject("description")?.optJSONArray("urls"),
        )
        val websiteUrl = entities?.optJSONObject("url")?.optJSONArray("urls")
            ?.optJSONObject(0)
            ?.let { it.optString("expanded_url").ifBlank { it.optString("url") } }
            .orEmpty()
            .ifBlank { json.optString("url") }
        return XUser(
            id = json.optString("id_str", handle),
            handle = handle,
            displayName = json.optString("name", handle).ifBlank { handle },
            avatarUrl = json.optString("profile_image_url_https").replace("_normal", "_400x400"),
            bannerUrl = json.optString("profile_banner_url"),
            bio = expandedBio,
            location = json.optString("location"),
            websiteUrl = websiteUrl,
            isVerified = json.optBoolean("verified") ||
                json.optBoolean("ext_is_blue_verified") ||
                json.optBoolean("is_blue_verified"),
            followersCount = followers,
            followingCount = json.optLong("friends_count"),
            tweetsCount = json.optLong("statuses_count"),
            joinedLabel = formatJoinedMonth(createdAt),
            joinedAtEpochMs = parseTwitterDateOrZero(createdAt),
            isFollowing = json.optBoolean("following"),
        )
    }

    private fun parseLegacyTweet(
        json: JSONObject,
        userJson: JSONObject?,
        quotedTweet: QuotedTweet?,
    ): Tweet {
        val id = json.optString("id_str")
        val author = userJson?.let { parseLegacyUser(it) }
            ?: XUser(id = json.optString("user_id_str"), handle = "xuser", displayName = "X User")
        val rawText = json.optString("full_text").ifBlank { json.optString("text") }
        val entitiesObj = json.optJSONObject("entities")
        val quotedPermalinkUrl = json.optJSONObject("quoted_status_permalink")?.optString("url").orEmpty()
        val (linkPreview, strippedShort) = parseLinkPreview(json.optJSONObject("card"), entitiesObj, quotedPermalinkUrl)
        val expanded = expandUrls(
            text = rawText,
            entities = entitiesObj,
            quotedShortUrl = quotedPermalinkUrl,
            strippedPreviewShortUrl = strippedShort,
            strippedPreviewExpandedUrl = linkPreview?.url.orEmpty(),
        )
        val media = parseMediaList(json.optJSONObject("extended_entities") ?: entitiesObj)
        val quotedStatusId = json.optString("quoted_status_id_str").takeIf { it.isNotBlank() }
        return Tweet(
            id = id,
            conversationId = json.optString("conversation_id_str").ifBlank { id },
            author = author,
            text = expanded,
            createdAtEpochMs = parseTwitterDate(json.optString("created_at")),
            media = media,
            quotedTweet = quotedTweet,
            quotedTweetId = quotedTweet?.id?.takeIf { it.isNotBlank() } ?: quotedStatusId,
            linkPreview = linkPreview,
            metrics = TweetMetrics(
                replies = json.optLong("reply_count"),
                reposts = json.optLong("retweet_count"),
                quotes = json.optLong("quote_count"),
                likes = json.optLong("favorite_count"),
            ),
            viewerState = ViewerState(
                isLiked = json.optBoolean("favorited"),
                isReposted = json.optBoolean("retweeted"),
            ),
            lang = json.optString("lang"),
        )
    }

    internal fun isPromotedTimelineNode(
        entryId: String,
        itemContent: JSONObject?,
        wrapperObj: JSONObject? = null,
        tweetResult: JSONObject? = null,
    ): Boolean {
        if (entryId.contains("promoted", ignoreCase = true)) return true
        if (itemContent != null) {
            if (itemContent.optJSONObject("promotedMetadata") != null ||
                itemContent.optJSONObject("promoted_metadata") != null ||
                itemContent.optJSONObject("adMetadataContainer") != null ||
                itemContent.optJSONObject("ad_metadata_container") != null
            ) {
                return true
            }
            val itemType = itemContent.optString("itemType")
            val typename = itemContent.optString("__typename")
            val displayType = itemContent.optString("tweetDisplayType")
            if (itemType.contains("Promoted", ignoreCase = true) ||
                typename.contains("Promoted", ignoreCase = true) ||
                displayType.equals("Promoted", ignoreCase = true)
            ) {
                return true
            }
        }
        if (wrapperObj != null) {
            if (wrapperObj.optJSONObject("promotedMetadata") != null ||
                wrapperObj.optJSONObject("promoted_metadata") != null ||
                wrapperObj.optJSONObject("adMetadataContainer") != null
            ) {
                return true
            }
            val clientEvent = wrapperObj.optJSONObject("clientEventInfo")
            if (clientEvent != null) {
                val component = clientEvent.optString("component")
                val injectionType = clientEvent.optJSONObject("details")
                    ?.optJSONObject("timelinesDetails")
                    ?.optString("injectionType")
                    .orEmpty()
                if (component.contains("promoted", ignoreCase = true) ||
                    injectionType.contains("promoted", ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        if (tweetResult != null) {
            val unwrapped = tweetResult.optJSONObject("tweet") ?: tweetResult
            if (unwrapped.optJSONObject("promotedMetadata") != null ||
                unwrapped.optJSONObject("promoted_metadata") != null ||
                tweetResult.optJSONObject("promotedMetadata") != null ||
                tweetResult.optJSONObject("promoted_metadata") != null
            ) {
                return true
            }
        }
        return false
    }

    internal fun extractMediaDimensions(mediaObj: JSONObject): Pair<Int, Int> {
        val orig = mediaObj.optJSONObject("original_info")
        val origW = orig?.optInt("width", 0) ?: 0
        val origH = orig?.optInt("height", 0) ?: 0
        if (origW > 0 && origH > 0) return origW to origH

        val sizes = mediaObj.optJSONObject("sizes")
        for (key in arrayOf("large", "medium", "small")) {
            val sizeObj = sizes?.optJSONObject(key) ?: continue
            val w = sizeObj.optInt("w", 0)
            val h = sizeObj.optInt("h", 0)
            if (w > 0 && h > 0) return w to h
        }

        val aspect = mediaObj.optJSONObject("video_info")?.optJSONArray("aspect_ratio")
        if (aspect != null && aspect.length() >= 2) {
            val aw = aspect.optInt(0, 0)
            val ah = aspect.optInt(1, 0)
            if (aw > 0 && ah > 0) return (aw * 100) to (ah * 100)
        }

        return 1200 to 675
    }

    private fun parseMediaList(entities: JSONObject?): List<MediaAttachment> {
        val arr = entities?.optJSONArray("media") ?: return emptyList()
        val list = mutableListOf<MediaAttachment>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val typeStr = m.optString("type")
            val mediaType = when (typeStr) {
                "video" -> MediaType.VIDEO
                "animated_gif" -> MediaType.GIF
                else -> MediaType.PHOTO
            }
            val thumb = m.optString("media_url_https")
            val videoInfo = m.optJSONObject("video_info")
            val (bestMp4, dataSaverMp4) = AdaptiveImageQualitySelector.pickVideoVariants(
                videoInfo?.optJSONArray("variants")
            )
            val (width, height) = extractMediaDimensions(m)
            list.add(
                MediaAttachment(
                    id = m.optString("id_str").ifBlank { "$i" },
                    type = mediaType,
                    url = thumb,
                    thumbnailUrl = thumb,
                    videoUrl = bestMp4,
                    dataSaverVideoUrl = dataSaverMp4,
                    width = width,
                    height = height,
                    altText = m.optString("ext_alt_text"),
                    durationMs = videoInfo?.optLong("duration_millis", 0L) ?: 0L,
                )
            )
        }
        return list
    }


    /**
     * Parses link preview metadata from X's card binding_values (`summary`, `summary_large_image`,
     * `unified_card`, `player`) or falls back to the first external URL entity in the tweet.
     * Returns `(LinkPreview?, matchedShortUrlToStrip)`.
     */
    private fun parseLinkPreview(
        cardObj: JSONObject?,
        entities: JSONObject?,
        quotedShortUrl: String,
    ): Pair<LinkPreview?, String> {
        // Locate first external (non-x.com / non-twitter.com) URL entity
        var externalUrl = ""
        var externalShort = ""
        var externalDisplay = ""
        val urls = entities?.optJSONArray("urls")
        if (urls != null) {
            for (i in 0 until urls.length()) {
                val u = urls.optJSONObject(i) ?: continue
                val shortU = u.optString("url")
                if (shortU.isNotBlank() && shortU == quotedShortUrl) continue
                val exp = u.optString("expanded_url").ifBlank { shortU }
                if (exp.isBlank()) continue
                val host = extractHost(exp)
                val isXInternal = host == "x.com" || host.endsWith(".x.com") ||
                    host == "twitter.com" || host.endsWith(".twitter.com") ||
                    host == "t.co"
                if (!isXInternal) {
                    externalUrl = exp
                    externalShort = shortU
                    externalDisplay = u.optString("display_url").ifBlank { host }
                    break
                }
            }
        }

        val legacyCard = cardObj?.optJSONObject("legacy")
        val cardName = legacyCard?.optString("name").orEmpty()
        if (legacyCard != null && !cardName.startsWith("poll")) {
            val bindingValues = legacyCard.optJSONArray("binding_values")
            if (bindingValues != null) {
                val strings = mutableMapOf<String, String>()
                val images = mutableMapOf<String, String>()
                for (i in 0 until bindingValues.length()) {
                    val entry = bindingValues.optJSONObject(i) ?: continue
                    val key = entry.optString("key")
                    val vObj = entry.optJSONObject("value") ?: continue
                    val s = vObj.optString("string_value")
                    if (s.isNotBlank()) strings[key] = s
                    val img = vObj.optJSONObject("image_value")?.optString("url")
                    if (!img.isNullOrBlank()) images[key] = img
                }

                // Unified card JSON payload (X's modern link card format)
                val unifiedJsonStr = strings["unified_card"]
                if (!unifiedJsonStr.isNullOrBlank()) {
                    val unified = runCatching { JSONObject(unifiedJsonStr) }.getOrNull()
                    if (unified != null) {
                        val destMap = unified.optJSONObject("destination_objects")
                        var destUrl = externalUrl
                        if (destMap != null) {
                            val dKeys = destMap.keys()
                            while (dKeys.hasNext()) {
                                val d = destMap.optJSONObject(dKeys.next())
                                val u = d?.optJSONObject("data")?.optJSONObject("url_data")?.optString("url")
                                if (!u.isNullOrBlank()) {
                                    destUrl = u
                                    break
                                }
                            }
                        }
                        var title = ""
                        var subtitle = ""
                        val compMap = unified.optJSONObject("component_objects")
                        if (compMap != null) {
                            val cKeys = compMap.keys()
                            while (cKeys.hasNext()) {
                                val c = compMap.optJSONObject(cKeys.next())?.optJSONObject("data") ?: continue
                                if (title.isBlank()) title = c.optJSONObject("title")?.optString("content").orEmpty()
                                if (subtitle.isBlank()) subtitle = c.optJSONObject("subtitle")?.optString("content").orEmpty()
                            }
                        }
                        var imgUrl = ""
                        val mediaMap = unified.optJSONObject("media_entities")
                        if (mediaMap != null) {
                            val mKeys = mediaMap.keys()
                            if (mKeys.hasNext()) {
                                imgUrl = mediaMap.optJSONObject(mKeys.next())?.optString("media_url_https").orEmpty()
                            }
                        }
                        if (destUrl.isNotBlank()) {
                            val domain = extractHost(destUrl)
                            return LinkPreview(
                                url = destUrl,
                                displayUrl = externalDisplay.ifBlank { domain },
                                domain = domain,
                                title = title,
                                description = subtitle,
                                imageUrl = imgUrl,
                            ) to externalShort
                        }
                    }
                }

                val cardUrl = externalUrl.ifBlank { strings["card_url"].orEmpty() }
                val title = strings["title"].orEmpty()
                val desc = strings["description"].orEmpty()
                val domain = strings["domain"].orEmpty().ifBlank { extractHost(cardUrl) }
                val imgUrl = images["summary_photo_image_large"]
                    ?: images["summary_photo_image_original"]
                    ?: images["photo_image_full_size_large"]
                    ?: images["thumbnail_image_large"]
                    ?: images["thumbnail_image"]
                    ?: images["player_image_large"]
                    ?: images.values.firstOrNull()
                    ?: ""

                if (cardUrl.isNotBlank() && (title.isNotBlank() || desc.isNotBlank() || imgUrl.isNotBlank())) {
                    return LinkPreview(
                        url = cardUrl,
                        displayUrl = externalDisplay.ifBlank { domain },
                        domain = domain,
                        title = title,
                        description = desc,
                        imageUrl = imgUrl,
                    ) to externalShort
                }
            }
        }

        // Plain external link without an X card — build a clean LinkPreview so raw URLs render as rich preview cards
        if (externalUrl.isNotBlank()) {
            val domain = extractHost(externalUrl)
            return LinkPreview(
                url = externalUrl,
                displayUrl = externalDisplay.ifBlank { domain },
                domain = domain,
            ) to externalShort
        }

        return null to ""
    }

    private fun extractHost(url: String): String {
        if (url.isBlank()) return ""
        return runCatching { URI(url).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
    }

    private fun expandUrls(
        text: String,
        entities: JSONObject?,
        quotedShortUrl: String,
        strippedPreviewShortUrl: String = "",
        strippedPreviewExpandedUrl: String = "",
    ): String {
        var cleaned = text
        if (quotedShortUrl.isNotBlank()) {
            cleaned = cleaned.replace(quotedShortUrl, "")
        }
        val urls = entities?.optJSONArray("urls")
        if (urls != null) {
            for (i in 0 until urls.length()) {
                val u = urls.optJSONObject(i) ?: continue
                val shortUrl = u.optString("url")
                val expandedUrl = u.optString("expanded_url")
                val displayUrl = u.optString("display_url").ifBlank { expandedUrl }
                val isPreviewedLink = (strippedPreviewShortUrl.isNotBlank() && shortUrl == strippedPreviewShortUrl) ||
                    (strippedPreviewExpandedUrl.isNotBlank() && expandedUrl == strippedPreviewExpandedUrl)
                if (isPreviewedLink && shortUrl.isNotBlank()) {
                    // Strip raw link URL from tweet body since it renders as a visual LinkPreviewCard below
                    cleaned = cleaned.replace(shortUrl, "")
                } else if (shortUrl.isNotBlank() && displayUrl.isNotBlank()) {
                    cleaned = cleaned.replace(shortUrl, displayUrl)
                }
            }
        }
        if (strippedPreviewExpandedUrl.isNotBlank()) {
            cleaned = cleaned.replace(strippedPreviewExpandedUrl, "")
        }
        val media = entities?.optJSONArray("media")
        if (media != null) {
            for (i in 0 until media.length()) {
                val mUrl = media.optJSONObject(i)?.optString("url").orEmpty()
                if (mUrl.isNotBlank()) cleaned = cleaned.replace(mUrl, "")
            }
        }
        return cleaned
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun findInstructionsArray(node: JSONObject): JSONArray? {
        node.optJSONArray("instructions")?.let { return it }
        val keys = node.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val child = node.optJSONObject(k)
            if (child != null) {
                findInstructionsArray(child)?.let { return it }
            }
        }
        return null
    }

    private fun stripHtml(input: String): String =
        input.replace(Regex("<[^>]*>"), "").trim()

    private fun formatJoinedMonth(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return runCatching {
            val date = twitterDateFormat.get()?.parse(dateStr) ?: return ""
            val locale = Locale.getDefault()
            val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMM")
            val out = SimpleDateFormat(pattern, locale)
            "Joined ${out.format(date)}"
        }.getOrDefault("")
    }
}
