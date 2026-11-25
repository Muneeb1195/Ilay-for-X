package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlin.math.hypot

/**
 * Typed in-app destinations resolved from tapped or external URLs, `@mentions`, and `#hashtags`.
 */
@Immutable
data class PendingDeepLink(
    val id: Long = System.nanoTime(),
    val url: String,
)

@Immutable
sealed interface InAppLinkDestination {
    @Immutable
    data object Home : InAppLinkDestination

    @Immutable
    data object Activity : InAppLinkDestination

    @Immutable
    data object Bookmarks : InAppLinkDestination

    @Immutable
    data class Profile(val handle: String) : InAppLinkDestination

    @Immutable
    data class HashtagSearch(val query: String) : InAppLinkDestination

    @Immutable
    data class Thread(val tweetId: String, val authorHandle: String) : InAppLinkDestination

    @Immutable
    data class Article(
        val articleId: String,
        val authorHandle: String,
        val rawUrl: String,
    ) : InAppLinkDestination

    @Immutable
    data class Compose(
        val prefillText: String = "",
        val replyToTweetId: String? = null,
    ) : InAppLinkDestination

    @Immutable
    data class WebPage(val url: String) : InAppLinkDestination
}

/**
 * Resolves tapped entities (`@mention`, `#hashtag`, or URL) and incoming Android deep-link
 * intents (`ACTION_VIEW` / `ACTION_SEND`) into corresponding in-app screens:
 * - Every X, Twitter, embed-proxy (`fxtwitter`, `vxtwitter`, `fixupx`, `fixvx`), or custom-scheme
 *   tweet link opens directly on the native Thread screen.
 * - Profiles, hashtags, searches, Home, Notifications, Bookmarks, and compose intents open their
 *   matching native screens.
 * - Any remaining unsupported X URL or external web URL opens cleanly in our in-app web viewer.
 */
object IlayLinkRouter {
    val X_HOSTS = setOf(
        "x.com",
        "www.x.com",
        "mobile.x.com",
        "twitter.com",
        "www.twitter.com",
        "mobile.twitter.com",
        "fxtwitter.com",
        "www.fxtwitter.com",
        "vxtwitter.com",
        "www.vxtwitter.com",
        "fixupx.com",
        "www.fixupx.com",
        "fixvx.com",
        "www.fixvx.com",
    )

    private val CUSTOM_X_SCHEMES = setOf("x", "twitter")

    private val RESERVED_X_PATHS = setOf(
        "home",
        "explore",
        "notifications",
        "messages",
        "settings",
        "compose",
        "i",
        "intent",
        "search",
        "hashtag",
        "login",
        "signup",
        "tos",
        "privacy",
        "share",
        "about",
        "help",
        "download",
        "jobs",
        "lists",
        "bookmarks",
        "communities",
        "grok",
        "premium",
        "spaces",
    )

    private val HANDLE_REGEX = Regex("^[A-Za-z0-9_]{1,15}$")
    private val TWEET_ID_REGEX = Regex("^\\d{5,25}$")

    fun isSupportedXHost(host: String?): Boolean =
        host?.lowercase()?.trim() in X_HOSTS

