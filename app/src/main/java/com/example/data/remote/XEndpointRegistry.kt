package com.example.data.remote

import com.example.data.local.EndpointHashDao
import com.example.data.local.EndpointHashEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ResolvedEndpoint(
    val operationName: String,
    val queryId: String,
    val featuresJson: String,
    val source: String,
)

/**
 * Dynamically resolves and caches X internal GraphQL queryIds and exact feature-flag maps.
 * Scans live x.com webpack bundles (including main + lazily-loaded API chunks) and open-source
 * community manifests, extracting BOTH the active queryId and required featureSwitches so
 * SearchTimeline, UserByScreenName, Bookmarks, and TweetDetail self-heal from hash rotations.
 */
class XEndpointRegistry(
    okHttpClient: OkHttpClient,
    private val endpointHashDao: EndpointHashDao,
) {
    private val fastClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val memoryCache = ConcurrentHashMap<String, ResolvedEndpoint>()
    private val refreshMutex = Mutex()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var loadedFromDb = false
    @Volatile
    private var lastRemoteRefreshAttemptMs = 0L

    fun observeDiagnostics(): Flow<List<EndpointHashEntity>> = endpointHashDao.observeAll()

    /**
     * Triggers a silent, non-blocking out-of-band sync of GraphQL queryIds and feature flags.
     * Interactive UI requests NEVER wait on webpack scraping; they call this when a persistent
     * 404 suggests X rotated a hash so subsequent sessions pick up the fresh config automatically.
     */
    fun scheduleBackgroundRefresh(force: Boolean = false) {
        backgroundScope.launch {
            runSuspendCatching { refreshFromRemote(force = force) }
        }
    }

    /**
     * Sub-millisecond targeted repair for X GraphQL error code 336
     * (`"The following features cannot be null: ..."`). Merges only the missing flag names into
     * memory and Room without making any network requests.
     */
    suspend fun patchMissingFeatures(
        operationName: String,
        missingFlags: List<String>,
    ): Boolean {
        if (missingFlags.isEmpty()) return false
        ensureInitialized()
        val current = resolve(operationName)
        val obj = runCatching { JSONObject(current.featuresJson) }.getOrElse { JSONObject() }
        var changed = false
        for (flag in missingFlags) {
            val clean = flag.trim()
            if (clean.isNotBlank() && !obj.has(clean)) {
                obj.put(clean, defaultFeatureValue(clean))
                changed = true
            }
        }
        if (!changed) return false
        val updatedJson = obj.toString()
        val updatedResolved = current.copy(featuresJson = updatedJson)
        memoryCache[operationName] = updatedResolved
        withContext(Dispatchers.IO) {
            endpointHashDao.upsertAll(
                listOf(
                    EndpointHashEntity(
                        operationName = operationName,
                        queryId = updatedResolved.queryId,
                        featuresJson = updatedJson,
                        source = updatedResolved.source,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                )
            )
        }
        return true
    }

    suspend fun ensureInitialized() {
        if (loadedFromDb) return
        withContext(Dispatchers.IO) {
            val saved = endpointHashDao.getAllOnce()
            val savedMap = saved.associateBy { it.operationName }
            val toUpsert = mutableListOf<EndpointHashEntity>()
            val now = System.currentTimeMillis()

            DEFAULT_QUERY_IDS.forEach { (op, defaultQid) ->
                val existing = savedMap[op]
                val needsDefaultUpgrade = existing == null ||
                    (existing.source.startsWith("Built-in") &&
                        (existing.queryId != defaultQid || existing.featuresJson != defaultFeaturesFor(op)))
                if (needsDefaultUpgrade) {
                    val entity = EndpointHashEntity(
                        operationName = op,
                        queryId = defaultQid,
                        featuresJson = defaultFeaturesFor(op),
                        source = BUILTIN_SOURCE_LABEL,
                        updatedAtEpochMs = now,
                    )
                    toUpsert.add(entity)
                    memoryCache[op] = entity.toResolved()
                } else {
                    // Always merge newly required feature flags into cached entries so X never 400s
                    val mergedFeatures = mergeFeaturesJson(existing.featuresJson, defaultFeaturesFor(op))
                    val upgraded = existing.copy(featuresJson = mergedFeatures)
                    memoryCache[op] = upgraded.toResolved()
                }
            }

            if (toUpsert.isNotEmpty()) {
                endpointHashDao.upsertAll(toUpsert)
            }
            loadedFromDb = true
        }
    }

    suspend fun resolve(operationName: String): ResolvedEndpoint {
        ensureInitialized()
        memoryCache[operationName]?.let { return it }
        val fallbackQid = DEFAULT_QUERY_IDS[operationName]
            ?: error("Unknown X GraphQL operation: $operationName")
        return ResolvedEndpoint(
            operationName = operationName,
            queryId = fallbackQid,
            featuresJson = defaultFeaturesFor(operationName),
            source = BUILTIN_SOURCE_LABEL,
        )
    }

    /**
     * Fetches the latest GraphQL queryIds and exact featureSwitches from live x.com frontend
     * webpack chunks and open-source community manifests concurrently. Includes a 60s cooldown
     * on automatic retries so simultaneous 404s never trigger redundant scrape storms.
     */
    suspend fun refreshFromRemote(force: Boolean = false): Result<Int> = refreshMutex.withLock {
        ensureInitialized()
        val nowAttempt = System.currentTimeMillis()
        if (!force && (nowAttempt - lastRemoteRefreshAttemptMs) < MIN_REFRESH_COOLDOWN_MS) {
            return@withLock Result.success(0)
        }
        lastRemoteRefreshAttemptMs = nowAttempt

        withContext(Dispatchers.IO) {
            val discovered = ConcurrentHashMap<String, DiscoveredOp>()

            // 1. Live x.com webpack bundle scan (most authoritative & freshest source)
            runCatching {
                scanLiveXWebBundles(discovered)
            }

            // 2. Supplement any remaining operations concurrently from community manifests
            if (discovered.size < DEFAULT_QUERY_IDS.size || !discovered.containsKey("SearchTimeline")) {
                runCatching {
                    val manifestUrls = listOf(
                        "https://raw.githubusercontent.com/fa0311/twitter-openapi-typescript-generated/main/src/config/placeholder.json",
                        "https://raw.githubusercontent.com/fa0311/twitter-openapi/main/src/config/placeholder.json",
                        "https://raw.githubusercontent.com/vladkens/twscrape/main/twscrape/api.py",
                        "https://raw.githubusercontent.com/d60/twikit/main/twikit/client/gql.py",
                    )
                    coroutineScope {
                        manifestUrls.map { url ->
                            async(Dispatchers.IO) {
                                runSuspendCatching {
                                    val req = Request.Builder()
                                        .url(url)
                                        .header("User-Agent", WEB_USER_AGENT)
                                        .build()
                                    fastClient.newCall(req).awaitUse { resp ->
                                        if (!resp.isSuccessful) return@awaitUse
                                        val body = resp.body.string()
                                        if (url.endsWith(".json")) {
                                            parseCommunityJsonManifest(body, discovered)
                                        } else {
                                            parseRegexMappings(body, "Community Manifest", discovered)
                                            parsePythonOpMappings(body, "Community Manifest", discovered)
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }

            if (discovered.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Could not reach live X bundles or manifests; retaining cached hashes.")
                )
            }

            val now = System.currentTimeMillis()
            val updatedEntities = discovered.entries.mapNotNull { (op, info) ->
                if (!DEFAULT_QUERY_IDS.containsKey(op)) return@mapNotNull null
                val mergedFeatures = if (info.featureSwitches.isNotEmpty()) {
                    buildFeaturesFromSwitches(op, info.featureSwitches)
                } else {
                    defaultFeaturesFor(op)
                }
                EndpointHashEntity(
                    operationName = op,
                    queryId = info.queryId,
                    featuresJson = mergedFeatures,
                    source = info.source,
                    updatedAtEpochMs = now,
                )
            }

            if (updatedEntities.isNotEmpty()) {
                endpointHashDao.upsertAll(updatedEntities)
                updatedEntities.forEach { memoryCache[it.operationName] = it.toResolved() }
            }
            Result.success(updatedEntities.size)
        }
    }

    private data class DiscoveredOp(
        val queryId: String,
        val source: String,
        val featureSwitches: List<String> = emptyList(),
    )

    private fun parseCommunityJsonManifest(
        rawJson: String,
        out: MutableMap<String, DiscoveredOp>,
    ) {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        val keys = root.keys()
        while (keys.hasNext()) {
            val opName = keys.next()
            if (out.containsKey(opName)) continue
            val obj = root.optJSONObject(opName) ?: continue
            val qid = obj.optString("queryId").takeIf { it.isNotBlank() }
                ?: obj.optString("query_id").takeIf { it.isNotBlank() }
            if (qid != null) {
                out[opName] = DiscoveredOp(qid, "twitter-openapi Sync")
            }
        }
    }

    private fun parsePythonOpMappings(
        sourceText: String,
        sourceName: String,
        out: MutableMap<String, DiscoveredOp>,
    ) {
        // Matches patterns like: OP_SearchTimeline = "BYppgupFFS2yqVmmRNjlsw/SearchTimeline" or "SearchTimeline": "hash"
        val slashMatcher = SLASH_OP_PATTERN.matcher(sourceText)
        while (slashMatcher.find()) {
            val qid = slashMatcher.group(1) ?: continue
            val op = slashMatcher.group(2) ?: continue
            if (DEFAULT_QUERY_IDS.containsKey(op) && !out.containsKey(op)) {
                out[op] = DiscoveredOp(qid, sourceName)
            }
        }
        val dictMatcher = DICT_OP_PATTERN.matcher(sourceText)
        while (dictMatcher.find()) {
            val op = dictMatcher.group(1) ?: continue
            val qid = dictMatcher.group(2) ?: continue
            if (DEFAULT_QUERY_IDS.containsKey(op) && !out.containsKey(op)) {
                out[op] = DiscoveredOp(qid, sourceName)
            }
        }
    }

    private suspend fun scanLiveXWebBundles(out: ConcurrentHashMap<String, DiscoveredOp>) = coroutineScope {
        val pages = listOf(
            "https://x.com/search?q=news&src=typed_query",
            "https://x.com/explore",
            "https://x.com/?mx=2",
        )
        val scriptUrls = LinkedHashSet<String>()

        fun extractChunkUrls(text: String, target: MutableCollection<String>) {
            val chunkMatcher = WEBPACK_CHUNK_PATTERN.matcher(text)
            while (chunkMatcher.find()) {
                val name = chunkMatcher.group(1) ?: continue
                val hash = chunkMatcher.group(2) ?: continue
                if (name.contains("api", ignoreCase = true) ||
                    name.contains("search", ignoreCase = true) ||
                    name.contains("explore", ignoreCase = true) ||
                    name.contains("profile", ignoreCase = true) ||
                    name.contains("bookmark", ignoreCase = true) ||
                    name.contains("article", ignoreCase = true) ||
                    name.contains("endpoint", ignoreCase = true) ||
                    name.contains("timeline", ignoreCase = true)
                ) {
                    target.add("https://abs.twimg.com/responsive-web/client-web/$name.${hash}a.js")
                }
            }
        }

        for (pageUrl in pages) {
            val html = runSuspendCatching {
                val req = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                fastClient.newCall(req).awaitUse { resp ->
                    if (!resp.isSuccessful) "" else resp.body.string()
                }
            }.getOrDefault("")
            if (html.isBlank()) continue

            val matcher = SCRIPT_URL_PATTERN.matcher(html)
            while (matcher.find()) {
                matcher.group(1)?.let { scriptUrls.add(it) }
            }
            extractChunkUrls(html, scriptUrls)
            if (scriptUrls.any { it.contains("/main.") || it.contains("Search", ignoreCase = true) }) {
                break
            }
        }

        val initialQueue = scriptUrls.sortedBy { url ->
            when {
                url.contains("Search", ignoreCase = true) -> 0
                url.contains("/main.") -> 1
                url.contains("/api.") -> 2
                url.contains("shared") || url.contains("vendor") -> 3
                else -> 4
            }
        }.take(10)

        val scanned = ConcurrentHashMap.newKeySet<String>()
        val dynamicDiscoveredChunks = ConcurrentHashMap.newKeySet<String>()
        val limiter = Semaphore(permits = 4)

        // Fetch and parse top webpack bundles concurrently (4 in-flight) instead of sequentially
        initialQueue.map { jsUrl ->
            async(Dispatchers.IO) {
                if (!scanned.add(jsUrl)) return@async
                limiter.withPermit {
                    runSuspendCatching {
                        val jsReq = Request.Builder()
                            .url(jsUrl)
                            .header("User-Agent", DESKTOP_USER_AGENT)
                            .build()
                        fastClient.newCall(jsReq).awaitUse { resp ->
                            if (!resp.isSuccessful) return@awaitUse
                            val js = resp.body.string()
                            parseRegexMappings(js, "Live x.com Bundle", out)
                            if (jsUrl.contains("/main.")) {
                                val lazyFound = LinkedHashSet<String>()
                                extractChunkUrls(js, lazyFound)
                                lazyFound.filterNot { scanned.contains(it) }.forEach { dynamicDiscoveredChunks.add(it) }
                            }
                        }
                    }
                }
            }
        }.awaitAll()

        // Follow lazy Search / API chunks discovered inside main.*.js concurrently
        if (!out.containsKey("SearchTimeline") || out.size < 14) {
            val lazyPrioritized = dynamicDiscoveredChunks.sortedBy { url ->
                when {
                    url.contains("Search", ignoreCase = true) -> 0
                    url.contains("/api.") -> 1
                    else -> 2
                }
            }.take(6)

            lazyPrioritized.map { chunkUrl ->
                async(Dispatchers.IO) {
                    if (!scanned.add(chunkUrl)) return@async
                    limiter.withPermit {
                        runSuspendCatching {
                            val jsReq = Request.Builder()
                                .url(chunkUrl)
                                .header("User-Agent", DESKTOP_USER_AGENT)
                                .build()
                            fastClient.newCall(jsReq).awaitUse { resp ->
                                if (!resp.isSuccessful) return@awaitUse
                                parseRegexMappings(resp.body.string(), "Live x.com Bundle", out)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun parseRegexMappings(
        sourceText: String,
        sourceName: String,
        out: MutableMap<String, DiscoveredOp>,
    ) {
        // Rich pattern: captures queryId, operationName, and full featureSwitches array (even for large 40+ flag tables)
        val rich = RICH_OP_PATTERN.matcher(sourceText)
        while (rich.find()) {
            val qid = rich.group(1) ?: continue
            val op = rich.group(2) ?: continue
            if (!DEFAULT_QUERY_IDS.containsKey(op)) continue
            val switchesRaw = rich.group(3).orEmpty()
            val switches = FEATURE_SWITCH_ITEM_REGEX
                .findAll(switchesRaw)
                .map { it.groupValues[1] }
                .toList()
            val existing = out[op]
            if (existing == null || existing.featureSwitches.isEmpty()) {
                out[op] = DiscoveredOp(qid, sourceName, switches)
            }
        }

        val m1 = QUERY_THEN_OP_PATTERN.matcher(sourceText)
        while (m1.find()) {
            val qid = m1.group(1) ?: continue
            val op = m1.group(2) ?: continue
            if (DEFAULT_QUERY_IDS.containsKey(op) && !out.containsKey(op)) {
                out[op] = DiscoveredOp(qid, sourceName)
            }
        }

        val m2 = OP_THEN_QUERY_PATTERN.matcher(sourceText)
        while (m2.find()) {
            val op = m2.group(1) ?: continue
            val qid = m2.group(2) ?: continue
            if (DEFAULT_QUERY_IDS.containsKey(op) && !out.containsKey(op)) {
                out[op] = DiscoveredOp(qid, sourceName)
            }
        }
    }

    private fun defaultFeatureValue(flag: String): Boolean =
        !flag.contains("disable", ignoreCase = true) &&
            !flag.contains("verified_phone_label", ignoreCase = true) &&
            !flag.contains("skip_user_profile_image", ignoreCase = true) &&
            !flag.contains("payments_enabled", ignoreCase = true) &&
            !flag.contains("premium_content_api_read", ignoreCase = true)

    private fun buildFeaturesFromSwitches(operationName: String, switches: List<String>): String {
        val base = runCatching { JSONObject(defaultFeaturesFor(operationName)) }.getOrElse { JSONObject() }
        for (flag in switches) {
            if (!base.has(flag)) {
                base.put(flag, defaultFeatureValue(flag))
            }
        }
        return base.toString()
    }

    private fun mergeFeaturesJson(existingJson: String, defaultJson: String): String {
        val merged = runCatching { JSONObject(defaultJson) }.getOrElse { JSONObject() }
        val existing = runCatching { JSONObject(existingJson) }.getOrNull() ?: return merged.toString()
        val keys = existing.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!merged.has(k)) {
                merged.put(k, existing.opt(k))
            }
        }
        return merged.toString()
    }

    suspend fun refreshIfStale(maxAgeMs: Long = 3L * 60 * 60 * 1000L) {
        ensureInitialized()
        val all = withContext(Dispatchers.IO) { endpointHashDao.getAllOnce() }
        val latestUpdate = all.maxOfOrNull { it.updatedAtEpochMs } ?: 0L
        val anySynced = all.any { !it.source.startsWith("Built-in") }
        if (!anySynced || (System.currentTimeMillis() - latestUpdate) > maxAgeMs) {
            refreshFromRemote()
        }
    }

    private fun EndpointHashEntity.toResolved() = ResolvedEndpoint(
        operationName = operationName,
        queryId = queryId,
        featuresJson = featuresJson,
        source = source,
    )

    companion object {
        private const val BUILTIN_SOURCE_LABEL = "Built-in 2026 v4"
        private const val MIN_REFRESH_COOLDOWN_MS = 60_000L

        const val WEB_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"

        private val SCRIPT_URL_PATTERN = Pattern.compile(
            """(?:src|href)=["'](https://abs\.twimg\.com/responsive-web/client-web[^"']*\.js)["']"""
        )
        private val WEBPACK_CHUNK_PATTERN = Pattern.compile(
            """["']([a-zA-Z0-9._~-]{2,80})["']\s*:\s*["']([a-f0-9]{7,10})["']"""
        )
        private val RICH_OP_PATTERN = Pattern.compile(
            """queryId\s*:\s*["']([A-Za-z0-9_-]{18,36})["']\s*,\s*operationName\s*:\s*["']([A-Za-z0-9_]+)["'][^\}]{0,1800}?featureSwitches\s*:\s*\[([^\]]*)\]"""
        )
        private val QUERY_THEN_OP_PATTERN = Pattern.compile(
            """queryId\s*:\s*["']([A-Za-z0-9_-]{18,36})["']\s*,\s*operationName\s*:\s*["']([A-Za-z0-9_]+)["']"""
        )
        private val OP_THEN_QUERY_PATTERN = Pattern.compile(
            """operationName\s*:\s*["']([A-Za-z0-9_]+)["'][^\}]{0,180}?queryId\s*:\s*["']([A-Za-z0-9_-]{18,36})["']"""
        )
        private val SLASH_OP_PATTERN = Pattern.compile(
            """["']([A-Za-z0-9_-]{18,36})/([A-Za-z0-9_]+)["']"""
        )
        private val DICT_OP_PATTERN = Pattern.compile(
            """["']([A-Za-z0-9_]+)["']\s*:\s*["']([A-Za-z0-9_-]{18,36})["']"""
        )
        private val FEATURE_SWITCH_ITEM_REGEX = Regex("""["']([a-zA-Z0-9_]+)["']""")

        val DEFAULT_QUERY_IDS: Map<String, String> = mapOf(
            "HomeTimeline" to "c-CzHF1LboFilMpsx4ZCrQ",
            "HomeLatestTimeline" to "BKB7oi212Fi7kQtCBGE4zA",
            "UserByScreenName" to "Yka-W8dz7RaEuQNkroPkYw",
            "UserByRestId" to "tDla4-2g1m0xK0p9L8Q3Xw",
            "UserTweets" to "E3opIU6Wqmg7NSz0fA4Cqw",
            "UserTweetsAndReplies" to "bt4IlYdq4EfNMyyWzEIbAQ",
            "UserMedia" to "2tLOJWwGuSzYIYIHRPzVpQ",
            "Likes" to "aeJwz_-kknVBOl4wQ7I_WA",
            "TweetDetail" to "nBS-WpgA6ZG0CyNHD517Sg",
            "SearchTimeline" to "BYppgupFFS2yqVmmRNjlsw",
            "CreateTweet" to "SoVnbfCycZ7fERGCwpZkYA",
            "DeleteTweet" to "VaenaVgh5q5ih7kvyVjgtg",
            "FavoriteTweet" to "lI07N6Otwv1PhnEgXILM7A",
            "UnfavoriteTweet" to "ZYKSe-w7KEslx3JhSIk5LA",
            "CreateRetweet" to "ojPdsZsimiJrUGLR1sjUtA",
            "DeleteRetweet" to "iQtK4dl5hBmXewYZuEOKVw",
            "CreateBookmark" to "aoDbu3RHznuiSkQ9aNM67Q",
            "DeleteBookmark" to "Wlmlj2-xzyS1GN3a6cj-mQ",
            "Bookmarks" to "uNowfj04D8PXQaefV6u3gA",
            "UserArticlesTweets" to "4l7g7lT1X8kH3jM9nQ2wZg",
            "ExploreSidebar" to "L2v5P2uW1f9Q8hN3cK7xZg",
            "ExplorePage" to "khe8_4nS6pM2vL9qR5tYxA",
            "Viewer" to "W62Nn4mk-zcwedHBZnpmpQ",
        )

        fun defaultFeaturesFor(operationName: String): String {
            return when (operationName) {
                "UserByScreenName", "UserByRestId", "Viewer" -> USER_FEATURES_JSON
                else -> TIMELINE_FEATURES_JSON
            }
        }

        fun fieldTogglesFor(operationName: String): String? = when (operationName) {
            "UserByScreenName" -> """{"withAuxiliaryUserLabels":false}"""
            "TweetDetail" -> """{"withArticleRichContentState":true,"withArticlePlainText":true,"withGrokAnalyze":false,"withDisallowedReplyControls":false}"""
            "UserArticlesTweets" -> """{"withArticleRichContentState":true,"withArticlePlainText":true}"""
            "UserTweets", "UserTweetsAndReplies", "UserMedia", "Likes" -> """{"withArticlePlainText":true}"""
            else -> null
        }

        const val TIMELINE_FEATURES_JSON =
            """{"rweb_video_screen_enabled":false,"payments_enabled":false,"profile_label_improvements_pcf_label_in_post_enabled":true,"rweb_tipjar_consumption_enabled":true,"verified_phone_label_enabled":false,"creator_subscriptions_tweet_preview_api_enabled":true,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"responsive_web_graphql_exclude_directive_enabled":true,"premium_content_api_read_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"responsive_web_grok_analyze_button_fetch_trends_enabled":false,"responsive_web_grok_analyze_post_followups_enabled":true,"responsive_web_jetfuel_frame":false,"responsive_web_grok_share_attachment_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"tweet_awards_web_tipping_enabled":false,"responsive_web_grok_show_grok_translated_post":false,"responsive_web_grok_analysis_button_from_backend":true,"creator_subscriptions_quote_tweet_preview_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"rweb_video_timestamps_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"responsive_web_grok_image_annotation_enabled":true,"responsive_web_grok_community_note_auto_translation_is_enabled":false,"responsive_web_enhance_cards_enabled":false,"rweb_lists_timeline_redesign_enabled":true}"""

        const val USER_FEATURES_JSON =
            """{"hidden_profile_subscriptions_enabled":true,"payments_enabled":false,"profile_label_improvements_pcf_label_in_post_enabled":true,"rweb_tipjar_consumption_enabled":true,"responsive_web_graphql_exclude_directive_enabled":true,"verified_phone_label_enabled":false,"subscriptions_verification_info_is_identity_verified_enabled":true,"subscriptions_verification_info_verified_since_enabled":true,"highlights_tweets_tab_enabled":true,"responsive_web_twitter_article_notes_tab_enabled":true,"subscriptions_feature_can_gift_premium":false,"creator_subscriptions_tweet_preview_api_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_profile_redirect_enabled":false}"""
    }
}
