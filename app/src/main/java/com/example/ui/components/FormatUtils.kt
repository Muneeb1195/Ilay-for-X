package com.example.ui.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class EntityKind { MENTION, HASHTAG, URL }

data class TextEntitySpan(
    val kind: EntityKind,
    val rawToken: String,
    val value: String,
    val start: Int,
    val endExclusive: Int,
)

object FormatUtils {
    private val MEDIA_NAME_PARAM_REGEX = Regex("name=[a-zA-Z0-9_]+")

    /**
     * Matches `@mentions`, `#hashtags`, full `https?://` URLs, `www.` URLs, and common bare domains
     * (e.g. `github.com/user`, `x.com/handle`, `example.org/docs`) while avoiding email addresses
     * (`user@domain.com`) via negative lookbehind.
     */
    private val ENTITY_REGEX = Regex(
        """(?<![A-Za-z0-9_])@[A-Za-z0-9_]{1,15}|(?<![A-Za-z0-9_])#[A-Za-z0-9_]+|https?://[^\s<>]+|(?<![A-Za-z0-9_/@])www\.[^\s<>]+|(?<![A-Za-z0-9_/@])(?:[A-Za-z0-9-]+\.)+(?:com|org|net|io|dev|ai|app|co|gg|me|tv|xyz|edu|gov|news|tech|cloud|blog|site|shop|link|ly|to|uk|de|jp|fr|ca|in|au|es|it|nl|br|kr|se)(?:/[^\s<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val TRAILING_URL_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '"', '\'', '>', '…')

    /**
     * Formats a post or activity timestamp using the device's system 12-hour / 24-hour clock
     * and system locale, displaying hours and minutes only (never seconds):
     * - Today: `14:35` (24h) or `2:35 PM` (12h)
     * - Same year: `Nov 22, 14:35` or `22 Nov, 2:35 PM`
     * - Prior year: `Nov 22, 2024`
     */
    fun formatDeviceTime(
        context: Context,
        epochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (epochMs <= 0L) return ""
        val date = Date(epochMs)
        val locale = Locale.getDefault()
        val targetCal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val timeText = DateFormat.getTimeFormat(context).format(date)

        val sameYear = targetCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val sameDay = sameYear && targetCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

        return when {
            sameDay -> timeText
            sameYear -> {
                val monthDayPattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
                val monthDay = SimpleDateFormat(monthDayPattern, locale).format(date)
                "$monthDay, $timeText"
            }
            else -> {
                val yearDatePattern = DateFormat.getBestDateTimePattern(locale, "yMMMd")
                SimpleDateFormat(yearDatePattern, locale).format(date)
            }
        }
    }

    /**
     * Formats a full timestamp for thread focal posts and article headers using the device's
     * system 12h/24h time format (hours & minutes only, no seconds) + medium localized date.
     */
    fun formatFullDeviceTimestamp(context: Context, epochMs: Long): String {
        if (epochMs <= 0L) return ""
        val date = Date(epochMs)
        val timeText = DateFormat.getTimeFormat(context).format(date)
        val dateText = DateFormat.getMediumDateFormat(context).format(date)
        return "$timeText · $dateText"
    }

    /**
     * Formats a profile's join date according to the device's locale (month & year, no seconds).
     */
    fun formatJoinedDate(epochMs: Long, fallbackLabel: String): String {
        if (epochMs > 0L) {
            val locale = Locale.getDefault()
            val pattern = DateFormat.getBestDateTimePattern(locale, "yMMMM")
            val formatted = SimpleDateFormat(pattern, locale).format(Date(epochMs))
            return "Joined $formatted"
        }
        return fallbackLabel
    }

    fun relativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffMin = ((nowMs - epochMs) / 60_000L).coerceAtLeast(0L)
        return when {
            diffMin < 1L -> "<1m"
            diffMin < 60L -> "${diffMin}m"
            diffMin < 1_440L -> "${diffMin / 60L}h"
            diffMin < 7 * 1_440L -> "${diffMin / 1_440L}d"
            else -> {
                val locale = Locale.getDefault()
                val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
                SimpleDateFormat(pattern, locale).format(Date(epochMs))
            }
        }
    }

    fun compactCount(value: Long): String = when {
        value <= 0L -> ""
        value < 1_000L -> value.toString()
        value < 1_000_000L -> {
            val k = value / 1_000.0
            if (k >= 100) "${k.toInt()}K" else String.format(Locale.US, "%.1fK", k).removeSuffix(".0K").let {
                if (it.endsWith("K")) it else "${it}K"
            }
        }
        else -> {
            val m = value / 1_000_000.0
            String.format(Locale.US, "%.1fM", m).replace(".0M", "M")
        }
    }

    /**
     * Upgrades `pbs.twimg.com/media/` URLs to a target quality variant (e.g. `large` or `orig`).
     */
    fun upgradeXPhotoUrl(url: String, sizeName: String = "large"): String {
        if (!url.contains("pbs.twimg.com/media/")) return url
        return if (url.contains("name=")) {
            url.replace(MEDIA_NAME_PARAM_REGEX, "name=$sizeName")
        } else if (url.contains("?")) {
            "$url&name=$sizeName"
        } else {
            "$url?name=$sizeName"
        }
    }

    /**
     * Extracts structured `@mention`, `#hashtag`, and URL spans from [text], cleanly trimming
     * trailing sentence punctuation from matched URLs.
     */
    fun extractTextEntities(text: String): List<TextEntitySpan> {
        if (text.isBlank()) return emptyList()
        val spans = ArrayList<TextEntitySpan>()
        ENTITY_REGEX.findAll(text).forEach { match ->
            val start = match.range.first
            var endExclusive = match.range.last + 1
            var token = match.value
            val kind = when {
                token.startsWith("@") -> EntityKind.MENTION
                token.startsWith("#") -> EntityKind.HASHTAG
                else -> EntityKind.URL
            }
            if (kind == EntityKind.URL) {
                while (token.isNotEmpty() && token.last() in TRAILING_URL_PUNCTUATION) {
                    // Preserve closing parenthesis if URL itself contains an opening parenthesis (e.g. Wikipedia)
                    if (token.last() == ')' && token.count { it == '(' } >= token.count { it == ')' }) {
                        break
                    }
                    token = token.dropLast(1)
                    endExclusive--
                }
                if (token.length < 4 || !token.contains('.')) return@forEach
            }
            val value = when (kind) {
                EntityKind.MENTION -> token.removePrefix("@")
                EntityKind.HASHTAG -> token
                EntityKind.URL -> normalizeExternalUrl(token)
            }
            spans.add(
                TextEntitySpan(
                    kind = kind,
                    rawToken = token,
                    value = value,
                    start = start,
                    endExclusive = endExclusive,
                )
            )
        }
        return spans
    }

    fun normalizeExternalUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun formatDisplayUrl(rawUrl: String): String {
        return rawUrl.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    /**
     * Static entity highlighter (used for offscreen screenshots and non-interactive previews).
     */
    fun highlightTweetEntities(text: String, accentColor: Color): AnnotatedString = buildAnnotatedString {
        append(text)
        extractTextEntities(text).forEach { span ->
            addStyle(
                style = SpanStyle(color = accentColor, fontWeight = FontWeight.Medium),
                start = span.start,
                end = span.endExclusive,
            )
            addStringAnnotation(
                tag = span.kind.name,
                annotation = span.rawToken,
                start = span.start,
                end = span.endExclusive,
            )
        }
    }

    /**
     * Builds an interactive [AnnotatedString] where `@mentions`, `#hashtags`, and URLs use
     * Material 3 / Compose [LinkAnnotation.Clickable] spans so tapping an entity routes directly
     * without intercepting taps on surrounding plain text.
     */
    fun buildInteractiveEntityText(
        text: String,
        accentColor: Color,
        onMentionClick: (String) -> Unit,
        onHashtagClick: (String) -> Unit,
        onUrlClick: (String) -> Unit,
    ): AnnotatedString = buildAnnotatedString {
        append(text)
        val linkStyles = TextLinkStyles(
            style = SpanStyle(color = accentColor, fontWeight = FontWeight.Medium),
            pressedStyle = SpanStyle(
                color = accentColor,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
            ),
        )
        extractTextEntities(text).forEach { span ->
            val linkAnnotation = LinkAnnotation.Clickable(
                tag = "${span.kind.name}:${span.value}",
                styles = linkStyles,
                linkInteractionListener = {
                    when (span.kind) {
                        EntityKind.MENTION -> onMentionClick(span.value)
                        EntityKind.HASHTAG -> onHashtagClick(span.value)
                        EntityKind.URL -> onUrlClick(span.value)
                    }
                },
            )
            addLink(
                clickable = linkAnnotation,
                start = span.start,
                end = span.endExclusive,
            )
        }
    }
}