    fun isSupportedDeepLinkUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return false
        val uri = runCatching { trimmed.toUri() }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme in CUSTOM_X_SCHEMES) return true
        if (scheme == "http" || scheme == "https") {
            return isSupportedXHost(uri.host)
        }
        val normalizedUri = runCatching { FormatUtils.normalizeExternalUrl(trimmed).toUri() }.getOrNull()
        return isSupportedXHost(normalizedUri?.host)
    }

    /**
     * Extracts a supported X/Twitter/embed-proxy URL from an incoming Android [Intent]
     * (`ACTION_VIEW` or `ACTION_SEND` with `text/plain`). Returns `null` for launcher or
     * unrelated intents so normal app launch stays untouched.
     */
    fun extractSupportedDeepLinkUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val dataUrl = intent.dataString?.trim().orEmpty()
                dataUrl.takeIf { isSupportedDeepLinkUrl(it) }
            }
            Intent.ACTION_SEND -> {
                val sharedText = listOfNotNull(
                    intent.getStringExtra(Intent.EXTRA_TEXT),
                    intent.getStringExtra(Intent.EXTRA_SUBJECT),
                ).joinToString(" ").trim()
                if (sharedText.isBlank()) return null

                // Prefer the first X/Twitter/embed-proxy URL embedded in the shared text
                val xUrl = FormatUtils.extractTextEntities(sharedText)
                    .firstOrNull { it.kind == EntityKind.URL && isSupportedDeepLinkUrl(it.value) }
                    ?.value
                if (xUrl != null) return xUrl

                // If shared directly without an X URL, open Composer with the shared text
                "x://post?text=${android.net.Uri.encode(sharedText)}"
            }
            else -> null
        }
    }

    fun resolveEntity(span: TextEntitySpan): InAppLinkDestination = when (span.kind) {
        EntityKind.MENTION -> InAppLinkDestination.Profile(span.value.removePrefix("@"))
        EntityKind.HASHTAG -> InAppLinkDestination.HashtagSearch(span.value)
        EntityKind.URL -> resolveUrl(span.value)
    }

    fun resolveUrl(rawUrl: String): InAppLinkDestination {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return InAppLinkDestination.WebPage(rawUrl)

        val directUri = runCatching { trimmed.toUri() }.getOrNull()
        val directScheme = directUri?.scheme?.lowercase().orEmpty()
        if (directUri != null && directScheme in CUSTOM_X_SCHEMES) {
            return resolveCustomSchemeUri(directUri)
        }

        val normalized = FormatUtils.normalizeExternalUrl(trimmed)
        val uri = runCatching { normalized.toUri() }.getOrNull()
            ?: return InAppLinkDestination.WebPage(normalized)
        val host = uri.host?.lowercase().orEmpty()
        if (host !in X_HOSTS) {
            return InAppLinkDestination.WebPage(normalized)
        }

        val segments = uri.pathSegments.orEmpty().filter { it.isNotBlank() }
        val firstLower = segments.firstOrNull()?.lowercase().orEmpty()

        // 1. Prioritize EVERY tweet/status link variant:
        //    /<handle>/status/<id>, /<handle>/statuses/<id>, /i/web/status/<id>, /i/status/<id>,
        //    plus trailing /photo/1, /video/1, /analytics, /quotes, /retweets
        val statusIdx = segments.indexOfFirst {
            it.equals("status", ignoreCase = true) || it.equals("statuses", ignoreCase = true)
        }
        if (statusIdx >= 0 && statusIdx + 1 < segments.size) {
            val candidateId = segments[statusIdx + 1]
            if (TWEET_ID_REGEX.matches(candidateId)) {
                val handleCandidate = segments.firstOrNull()?.takeIf {
                    !it.equals("i", ignoreCase = true) &&
                        !it.equals("web", ignoreCase = true) &&
                        HANDLE_REGEX.matches(it)
                }.orEmpty()
                return InAppLinkDestination.Thread(tweetId = candidateId, authorHandle = handleCandidate)
            }
        }

        // 1b. Native X Articles: /<handle>/article/<id> or /i/article/<id>
        val articleIdx = segments.indexOfFirst { it.equals("article", ignoreCase = true) }
        if (articleIdx >= 0 && articleIdx + 1 < segments.size) {
            val candidateArticleId = segments[articleIdx + 1].trim()
            if (candidateArticleId.isNotBlank()) {
                val handleCandidate = segments.firstOrNull()?.takeIf {
                    !it.equals("i", ignoreCase = true) &&
                        !it.equals("web", ignoreCase = true) &&
                        HANDLE_REGEX.matches(it)
                }.orEmpty()
                return InAppLinkDestination.Article(
                    articleId = candidateArticleId,
                    authorHandle = handleCandidate,
                    rawUrl = normalized,
                )
            }
        }

        // 2. X web intent links (like/retweet/tweet with tweet_id or in_reply_to)
        if (firstLower == "intent") {
            val intentAction = segments.getOrNull(1)?.lowercase().orEmpty()
            val tweetIdParam = uri.getQueryParameter("tweet_id")?.trim()
                ?: uri.getQueryParameter("in_reply_to")?.trim()
            if (intentAction in setOf("like", "retweet", "favorite") &&
                tweetIdParam != null && TWEET_ID_REGEX.matches(tweetIdParam)
            ) {
                return InAppLinkDestination.Thread(tweetId = tweetIdParam, authorHandle = "")
            }
            if (intentAction == "tweet" || intentAction == "post") {
                val prefill = buildString {
                    val t = uri.getQueryParameter("text")?.trim().orEmpty()
                    val u = uri.getQueryParameter("url")?.trim().orEmpty()
                    if (t.isNotBlank()) append(t)
                    if (u.isNotBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(u)
                    }
                }
                val replyId = tweetIdParam?.takeIf { TWEET_ID_REGEX.matches(it) }
                if (replyId != null && prefill.isBlank()) {
                    return InAppLinkDestination.Thread(tweetId = replyId, authorHandle = "")
                }
                return InAppLinkDestination.Compose(prefillText = prefill, replyToTweetId = replyId)
            }
            if (intentAction == "user" || intentAction == "follow") {
                val screenName = uri.getQueryParameter("screen_name")?.trim()?.removePrefix("@").orEmpty()
                if (HANDLE_REGEX.matches(screenName)) {
                    return InAppLinkDestination.Profile(screenName)
                }
            }
        }

        // 3. Compose paths
        if (firstLower == "compose") {
            val prefill = uri.getQueryParameter("text")?.trim().orEmpty()
            return InAppLinkDestination.Compose(prefillText = prefill)
        }

        // 4. Native top-level X destinations
        if (segments.isEmpty() || firstLower == "home") {
            return InAppLinkDestination.Home
        }
        if (firstLower == "notifications") {
            return InAppLinkDestination.Activity
        }
        if (firstLower == "bookmarks" || (firstLower == "i" && segments.getOrNull(1).equals("bookmarks", ignoreCase = true))) {
            return InAppLinkDestination.Bookmarks
        }

        // 5. Hashtag links: x.com/hashtag/<tag>
        if (segments.size >= 2 && firstLower == "hashtag") {
            val tag = segments[1].removePrefix("#").trim()
            if (tag.isNotBlank()) {
                return InAppLinkDestination.HashtagSearch("#$tag")
            }
        }

        // 6. Search links: x.com/search?q=<query>
        if (firstLower == "search") {
            val q = uri.getQueryParameter("q")?.trim().orEmpty()
            if (q.isNotBlank()) {
                return InAppLinkDestination.HashtagSearch(q)
            }
        }

        // 7. Profile links: x.com/<handle> (including /with_replies, /media, /likes, /highlights, /articles)
        val firstSeg = segments.firstOrNull()?.removePrefix("@").orEmpty()
        val secondSeg = segments.getOrNull(1)?.lowercase()
        val isProfileSubTab = secondSeg == null || secondSeg in setOf("with_replies", "media", "likes", "highlights", "articles")
        if (firstSeg.isNotBlank() &&
            firstSeg.lowercase() !in RESERVED_X_PATHS &&
            HANDLE_REGEX.matches(firstSeg) &&
            isProfileSubTab
        ) {
            return InAppLinkDestination.Profile(firstSeg)
        }

        return InAppLinkDestination.WebPage(normalized)
    }

    private fun resolveCustomSchemeUri(uri: android.net.Uri): InAppLinkDestination {
        val host = uri.host?.lowercase().orEmpty()
        val segments = uri.pathSegments.orEmpty().filter { it.isNotBlank() }

        return when (host) {
            "status", "statuses", "tweet" -> {
                val id = uri.getQueryParameter("status_id")
                    ?: uri.getQueryParameter("id")
                    ?: segments.firstOrNull { TWEET_ID_REGEX.matches(it) }
                if (id != null && TWEET_ID_REGEX.matches(id.trim())) {
                    val handle = uri.getQueryParameter("screen_name")?.removePrefix("@").orEmpty()
                    InAppLinkDestination.Thread(tweetId = id.trim(), authorHandle = handle)
                } else {
                    InAppLinkDestination.Home
                }
            }
            "user", "profile" -> {
                val handle = (uri.getQueryParameter("screen_name")
                    ?: uri.getQueryParameter("handle")
                    ?: segments.firstOrNull())?.trim()?.removePrefix("@").orEmpty()
                if (HANDLE_REGEX.matches(handle)) InAppLinkDestination.Profile(handle) else InAppLinkDestination.Home
            }
            "search" -> {
                val q = (uri.getQueryParameter("query") ?: uri.getQueryParameter("q")).orEmpty().trim()
                if (q.isNotBlank()) InAppLinkDestination.HashtagSearch(q) else InAppLinkDestination.Home
            }
            "hashtag" -> {
                val tag = (uri.getQueryParameter("tag") ?: segments.firstOrNull()).orEmpty().trim().removePrefix("#")
                if (tag.isNotBlank()) InAppLinkDestination.HashtagSearch("#$tag") else InAppLinkDestination.Home
            }
            "notifications", "mentions", "activity" -> InAppLinkDestination.Activity
            "bookmarks" -> InAppLinkDestination.Bookmarks
            "post", "compose" -> {
                val text = (uri.getQueryParameter("message") ?: uri.getQueryParameter("text")).orEmpty().trim()
                InAppLinkDestination.Compose(prefillText = text)
            }
            "timeline", "home", "" -> InAppLinkDestination.Home
            else -> {
                // Handle x://<handle>/status/<id> or x://<handle>
                val statusIdx = segments.indexOfFirst { it.equals("status", ignoreCase = true) }
                if (statusIdx >= 0 && statusIdx + 1 < segments.size && TWEET_ID_REGEX.matches(segments[statusIdx + 1])) {
                    InAppLinkDestination.Thread(
                        tweetId = segments[statusIdx + 1],
                        authorHandle = host.takeIf { HANDLE_REGEX.matches(it) }.orEmpty(),
                    )
                } else if (host !in RESERVED_X_PATHS && HANDLE_REGEX.matches(host)) {
                    InAppLinkDestination.Profile(host)
                } else {
                    InAppLinkDestination.Home
                }
            }
        }
    }
}

