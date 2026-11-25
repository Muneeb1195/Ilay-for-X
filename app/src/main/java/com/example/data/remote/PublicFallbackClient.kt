package com.example.data.remote

import com.example.domain.model.LinkPreview
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.QuotedTweet
import com.example.domain.model.SearchTab
import com.example.domain.model.ThreadConversation
import com.example.domain.model.TimelinePage
import com.example.domain.model.Tweet
import com.example.domain.model.TweetMetrics
import com.example.domain.model.XUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Unified 2-tier public/fallback client for Ilay for X:
 * 1. **Single Nitter Instance** (default `https://nitter.netbub.com`, customizable in Settings)
 *    with full HTML parsing for timelines (posts/replies/media), full reply threads, user
 *    profiles, and paginated global/profile search (plus lightweight RSS backup).
 * 2. **FxTwitter Open API (`api.fxtwitter.com`)** as a focused JSON safety net for single-post
 *    lookups and profile hydration/enrichment if the Nitter instance is temporarily throttled.
 */
class PublicFallbackClient(
    private val okHttpClient: OkHttpClient,
) {
    private val fastClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val handleTimelineCache = BoundedTtlCache<String, List<Tweet>>(
        maxEntries = 48,
        defaultTtlMs = PUBLIC_HANDLE_CACHE_TTL_MS,
    )
    private val handleCoalescer = RequestCoalescer<String, Result<List<Tweet>>>()

    private val profileCache = BoundedTtlCache<String, XUser>(
        maxEntries = 60,
        defaultTtlMs = PUBLIC_PROFILE_CACHE_TTL_MS,
    )
    private val profileCoalescer = RequestCoalescer<String, Result<XUser>>()

    private val rfc822Format: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun clearCaches() {
        handleTimelineCache.clear()
        profileCache.clear()
    }

    suspend fun fetchPublicProfile(
        handle: String,
        customProxyBaseUrl: String = "",
        forceRefresh: Boolean = false,
    ): Result<XUser> {
        val clean = handle.trim().removePrefix("@")
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Empty handle"))
        val baseUrl = NitterHtmlParser.normalizeBaseUrl(customProxyBaseUrl)
        val cacheKey = "${clean.lowercase(Locale.US)}|$baseUrl"
        if (!forceRefresh) {
            profileCache.get(cacheKey)?.let { return Result.success(it) }
        }
        return profileCoalescer.coalesce(cacheKey) {
            withContext(Dispatchers.IO) {
                // Tier 1: Nitter HTML profile page
                val nitterResult = runSuspendCatching {
                    val html = fetchHtml("$baseUrl/$clean")
                    NitterHtmlParser.parseProfile(html, clean, baseUrl)
                        ?: error("Nitter profile unavailable")
                }
                if (nitterResult.isSuccess) {
                    val user = nitterResult.getOrThrow()
                    profileCache.put(cacheKey, user)
                    return@withContext Result.success(user)
                }

                // Tier 2: FxTwitter JSON safety net
                fetchFxProfile(clean).onSuccess { profileCache.put(cacheKey, it) }
            }
        }
    }

    suspend fun fetchPublicTweet(
        handle: String,
        tweetId: String,
        customProxyBaseUrl: String = "",
    ): Result<Tweet> = withContext(Dispatchers.IO) {
        val clean = handle.trim().removePrefix("@").ifBlank { "i" }
        val baseUrl = NitterHtmlParser.normalizeBaseUrl(customProxyBaseUrl)
        withTimeoutOrNull(6_000L) {
            // Fast JSON lookup via FxTwitter first for single tweet embeds/metadata, then Nitter HTML
            val fxRes = fetchFxTweet(clean, tweetId)
            if (fxRes.isSuccess) return@withTimeoutOrNull fxRes

            runSuspendCatching {
                val html = fetchHtml("$baseUrl/$clean/status/$tweetId")
                val conv = NitterHtmlParser.parseThreadConversation(html, tweetId, baseUrl)
                    ?: error("Nitter tweet unavailable")
                conv.focalTweet
            }
        } ?: Result.failure(IllegalStateException("Public tweet lookup timed out"))
    }

    /**
     * Loads a full thread conversation (ancestors, focal tweet, and replies) from the active
     * Nitter instance, falling back to FxTwitter for the focal post if Nitter is unavailable.
     */
    suspend fun fetchPublicThread(
        handle: String,
        tweetId: String,
        customProxyBaseUrl: String = "",
    ): Result<ThreadConversation> = withContext(Dispatchers.IO) {
        val clean = handle.trim().removePrefix("@").ifBlank { "i" }
        val baseUrl = NitterHtmlParser.normalizeBaseUrl(customProxyBaseUrl)

        val nitterThread = withTimeoutOrNull(5_500L) {
            runSuspendCatching {
                val html = fetchHtml("$baseUrl/$clean/status/$tweetId")
                NitterHtmlParser.parseThreadConversation(html, tweetId, baseUrl)
                    ?: error("Nitter thread unavailable")
            }
        }
        if (nitterThread != null && nitterThread.isSuccess) {
            return@withContext nitterThread
        }

        // Tier 2: FxTwitter safety net (focal tweet only)
        fetchFxTweet(clean, tweetId).map { focal ->
            ThreadConversation(
                ancestors = emptyList(),
                focalTweet = focal,
                replies = emptyList(),
            )
        }
    }

    suspend fun fetchPublicTimelineForHandle(
        handle: String,
        customProxyBaseUrl: String = "",
        tabPath: String = "",
        forceRefresh: Boolean = false,
    ): Result<List<Tweet>> {
        val clean = handle.trim().removePrefix("@")
        if (clean.isBlank()) return Result.success(emptyList())
        val baseUrl = NitterHtmlParser.normalizeBaseUrl(customProxyBaseUrl)
        val normalizedTab = tabPath.trim().trim('/')
        val cacheKey = "${clean.lowercase(Locale.US)}|$normalizedTab|$baseUrl"
        if (!forceRefresh) {
            handleTimelineCache.get(cacheKey)?.let { return Result.success(it) }
        }

        return handleCoalescer.coalesce(cacheKey) {
            val result = withContext(Dispatchers.IO) {
                fetchNitterHandleTimeline(clean, baseUrl, normalizedTab)
            }
            if (result.isSuccess && result.getOrNull().orEmpty().isNotEmpty()) {
                handleTimelineCache.put(cacheKey, result.getOrThrow())
            }
            result
        }
    }

    private suspend fun fetchNitterHandleTimeline(
        cleanHandle: String,
        baseUrl: String,
        tabPath: String,
    ): Result<List<Tweet>> {
        val host = NitterHtmlParser.displayHost(baseUrl)
        val htmlUrl = if (tabPath.isNotBlank()) "$baseUrl/$cleanHandle/$tabPath" else "$baseUrl/$cleanHandle"

        // 1. Primary: full Nitter HTML timeline
        val htmlResult = runSuspendCatching {
            val html = fetchHtml(htmlUrl)
            val page = NitterHtmlParser.parseTimelinePage(
                html = html,
                baseUrl = baseUrl,
                sourceInfo = "Nitter ($host)",
            )
            page.tweets
        }
        if (htmlResult.isSuccess && htmlResult.getOrNull().orEmpty().isNotEmpty()) {
            return htmlResult
        }

        // 2. Lightweight Nitter RSS backup if HTML is unavailable (main timeline only)
        if (tabPath.isBlank()) {
            val rssResult = runSuspendCatching {
                val req = Request.Builder()
                    .url("$baseUrl/$cleanHandle/rss")
                    .header("User-Agent", XEndpointRegistry.WEB_USER_AGENT)
                    .build()
                fastClient.newCall(req).awaitUse { resp ->
                    if (!resp.isSuccessful) error("Nitter RSS HTTP ${resp.code}")
                    parseRssTimeline(resp.body.string(), cleanHandle, host)
                }
            }
            if (rssResult.isSuccess && rssResult.getOrNull().orEmpty().isNotEmpty()) {
                return rssResult
            }
        }

        return Result.success(emptyList())
    }

    suspend fun fetchPublicDiscoveryFeed(
        handles: List<String>,
        customProxyBaseUrl: String = "",
        forceRefresh: Boolean = false,
    ): TimelinePage = coroutineScope {
        val baseUrl = NitterHtmlParser.normalizeBaseUrl(customProxyBaseUrl)
        val host = NitterHtmlParser.displayHost(baseUrl)
        val targetHandles = handles.ifEmpty { listOf("AndroidDev", "kotlin", "NASA", "GitHub") }.take(6)
        val results = targetHandles.map { handle ->
            async(Dispatchers.IO) {
                fetchPublicTimelineForHandle(
                    handle = handle,
                    customProxyBaseUrl = baseUrl,
                    forceRefresh = forceRefresh,
                ).getOrDefault(emptyList())
            }
        }.awaitAll().flatten()

        val sorted = results
            .distinctBy { it.id }
            .sortedByDescending { it.createdAtEpochMs }

        TimelinePage(
            tweets = sorted,
            nextCursor = null,
            isFromPublicFallback = true,
            sourceInfo = "Nitter ($host)",
        )
    }

    /**
     * Global & profile search via the single active Nitter instance (`/search?f=tweets&q=...`),
     * supporting `#hashtags`, `from:@handle`, Media filtering, and cursor pagination.
     */
    suspend fun searchPublicGlobalTweets(
        query: String,
        tab: SearchTab,
        customProxyBaseUrl: String = "",
        cursor: String? = null,
    ): Result<TimelinePage> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(TimelinePage(emptyList()))

        val baseUrl = NitterHtmlParser.normalizeBaseUrl(customProxyBaseUrl)
        val host = NitterHtmlParser.displayHost(baseUrl)

        val searchUrlBuilder = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("f", "tweets")
            .addQueryParameter("q", trimmed)
        if (tab == SearchTab.MEDIA) {
            searchUrlBuilder.addQueryParameter("f-images", "on")
            searchUrlBuilder.addQueryParameter("f-videos", "on")
        }
        if (!cursor.isNullOrBlank()) {
            searchUrlBuilder.addQueryParameter("cursor", cursor)
        }

        // 1. Primary: Nitter HTML search with full metadata & cursor pagination
        val htmlSearch = runSuspendCatching {
            val html = fetchHtml(searchUrlBuilder.build().toString())
            val page = NitterHtmlParser.parseTimelinePage(
                html = html,
                baseUrl = baseUrl,
                sourceInfo = "Nitter Search ($host)",
            )
            val filtered = page.tweets
                .let { items -> if (tab == SearchTab.MEDIA) items.filter { it.media.isNotEmpty() } else items }
                .let { items ->
                    when (tab) {
                        SearchTab.LATEST -> items.sortedByDescending { it.createdAtEpochMs }
                        SearchTab.TOP -> items.sortedByDescending { it.metrics.likes + it.metrics.reposts * 2L }
                        else -> items
                    }
                }
            page.copy(tweets = filtered)
        }
        if (htmlSearch.isSuccess && htmlSearch.getOrNull()?.tweets.orEmpty().isNotEmpty()) {
            return@withContext htmlSearch
        }

        // 2. Lightweight Nitter RSS search backup (first page only)
        if (cursor == null) {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val rssSearch = runSuspendCatching {
                val req = Request.Builder()
                    .url("$baseUrl/search/rss?f=tweets&q=$encoded")
                    .header("User-Agent", XEndpointRegistry.WEB_USER_AGENT)
                    .build()
                fastClient.newCall(req).awaitUse { resp ->
                    if (!resp.isSuccessful) error("Nitter search RSS HTTP ${resp.code}")
                    parseRssTimeline(resp.body.string(), "x", host)
                }
            }
            val list = rssSearch.getOrNull().orEmpty()
                .let { items -> if (tab == SearchTab.MEDIA) items.filter { it.media.isNotEmpty() } else items }
                .let { items -> if (tab == SearchTab.LATEST) items.sortedByDescending { it.createdAtEpochMs } else items }
            if (list.isNotEmpty()) {
                return@withContext Result.success(
                    TimelinePage(
                        tweets = list,
                        isFromPublicFallback = true,
                        sourceInfo = "Nitter RSS Search ($host)",
                    )
                )
            }
        }

        Result.failure(IllegalStateException("Nitter search ($host) returned no results."))
    }

    suspend fun searchPublicUsers(
        query: String,
        customProxyBaseUrl: String = "",
    ): Result<List<XUser>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext Result.success(emptyList())
        val baseUrl = NitterHtmlParser.normalizeBaseUrl(customProxyBaseUrl)
        runSuspendCatching {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("f", "users")
                .addQueryParameter("q", trimmed)
                .build()
                .toString()
            val html = fetchHtml(url)
            NitterHtmlParser.parseUsersSearch(html, baseUrl)
        }
    }

    private suspend fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XEndpointRegistry.DESKTOP_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return fastClient.newCall(req).awaitUse { resp ->
            if (!resp.isSuccessful) error("Nitter HTTP ${resp.code}")
            resp.body.string()
        }
    }

    private suspend fun fetchFxProfile(cleanHandle: String): Result<XUser> = runSuspendCatching {
        val req = Request.Builder()
            .url("https://api.fxtwitter.com/$cleanHandle")
            .header("User-Agent", "IlayForX/1.0 (+https://github.com/ilayforx)")
            .build()
        fastClient.newCall(req).awaitUse { resp ->
            if (!resp.isSuccessful) error("FxTwitter profile HTTP ${resp.code}")
            val root = JSONObject(resp.body.string())
            val u = root.optJSONObject("user") ?: error("Profile not found")
            parseFxUser(u)
        }
    }

    private suspend fun fetchFxTweet(cleanHandle: String, tweetId: String): Result<Tweet> = runSuspendCatching {
        val req = Request.Builder()
            .url("https://api.fxtwitter.com/$cleanHandle/status/$tweetId")
            .header("User-Agent", "IlayForX/1.0")
            .build()
        fastClient.newCall(req).awaitUse { resp ->
            if (!resp.isSuccessful) error("FxTwitter status HTTP ${resp.code}")
            val root = JSONObject(resp.body.string())
            val t = root.optJSONObject("tweet") ?: error("Tweet not found")
            parseFxTweet(t)
        }
    }

    private fun parseFxUser(u: JSONObject): XUser {
        val handle = u.optString("screen_name", "user")
        val followers = u.optLong("followers", -1L).takeIf { it >= 0L }
            ?: u.optLong("followers_count", 0L)
        val following = u.optLong("following", -1L).takeIf { it >= 0L }
            ?: u.optLong("friends_count", 0L)
        return XUser(
            id = u.optString("id", handle),
            handle = handle,
            displayName = u.optString("name", handle),
            avatarUrl = u.optString("avatar_url").replace("_normal", "_400x400"),
            bannerUrl = u.optString("banner_url"),
            bio = u.optString("description"),
            location = u.optString("location"),
            websiteUrl = u.optJSONObject("website")?.optString("url").orEmpty(),
            isVerified = u.optString("verification").isNotBlank() && u.optString("verification") != "null",
            followersCount = followers,
            followingCount = following,
            tweetsCount = u.optLong("tweets"),
            joinedLabel = u.optString("joined"),
        )
    }

    private fun parseFxTweet(t: JSONObject): Tweet {
        val id = t.optString("id")
        val authorObj = t.optJSONObject("author") ?: JSONObject()
        val author = parseFxUser(authorObj)
        val media = mutableListOf<MediaAttachment>()
        val mediaObj = t.optJSONObject("media")
        val photos = mediaObj?.optJSONArray("photos")
        if (photos != null) {
            for (i in 0 until photos.length()) {
                val p = photos.optJSONObject(i) ?: continue
                media.add(
                    MediaAttachment(
                        id = "$id-p$i",
                        type = MediaType.PHOTO,
                        url = p.optString("url"),
                        width = p.optInt("width", 1200),
                        height = p.optInt("height", 675),
                        altText = p.optString("altText"),
                    )
                )
            }
        }
        val videos = mediaObj?.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val (bestVideoUrl, dataSaverVideoUrl) = AdaptiveImageQualitySelector.pickVideoVariants(
                    v.optJSONArray("variants")
                )
                val resolvedBestUrl = bestVideoUrl ?: v.optString("url").takeIf { it.isNotBlank() }
                media.add(
                    MediaAttachment(
                        id = "$id-v$i",
                        type = if (v.optString("type") == "gif") MediaType.GIF else MediaType.VIDEO,
                        url = v.optString("thumbnail_url"),
                        thumbnailUrl = v.optString("thumbnail_url"),
                        videoUrl = resolvedBestUrl,
                        dataSaverVideoUrl = dataSaverVideoUrl,
                        width = v.optInt("width", 1200),
                        height = v.optInt("height", 675),
                    )
                )
            }
        }

        val poll = XPollParser.parsePublicPollJson(t.optJSONObject("poll"))

        val qtObj = t.optJSONObject("quote")
        val quoted = qtObj?.let { parseFxTweet(it) }?.let { qt ->
            QuotedTweet.fromTweet(qt)
        }

        val timestamp = t.optLong("created_timestamp", 0L).let { sec ->
            if (sec > 0L) sec * 1000L else XGraphqlParser.parseTwitterDate(t.optString("created_at"))
        }

        val fxArticle = t.optJSONObject("article")?.let { articleObj ->
            XGraphqlParser.parseArticleNode(articleObj, id, author.handle)
        }

        val rawFxText = t.optString("text")
        val matchedUrl = URL_REGEX.find(rawFxText)?.value?.takeIf {
            !it.contains("x.com/") && !it.contains("twitter.com/") && !it.contains("t.co/")
        }
        val fxLinkPreview = if (fxArticle == null) {
            matchedUrl?.let { link ->
                val dom = runCatching { java.net.URI(link).host?.removePrefix("www.").orEmpty() }.getOrDefault("")
                LinkPreview(url = link, displayUrl = dom.ifBlank { link }, domain = dom)
            }
        } else {
            null
        }
        val cleanedFxText = if (matchedUrl != null) rawFxText.replace(matchedUrl, "").trim() else rawFxText

        return Tweet(
            id = id,
            conversationId = id,
            author = author,
            text = cleanedFxText,
            createdAtEpochMs = timestamp,
            media = media,
            poll = poll,
            quotedTweet = quoted,
            quotedTweetId = quoted?.id,
            article = fxArticle,
            linkPreview = fxLinkPreview,
            replyToHandle = t.optString("replying_to").takeIf { it.isNotBlank() && it != "null" },
            replyToTweetId = t.optString("replying_to_status").takeIf { it.isNotBlank() && it != "null" },
            metrics = TweetMetrics(
                replies = t.optLong("replies"),
                reposts = t.optLong("retweets"),
                quotes = t.optLong("quotes"),
                likes = t.optLong("likes"),
                views = t.optLong("views"),
            ),
            lang = t.optString("lang"),
            sourceLabel = "FxTwitter Open API",
        )
    }

    private fun parseRssTimeline(xml: String, fallbackHandle: String, host: String): List<Tweet> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        val tweets = mutableListOf<Tweet>()
        var inItem = false
        var title = ""
        var link = ""
        var pubDate = ""
        var creator = fallbackHandle

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.US)) {
                        "item" -> {
                            inItem = true
                            title = ""
                            link = ""
                            pubDate = ""
                            creator = fallbackHandle
                        }
                        "title" -> if (inItem) title = parser.nextText()
                        "link" -> if (inItem) link = parser.nextText()
                        "pubdate" -> if (inItem) pubDate = parser.nextText()
                        "creator" -> if (inItem) creator = parser.nextText().removePrefix("@")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true) && inItem) {
                        val id = STATUS_ID_REGEX.find(link)?.groupValues?.getOrNull(1)
                            ?: "rss_${title.hashCode()}"
                        val ts = runCatching { rfc822Format.get()?.parse(pubDate)?.time }.getOrNull()
                            ?: System.currentTimeMillis()
                        if (title.isNotBlank()) {
                            tweets.add(
                                Tweet(
                                    id = id,
                                    author = XUser(id = creator, handle = creator, displayName = "@$creator"),
                                    text = title.trim(),
                                    createdAtEpochMs = ts,
                                    sourceLabel = "Nitter RSS ($host)",
                                )
                            )
                        }
                        inItem = false
                    }
                }
            }
            eventType = parser.next()
        }
        return tweets
    }

    companion object {
        private const val PUBLIC_HANDLE_CACHE_TTL_MS = 3L * 60 * 1000L
        private const val PUBLIC_PROFILE_CACHE_TTL_MS = 2L * 60 * 1000L
        private val URL_REGEX = Regex("""https?://\S+""")
        private val STATUS_ID_REGEX = Regex("/status/(\\d+)")
    }
}
