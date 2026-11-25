package com.example.data.remote

import android.util.Base64
import com.example.domain.model.LinkPreview
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.QuotedTweet
import com.example.domain.model.ThreadConversation
import com.example.domain.model.TimelinePage
import com.example.domain.model.Tweet
import com.example.domain.model.TweetMetrics
import com.example.domain.model.XUser
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Zero-dependency pure-Kotlin HTML parser for Nitter instances (defaulting to
 * `https://nitter.netbub.com`). Extracts profiles, timelines (posts, replies, media),
 * full thread conversations (ancestors + focal post + replies), and paginated search results.
 */
object NitterHtmlParser {

    const val DEFAULT_NITTER_BASE_URL = "https://nitter.netbub.com"
    const val DEFAULT_NITTER_HOST = "nitter.netbub.com"

    private val STATUS_LINK_REGEX = Regex("""href="[^"]*/([A-Za-z0-9_]+)/status/(\d+)(?:[^"]*)"""")
    private val CURSOR_HREF_REGEX = Regex("""<div class="show-more"[^>]*>.*?href="([^"]*cursor=([^"&]+)[^"]*)"""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG_REGEX = Regex("<[^>]+>")
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val P_END_REGEX = Regex("</p>", RegexOption.IGNORE_CASE)
    private val WHITESPACE_MULTI_REGEX = Regex("[ \\t\\x0B\\f\\r]+")
    private val NEWLINE_MULTI_REGEX = Regex("\\n{3,}")
    private val HREF_REGEX = Regex("""href="([^"]+)"""")
    private val SRC_REGEX = Regex("""src="([^"]+)"""")
    private val DATA_URL_REGEX = Regex("""data-url="([^"]+)"""")
    private val POSTER_REGEX = Regex("""poster="([^"]+)"""")
    private val TITLE_ATTR_REGEX = Regex("""title="([^"]+)"""")
    private val ALT_ATTR_REGEX = Regex("""alt="([^"]*)"""")
    private val STAT_BLOCK_REGEX = Regex("""<span class="tweet-stat"[^>]*>.*?icon-(comment|retweet|quote|heart)\b[^>]*>(?:</span>|</i>)\s*([^<]*)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val PROFILE_STAT_REGEX = Regex("""<li class="(posts|following|followers|likes)"[^>]*>.*?<span class="profile-stat-num"[^>]*>([^<]+)</span>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HANDLE_REGEX = Regex("""@([A-Za-z0-9_]{1,20})""")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(\\d+);")
    private val HEX_ENTITY_REGEX = Regex("&#x([0-9a-fA-F]+);")

    private val DATE_FORMATS = arrayOf(
        "MMM d, yyyy · h:mm a z",
        "MMM dd, yyyy · h:mm a z",
        "MMM d, yyyy · HH:mm z",
        "d MMM yyyy, HH:mm:ss z",
        "MMM d, yyyy, h:mm a z",
    )

    private val PBS_PREFIXES = arrayOf(
        "media/",
        "profile_images/",
        "profile_banners/",
        "ext_tw_video_thumb/",
        "amplify_video_thumb/",
        "tweet_video_thumb/",
        "card_img/",
        "semantic_core_img/",
        "sticky/",
    )

    fun normalizeBaseUrl(customProxyBaseUrl: String): String {
        val raw = customProxyBaseUrl.trim().trimEnd('/')
        if (raw.isBlank()) return DEFAULT_NITTER_BASE_URL
        return if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
    }

    fun displayHost(customProxyBaseUrl: String): String {
        val normalized = normalizeBaseUrl(customProxyBaseUrl)
        return runCatching { URI(normalized).host?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifBlank { DEFAULT_NITTER_HOST }
    }

    fun isChallengeOrErrorHtml(html: String): Boolean {
        if (html.isBlank()) return true
        val lower = html.take(4000).lowercase(Locale.US)
        return lower.contains("just a moment...") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("anubis") && lower.contains("proof of work") ||
            html.contains("""class="error-panel"""")
    }

    /**
     * Converts Nitter `/pic/...` and `/video/...` proxy paths into canonical direct X CDN URLs
     * whenever possible (for fast Coil caching & adaptive variants), falling back to full
     * Nitter-hosted URLs for unrecognized relative paths.
     */
    fun resolveMediaUrl(rawPath: String, baseUrl: String): String {
        val cleaned = unescapeHtml(rawPath).trim()
        if (cleaned.isBlank()) return ""
        if (cleaned.startsWith("https://", ignoreCase = true) || cleaned.startsWith("http://", ignoreCase = true)) {
            return cleaned
        }
        val normalizedBase = normalizeBaseUrl(baseUrl)

        if (cleaned.startsWith("/pic/")) {
            val remainder = cleaned.removePrefix("/pic/").removePrefix("orig/")
            val decoded = if (remainder.startsWith("enc/")) {
                decodeBase64Payload(remainder.removePrefix("enc/")) ?: urlDecode(remainder)
            } else {
                urlDecode(remainder)
            }.trimStart('/')

            if (decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith("http://", ignoreCase = true)) {
                return decoded
            }
            if (decoded.startsWith("pbs.twimg.com/") || decoded.startsWith("video.twimg.com/")) {
                return "https://$decoded"
            }
            if (PBS_PREFIXES.any { decoded.startsWith(it) }) {
                return "https://pbs.twimg.com/$decoded"
            }
            return "$normalizedBase$cleaned"
        }

        if (cleaned.startsWith("/video/")) {
            val decoded = urlDecode(cleaned)
            val httpsIdx = decoded.indexOf("https://")
            if (httpsIdx >= 0) {
                return decoded.substring(httpsIdx)
            }
            return "$normalizedBase$cleaned"
        }

        return if (cleaned.startsWith("/")) "$normalizedBase$cleaned" else "$normalizedBase/$cleaned"
    }

    fun parseProfile(
        html: String,
        fallbackHandle: String,
        baseUrl: String = DEFAULT_NITTER_BASE_URL,
    ): XUser? {
        if (isChallengeOrErrorHtml(html)) return null
        val cardIdx = html.indexOf("""class="profile-card"""")
        if (cardIdx < 0) return null
        val cardEnd = html.indexOf("""class="timeline""", cardIdx).takeIf { it > cardIdx }
            ?: (cardIdx + 12_000).coerceAtMost(html.length)
        val profileRegion = html.substring((cardIdx - 2_000).coerceAtLeast(0), cardEnd)

        val rawFullName = extractFirstElementText(profileRegion, "profile-card-fullname")
        val rawUsername = extractFirstElementText(profileRegion, "profile-card-username")
            .removePrefix("@")
            .ifBlank { fallbackHandle.trim().removePrefix("@") }
        if (rawUsername.isBlank()) return null

        val avatarBlock = extractTagBlock(profileRegion, "profile-card-avatar")
        val rawAvatar = (HREF_REGEX.find(avatarBlock)?.groupValues?.getOrNull(1)
            ?: SRC_REGEX.find(avatarBlock)?.groupValues?.getOrNull(1)).orEmpty()
        val avatarUrl = resolveMediaUrl(rawAvatar, baseUrl)
            .replace("_normal.", "_400x400.")
            .replace("_bigger.", "_400x400.")

        val bannerBlock = extractTagBlock(profileRegion, "profile-banner")
        val rawBanner = (HREF_REGEX.find(bannerBlock)?.groupValues?.getOrNull(1)
            ?: SRC_REGEX.find(bannerBlock)?.groupValues?.getOrNull(1)).orEmpty()
        val bannerUrl = resolveMediaUrl(rawBanner, baseUrl)

        val bioHtml = extractInnerHtml(profileRegion, "profile-bio")
        val bio = cleanRichText(bioHtml)
        val location = extractFirstElementText(profileRegion, "profile-location")
        val websiteBlock = extractTagBlock(profileRegion, "profile-website")
        val websiteUrl = HREF_REGEX.find(websiteBlock)?.groupValues?.getOrNull(1)?.let(::unescapeHtml).orEmpty()
        val joinBlock = extractTagBlock(profileRegion, "profile-joindate")
        val joinedLabel = extractTextFromHtml(joinBlock)
            .ifBlank { TITLE_ATTR_REGEX.find(joinBlock)?.groupValues?.getOrNull(1).orEmpty() }

        val isVerified = profileRegion.contains("verified-icon")

        var tweetsCount = 0L
        var followingCount = 0L
        var followersCount = 0L
        PROFILE_STAT_REGEX.findAll(profileRegion).forEach { m ->
            val key = m.groupValues[1].lowercase(Locale.US)
            val count = parseCount(m.groupValues[2])
            when (key) {
                "posts" -> tweetsCount = count
                "following" -> followingCount = count
                "followers" -> followersCount = count
            }
        }

        return XUser(
            id = rawUsername.lowercase(Locale.US),
            handle = rawUsername,
            displayName = rawFullName.ifBlank { rawUsername },
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            bio = bio,
            location = location,
            websiteUrl = websiteUrl,
            isVerified = isVerified,
            followersCount = followersCount,
            followingCount = followingCount,
            tweetsCount = tweetsCount,
            joinedLabel = joinedLabel,
        )
    }

    fun parseTimelinePage(
        html: String,
        baseUrl: String = DEFAULT_NITTER_BASE_URL,
        sourceInfo: String = "Nitter (${displayHost(baseUrl)})",
    ): TimelinePage {
        if (isChallengeOrErrorHtml(html)) {
            return TimelinePage(emptyList(), isFromPublicFallback = true, sourceInfo = sourceInfo)
        }
        val items = splitTimelineItemBlocks(html)
        val tweets = items.mapNotNull { block ->
            parseTweetCard(block, baseUrl, sourceInfo)
        }.distinctBy { it.id }

        val nextCursor = extractNextCursor(html)
        return TimelinePage(
            tweets = tweets,
            nextCursor = nextCursor,
            isFromPublicFallback = true,
            sourceInfo = sourceInfo,
        )
    }

    fun parseThreadConversation(
        html: String,
        focalTweetId: String,
        baseUrl: String = DEFAULT_NITTER_BASE_URL,
    ): ThreadConversation? {
        if (isChallengeOrErrorHtml(html)) return null
        val sourceInfo = "Nitter (${displayHost(baseUrl)})"

        val mainIdx = html.indexOf("""id="m"""")
            .takeIf { it >= 0 }
            ?: html.indexOf("""class="main-tweet"""")
        if (mainIdx < 0) {
            val fallbackPage = parseTimelinePage(html, baseUrl, sourceInfo)
            val match = fallbackPage.tweets.firstOrNull { it.id == focalTweetId }
                ?: fallbackPage.tweets.firstOrNull()
                ?: return null
            val idx = fallbackPage.tweets.indexOfFirst { it.id == match.id }
            return ThreadConversation(
                ancestors = if (idx > 0) fallbackPage.tweets.subList(0, idx) else emptyList(),
                focalTweet = match,
                replies = if (idx >= 0) fallbackPage.tweets.drop(idx + 1) else emptyList(),
                bottomCursor = fallbackPage.nextCursor,
            )
        }

        val repliesIdx = html.indexOf("""class="replies""", mainIdx)
        val beforeHtml = html.substring(0, mainIdx)
        val mainHtml = if (repliesIdx > mainIdx) {
            html.substring(mainIdx, repliesIdx)
        } else {
            html.substring(mainIdx)
        }
        val repliesHtml = if (repliesIdx > mainIdx) html.substring(repliesIdx) else ""

        val ancestors = splitTimelineItemBlocks(beforeHtml)
            .mapNotNull { parseTweetCard(it, baseUrl, sourceInfo) }
            .filterNot { it.id == focalTweetId }
            .distinctBy { it.id }

        val focalTweet = parseTweetCard(mainHtml, baseUrl, sourceInfo, fallbackTweetId = focalTweetId)
            ?: return null

        val replies = splitTimelineItemBlocks(repliesHtml)
            .mapNotNull { parseTweetCard(it, baseUrl, sourceInfo) }
            .filterNot { it.id == focalTweet.id }
            .distinctBy { it.id }

        return ThreadConversation(
            ancestors = ancestors,
            focalTweet = focalTweet,
            replies = replies,
            bottomCursor = extractNextCursor(repliesHtml),
        )
    }

    fun parseUsersSearch(
        html: String,
        baseUrl: String = DEFAULT_NITTER_BASE_URL,
    ): List<XUser> {
        if (isChallengeOrErrorHtml(html)) return emptyList()
        val blocks = splitTimelineItemBlocks(html)
        return blocks.mapNotNull { block ->
            val username = extractFirstElementText(block, "username").removePrefix("@").trim()
            if (username.isBlank()) return@mapNotNull null
            val fullName = extractFirstElementText(block, "fullname").ifBlank { username }
            val avatarBlock = extractTagBlock(block, "tweet-avatar")
            val rawAvatar = SRC_REGEX.find(avatarBlock)?.groupValues?.getOrNull(1).orEmpty()
            val avatarUrl = resolveMediaUrl(rawAvatar, baseUrl)
                .replace("_normal.", "_400x400.")
                .replace("_bigger.", "_400x400.")
            val bio = cleanRichText(extractInnerHtml(block, "tweet-content"))
            XUser(
                id = username.lowercase(Locale.US),
                handle = username,
                displayName = fullName,
                avatarUrl = avatarUrl,
                bio = bio,
                isVerified = block.contains("verified-icon"),
            )
        }.distinctBy { it.handle.lowercase(Locale.US) }
    }

    private fun parseTweetCard(
        block: String,
        baseUrl: String,
        sourceInfo: String,
        fallbackTweetId: String = "",
    ): Tweet? {
        if (block.contains("""class="unavailable""") || block.contains("""class="show-more""")) {
            return null
        }

        // Split off embedded quote card so its author/media/text don't collide with outer tweet
        val quoteStart = block.indexOf("""<div class="quote""")
        val outerPart = if (quoteStart > 0) block.substring(0, quoteStart) else block
        val quotePart = if (quoteStart > 0) {
            val statsIdx = block.indexOf("""<div class="tweet-stats""", quoteStart)
            val footerIdx = block.indexOf("""<div class="tweet-published""", quoteStart)
            val endIdx = listOf(statsIdx, footerIdx).filter { it > quoteStart }.minOrNull() ?: block.length
            block.substring(quoteStart, endIdx)
        } else ""

        // Extract stats from full block (appears after quote)
        val statsStart = block.lastIndexOf("""class="tweet-stats""")
        val statsPart = if (statsStart >= 0) block.substring(statsStart) else outerPart

        val statusMatch = STATUS_LINK_REGEX.find(extractTagBlock(outerPart, "tweet-date"))
            ?: STATUS_LINK_REGEX.find(outerPart)
        val tweetId = statusMatch?.groupValues?.getOrNull(2).orEmpty().ifBlank { fallbackTweetId }
        if (tweetId.isBlank()) return null

        val handle = extractFirstElementText(outerPart, "username")
            .removePrefix("@")
            .ifBlank { statusMatch?.groupValues?.getOrNull(1).orEmpty() }
        if (handle.isBlank()) return null

        val displayName = extractFirstElementText(outerPart, "fullname").ifBlank { handle }
        val avatarBlock = extractTagBlock(outerPart, "tweet-avatar")
        val rawAvatar = SRC_REGEX.find(avatarBlock)?.groupValues?.getOrNull(1).orEmpty()
        val avatarUrl = resolveMediaUrl(rawAvatar, baseUrl)
            .replace("_normal.", "_400x400.")
            .replace("_bigger.", "_400x400.")
        val headerPart = outerPart.substring(0, outerPart.indexOf("""class="tweet-content""").takeIf { it > 0 } ?: outerPart.length)
        val isVerified = headerPart.contains("verified-icon")

        val retweetHeader = extractFirstElementText(outerPart, "retweet-header")
            .replace(" retweeted", "", ignoreCase = true)
            .trim()
            .takeIf { it.isNotBlank() }

        val replyingToBlock = extractFirstElementText(outerPart, "replying-to")
        val replyToHandle = HANDLE_REGEX.find(replyingToBlock)?.groupValues?.getOrNull(1)

        val dateBlock = extractTagBlock(outerPart, "tweet-date")
            .ifBlank { extractTagBlock(block, "tweet-published") }
        val dateTitle = TITLE_ATTR_REGEX.find(dateBlock)?.groupValues?.getOrNull(1)
            ?.ifBlank { extractTextFromHtml(dateBlock) }
            .orEmpty()
        val createdAtMs = parseNitterDate(dateTitle)

        val text = cleanRichText(extractInnerHtml(outerPart, "tweet-content"))
        val media = parseAttachments(outerPart, tweetId, baseUrl)
        val linkPreview = parseCard(outerPart, baseUrl)
        val quotedTweet = if (quotePart.isNotBlank()) parseQuote(quotePart, baseUrl) else null

        var replies = 0L
        var reposts = 0L
        var quotes = 0L
        var likes = 0L
        STAT_BLOCK_REGEX.findAll(statsPart).forEach { m ->
            val icon = m.groupValues[1].lowercase(Locale.US)
            val count = parseCount(m.groupValues[2])
            when (icon) {
                "comment" -> replies = count
                "retweet" -> reposts = count
                "quote" -> quotes = count
                "heart" -> likes = count
            }
        }

        val author = XUser(
            id = handle.lowercase(Locale.US),
            handle = handle,
            displayName = displayName,
            avatarUrl = avatarUrl,
            isVerified = isVerified,
        )

        return Tweet(
            id = tweetId,
            conversationId = tweetId,
            author = author,
            text = text,
            createdAtEpochMs = createdAtMs,
            media = media,
            quotedTweet = quotedTweet,
            quotedTweetId = quotedTweet?.id,
            linkPreview = linkPreview,
            repostedBy = retweetHeader,
            replyToHandle = replyToHandle,
            metrics = TweetMetrics(
                replies = replies,
                reposts = reposts,
                quotes = quotes,
                likes = likes,
            ),
            sourceLabel = sourceInfo,
        )
    }

    private fun parseAttachments(html: String, tweetId: String, baseUrl: String): List<MediaAttachment> {
        val attachStart = html.indexOf("""class="attachments""")
        if (attachStart < 0) return emptyList()
        val cardIdx = html.indexOf("""class="card""", attachStart).takeIf { it > attachStart } ?: html.length
        val region = html.substring(attachStart, cardIdx)
        val media = mutableListOf<MediaAttachment>()

        // 1. Videos & GIFs
        val videoTagRegex = Regex("""<video\b[^>]*>.*?</video>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        videoTagRegex.findAll(region).forEachIndexed { idx, match ->
            val tag = match.value
            val isGif = tag.contains("class=\"gif\"", ignoreCase = true) || tag.contains("loop", ignoreCase = true)
            val poster = POSTER_REGEX.find(tag)?.groupValues?.getOrNull(1).orEmpty()
            val dataUrl = DATA_URL_REGEX.find(tag)?.groupValues?.getOrNull(1).orEmpty()
            val sourceSrc = Regex("""<source[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.getOrNull(1).orEmpty()
            val videoUrl = resolveMediaUrl(sourceSrc.ifBlank { dataUrl }, baseUrl).takeIf { it.isNotBlank() }
            val thumbUrl = resolveMediaUrl(poster, baseUrl)
            if (thumbUrl.isNotBlank() || videoUrl != null) {
                media.add(
                    MediaAttachment(
                        id = "$tweetId-v$idx",
                        type = if (isGif) MediaType.GIF else MediaType.VIDEO,
                        url = thumbUrl.ifBlank { videoUrl.orEmpty() },
                        thumbnailUrl = thumbUrl.ifBlank { videoUrl.orEmpty() },
                        videoUrl = videoUrl,
                    )
                )
            }
        }

        // 2. Photos (<a class="still-image" href="..."> <img src="..." alt="...">)
        val photoRegex = Regex("""<a[^>]*class="[^"]*still-image[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        photoRegex.findAll(region).forEachIndexed { idx, match ->
            val href = resolveMediaUrl(match.groupValues[1], baseUrl)
            val inner = match.groupValues[2]
            val imgSrc = SRC_REGEX.find(inner)?.groupValues?.getOrNull(1)?.let { resolveMediaUrl(it, baseUrl) }.orEmpty()
            val alt = ALT_ATTR_REGEX.find(inner)?.groupValues?.getOrNull(1)?.let(::unescapeHtml).orEmpty()
            val finalUrl = href.ifBlank { imgSrc }
            if (finalUrl.isNotBlank()) {
                media.add(
                    MediaAttachment(
                        id = "$tweetId-p$idx",
                        type = MediaType.PHOTO,
                        url = finalUrl,
                        thumbnailUrl = imgSrc.ifBlank { finalUrl },
                        altText = alt,
                    )
                )
            }
        }

        // 3. Video poster placeholders without <video> tag (e.g. HLS-disabled Nitter instances)
        if (media.isEmpty() && region.contains("gallery-video")) {
            val imgMatch = Regex("""<img[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE).find(region)
            val poster = imgMatch?.groupValues?.getOrNull(1)?.let { resolveMediaUrl(it, baseUrl) }.orEmpty()
            if (poster.isNotBlank()) {
                media.add(
                    MediaAttachment(
                        id = "$tweetId-vp0",
                        type = MediaType.VIDEO,
                        url = poster,
                        thumbnailUrl = poster,
                    )
                )
            }
        }

        return media.distinctBy { it.url }
    }

    private fun parseCard(html: String, baseUrl: String): LinkPreview? {
        val cardStart = html.indexOf("""class="card"""")
            .takeIf { it >= 0 }
            ?: html.indexOf("""class="card """)
        if (cardStart < 0) return null
        val region = html.substring(cardStart, (cardStart + 4_000).coerceAtMost(html.length))
        val url = HREF_REGEX.find(region)?.groupValues?.getOrNull(1)?.let(::unescapeHtml).orEmpty()
        if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) return null
        val title = extractFirstElementText(region, "card-title")
        val description = extractFirstElementText(region, "card-description")
        val destination = extractFirstElementText(region, "card-destination")
        val domain = destination.ifBlank {
            runCatching { URI(url).host?.removePrefix("www.").orEmpty() }.getOrDefault("")
        }
        val imageUrl = SRC_REGEX.find(region)?.groupValues?.getOrNull(1)
            ?.let { resolveMediaUrl(it, baseUrl) }
            .orEmpty()
        return LinkPreview(
            url = url,
            displayUrl = domain.ifBlank { url },
            title = title,
            description = description,
            imageUrl = imageUrl,
            domain = domain,
        )
    }

    private fun parseQuote(quoteHtml: String, baseUrl: String): QuotedTweet? {
        val statusMatch = STATUS_LINK_REGEX.find(quoteHtml)
        val qId = statusMatch?.groupValues?.getOrNull(2).orEmpty()
        val handle = extractFirstElementText(quoteHtml, "username")
            .removePrefix("@")
            .ifBlank { statusMatch?.groupValues?.getOrNull(1).orEmpty() }
        if (qId.isBlank() && handle.isBlank()) return null
        val fullName = extractFirstElementText(quoteHtml, "fullname").ifBlank { handle }
        val rawAvatar = SRC_REGEX.find(extractTagBlock(quoteHtml, "tweet-avatar"))
            ?.groupValues?.getOrNull(1).orEmpty()
        val avatarUrl = resolveMediaUrl(rawAvatar, baseUrl)
            .replace("_normal.", "_400x400.")
        val text = cleanRichText(extractInnerHtml(quoteHtml, "quote-text"))
        val media = parseAttachments(quoteHtml, qId.ifBlank { "q" }, baseUrl)
        val quoteDateBlock = extractTagBlock(quoteHtml, "tweet-date")
        val quoteDateTitle = TITLE_ATTR_REGEX.find(quoteDateBlock)?.groupValues?.getOrNull(1)
            ?.ifBlank { extractTextFromHtml(quoteDateBlock) }
            .orEmpty()
        return QuotedTweet(
            id = qId.ifBlank { "q_${text.hashCode()}" },
            conversationId = qId.ifBlank { "q_${text.hashCode()}" },
            author = XUser(
                id = handle.lowercase(Locale.US),
                handle = handle,
                displayName = fullName,
                avatarUrl = avatarUrl,
                isVerified = quoteHtml.contains("verified-icon"),
            ),
            text = text,
            createdAtEpochMs = parseNitterDate(quoteDateTitle),
            media = media,
        )
    }

    private fun splitTimelineItemBlocks(html: String): List<String> {
        val markerRegex = Regex("""<div class="timeline-item[^"]*">""", RegexOption.IGNORE_CASE)
        val starts = markerRegex.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        val blocks = mutableListOf<String>()
        for (i in starts.indices) {
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] else html.length
            val slice = html.substring(start, end)
            // Trim trailing footer/show-more container on last block
            val trimmed = slice.indexOf("""<div class="show-more""").takeIf { it > 0 }
                ?.let { slice.substring(0, it) }
                ?: slice
            blocks.add(trimmed)
        }
        return blocks
    }

    private fun extractNextCursor(html: String): String? {
        val match = CURSOR_HREF_REGEX.findAll(html).lastOrNull() ?: return null
        val rawCursor = match.groupValues.getOrNull(2)?.let(::urlDecode)?.trim().orEmpty()
        return rawCursor.takeIf { it.isNotBlank() }
    }

    private fun extractTagBlock(html: String, className: String): String {
        val idx = findClassAttributeIndex(html, className)
        if (idx < 0) return ""
        val tagStart = html.lastIndexOf('<', idx).coerceAtLeast(0)
        val tagNameEnd = html.indexOfAny(charArrayOf(' ', '>', '/'), tagStart + 1)
            .takeIf { it > tagStart } ?: return ""
        val tagName = html.substring(tagStart + 1, tagNameEnd).lowercase(Locale.US)
        val openEnd = html.indexOf('>', idx)
        if (openEnd < 0) return ""
        if (html[openEnd - 1] == '/') return html.substring(tagStart, openEnd + 1)

        var depth = 1
        var cursor = openEnd + 1
        val openPattern = "<$tagName"
        val closePattern = "</$tagName>"
        while (depth > 0 && cursor < html.length) {
            val nextOpen = html.indexOf(openPattern, cursor, ignoreCase = true)
            val nextClose = html.indexOf(closePattern, cursor, ignoreCase = true)
            if (nextClose < 0) return html.substring(tagStart)
            if (nextOpen in 0 until nextClose) {
                val afterTag = html.getOrNull(nextOpen + openPattern.length)
                if (afterTag == ' ' || afterTag == '>' || afterTag == '/') {
                    depth++
                }
                cursor = nextOpen + openPattern.length
            } else {
                depth--
                cursor = nextClose + closePattern.length
            }
        }
        return html.substring(tagStart, cursor)
    }

    private fun extractInnerHtml(html: String, className: String): String {
        val block = extractTagBlock(html, className)
        if (block.isBlank()) return ""
        val firstGt = block.indexOf('>')
        val lastLt = block.lastIndexOf("</")
        return if (firstGt in 0 until lastLt) block.substring(firstGt + 1, lastLt) else ""
    }

    private fun extractFirstElementText(html: String, className: String): String =
        extractTextFromHtml(extractInnerHtml(html, className))

    private fun findClassAttributeIndex(html: String, className: String): Int {
        var searchFrom = 0
        while (searchFrom < html.length) {
            val classAttr = html.indexOf("class=\"", searchFrom)
            if (classAttr < 0) return -1
            val valueStart = classAttr + 7
            val valueEnd = html.indexOf('"', valueStart)
            if (valueEnd < 0) return -1
            val classes = html.substring(valueStart, valueEnd)
            if (classes == className ||
                classes.startsWith("$className ") ||
                classes.endsWith(" $className") ||
                classes.contains(" $className ")
            ) {
                return classAttr
            }
            searchFrom = valueEnd + 1
        }
        return -1
    }

    private fun extractTextFromHtml(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        return unescapeHtml(TAG_REGEX.replace(rawHtml, ""))
            .replace(WHITESPACE_MULTI_REGEX, " ")
            .trim()
    }

    private fun cleanRichText(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val withBreaks = BR_REGEX.replace(rawHtml, "\n")
            .let { P_END_REGEX.replace(it, "\n") }
        val stripped = unescapeHtml(TAG_REGEX.replace(withBreaks, ""))
        return stripped
            .lines()
            .joinToString("\n") { it.replace(WHITESPACE_MULTI_REGEX, " ").trim() }
            .replace(NEWLINE_MULTI_REGEX, "\n\n")
            .trim()
    }

    internal fun parseCount(raw: String): Long {
        val cleaned = raw.trim().replace(",", "").uppercase(Locale.US)
        if (cleaned.isBlank()) return 0L
        return when {
            cleaned.endsWith("K") -> ((cleaned.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000.0).toLong()
            cleaned.endsWith("M") -> ((cleaned.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000_000.0).toLong()
            cleaned.endsWith("B") -> ((cleaned.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000_000_000.0).toLong()
            else -> cleaned.filter(Char::isDigit).toLongOrNull() ?: 0L
        }
    }

    internal fun parseNitterDate(raw: String): Long {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return System.currentTimeMillis()
        for (pattern in DATE_FORMATS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(cleaned)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return System.currentTimeMillis()
    }

    private fun decodeBase64Payload(encoded: String): String? {
        val sanitized = urlDecode(encoded).trim()
        val variants = intArrayOf(Base64.URL_SAFE or Base64.NO_WRAP, Base64.DEFAULT)
        for (flags in variants) {
            val bytes = runCatching { Base64.decode(sanitized, flags) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val text = String(bytes, Charsets.UTF_8)
                if (text.all { it.code in 32..126 }) return text
            }
        }
        return null
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun unescapeHtml(raw: String): String {
        if (!raw.contains('&')) return raw
        var result = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&middot;", "·")
        result = NUMERIC_ENTITY_REGEX.replace(result) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp ->
                runCatching { String(Character.toChars(cp)) }.getOrNull()
            } ?: m.value
        }
        result = HEX_ENTITY_REGEX.replace(result) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp ->
                runCatching { String(Character.toChars(cp)) }.getOrNull()
            } ?: m.value
        }
        return result
    }
}