/**
 * Custom Jetpack Compose LinkMovementMethod equivalent modeled after Android's `LinkMovementMethod`.
 *
 * Performs line-bounded character-offset hit testing against a [TextLayoutResult] with mobile
 * touch-slop forgiveness so tapping compact `@mentions`, `#hashtags`, and URLs reliably intercepts
 * the gesture before parent card `clickable` modifiers fire, while leaving taps on plain text
 * completely unconsumed so parent tweet/profile cards remain tappable.
 */
class IlayLinkMovementMethod(
    val spans: List<TextEntitySpan>,
) {
    fun findEntityAtOffset(charOffset: Int): TextEntitySpan? {
        if (spans.isEmpty() || charOffset < 0) return null
        return spans.firstOrNull { charOffset in it.start until it.endExclusive }
    }

    fun findEntityAtPosition(
        layoutResult: TextLayoutResult,
        position: Offset,
        slopPx: Float,
    ): TextEntitySpan? {
        if (spans.isEmpty()) return null

        // Direct hit first (strictly inside line bounds so empty trailing line space does not false-match)
        hitTestPoint(layoutResult, position, tolerancePx = 2f)?.let { return it }

        if (slopPx <= 0f) return null

        // Small forgiveness cross around the touch point for compact mentions/short links
        val probes = arrayOf(
            Offset(position.x - slopPx, position.y),
            Offset(position.x + slopPx, position.y),
            Offset(position.x, position.y - slopPx * 0.75f),
            Offset(position.x, position.y + slopPx * 0.75f),
        )
        for (probe in probes) {
            hitTestPoint(layoutResult, probe, tolerancePx = slopPx)?.let { return it }
        }
        return null
    }

    private fun hitTestPoint(
        layoutResult: TextLayoutResult,
        point: Offset,
        tolerancePx: Float,
    ): TextEntitySpan? {
        val lineCount = layoutResult.lineCount
        if (lineCount <= 0) return null
        if (point.y < -tolerancePx || point.y > layoutResult.size.height + tolerancePx) return null

        val clampedY = point.y.coerceIn(0f, (layoutResult.size.height - 1).coerceAtLeast(0).toFloat())
        val line = layoutResult.getLineForVerticalPosition(clampedY)
        val lineLeft = layoutResult.getLineLeft(line)
        val lineRight = layoutResult.getLineRight(line)
        val lineTop = layoutResult.getLineTop(line)
        val lineBottom = layoutResult.getLineBottom(line)

        if (point.x < lineLeft - tolerancePx || point.x > lineRight + tolerancePx) return null
        if (point.y < lineTop - tolerancePx || point.y > lineBottom + tolerancePx) return null

        val clampedPoint = Offset(
            x = point.x.coerceIn(lineLeft, lineRight),
            y = point.y.coerceIn(lineTop, lineBottom),
        )
        val offset = layoutResult.getOffsetForPosition(clampedPoint)
        return findEntityAtOffset(offset)
            ?: if (offset > 0) findEntityAtOffset(offset - 1) else null
    }
}

/**
 * Custom LinkMovementMethod-backed [Text] composable for all bio and tweet card text nodes.
 *
 * - Intercepts taps on URLs, `@mentions`, and `#hashtags` via [IlayLinkMovementMethod] so parent
 *   clickable cards do NOT also trigger when an entity is tapped.
 * - Leaves taps on surrounding plain text completely unconsumed so tapping a tweet card opens the
 *   thread and tapping a user search card opens the profile.
 * - Highlights the pressed entity during touch-down and exposes TalkBack custom accessibility
 *   actions for every detected entity.
 */
@Composable
fun IlayLinkMovementText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onMentionClick: ((String) -> Unit)? = null,
    onHashtagClick: ((String) -> Unit)? = null,
    onUrlClick: ((String) -> Unit)? = null,
) {
    val host = LocalTweetCardHost.current
    val resolvedMentionClick = onMentionClick ?: host.openProfile
    val resolvedHashtagClick = onHashtagClick ?: host.openHashtag
    val resolvedUrlClick = onUrlClick ?: host.openUrl

    val entities = remember(text) { FormatUtils.extractTextEntities(text) }
    val movementMethod = remember(entities) { IlayLinkMovementMethod(entities) }

    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var pressedEntity by remember(text) { mutableStateOf<TextEntitySpan?>(null) }

    val slopPx = with(LocalDensity.current) { 6.dp.toPx() }
    val pressedBg = accentColor.copy(alpha = 0.16f)

    val dispatchEntity: (TextEntitySpan) -> Unit = remember(
        resolvedMentionClick,
        resolvedHashtagClick,
        resolvedUrlClick,
    ) {
        { span ->
            when (val dest = IlayLinkRouter.resolveEntity(span)) {
                is InAppLinkDestination.Profile -> resolvedMentionClick(dest.handle)
                is InAppLinkDestination.HashtagSearch -> resolvedHashtagClick(dest.query)
                is InAppLinkDestination.WebPage -> resolvedUrlClick(dest.url)
                is InAppLinkDestination.Article -> resolvedUrlClick(dest.rawUrl)
                is InAppLinkDestination.Thread,
                is InAppLinkDestination.Home,
                is InAppLinkDestination.Activity,
                is InAppLinkDestination.Bookmarks,
                is InAppLinkDestination.Compose -> resolvedUrlClick(span.value)
            }
        }
    }

    val annotatedText: AnnotatedString = remember(text, entities, accentColor, pressedEntity, pressedBg) {
        buildAnnotatedString {
            append(text)
            entities.forEach { span ->
                val isPressed = pressedEntity == span
                addStyle(
                    style = SpanStyle(
                        color = accentColor,
                        fontWeight = if (isPressed) FontWeight.SemiBold else FontWeight.Medium,
                        textDecoration = if (isPressed) TextDecoration.Underline else TextDecoration.None,
                        background = if (isPressed) pressedBg else Color.Unspecified,
                    ),
                    start = span.start,
                    end = span.endExclusive,
                )
                addStringAnnotation(
                    tag = span.kind.name,
                    annotation = span.value,
                    start = span.start,
                    end = span.endExclusive,
                )
            }
        }
    }

    val accessibilityActions = remember(entities, dispatchEntity) {
        entities.distinctBy { it.kind to it.value }.map { span ->
            val label = when (span.kind) {
                EntityKind.MENTION -> "Open profile @${span.value}"
                EntityKind.HASHTAG -> "Search ${span.value}"
                EntityKind.URL -> "Open link ${FormatUtils.formatDisplayUrl(span.value)}"
            }
            CustomAccessibilityAction(label = label) {
                dispatchEntity(span)
                true
            }
        }
    }

    val pointerModifier = if (entities.isEmpty()) {
        Modifier
    } else {
        Modifier
            .semantics {
                if (accessibilityActions.isNotEmpty()) {
                    customActions = accessibilityActions
                }
            }
            .pointerInput(movementMethod, slopPx, dispatchEntity) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val currentLayout = layoutResult ?: return@awaitEachGesture
                    val hitSpan = movementMethod.findEntityAtPosition(
                        layoutResult = currentLayout,
                        position = down.position,
                        slopPx = slopPx,
                    ) ?: return@awaitEachGesture

                    pressedEntity = hitSpan
                    try {
                        val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                        if (up != null) {
                            val moveDistance = hypot(
                                (up.position.x - down.position.x).toDouble(),
                                (up.position.y - down.position.y).toDouble(),
                            ).toFloat()
                            if (moveDistance <= touchSlop) {
                                // Confirm pointer remained on or near the entity at lift-off
                                val upHit = movementMethod.findEntityAtPosition(
                                    layoutResult = currentLayout,
                                    position = up.position,
                                    slopPx = slopPx * 1.5f,
                                )
                                if (upHit == hitSpan) {
                                    // Consume up event in Initial pass so parent Surface.clickable never fires
                                    up.consume()
                                    dispatchEntity(hitSpan)
                                }
                            }
                        }
                    } finally {
                        pressedEntity = null
                    }
                }
            }
    }

    Text(
        text = annotatedText,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layoutResult = it },
        modifier = modifier.then(pointerModifier),
    )
}
