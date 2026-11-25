package com.example.data.repository

import com.example.data.local.IlayDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.local.RecentSearchEntity
import com.example.data.remote.BoundedTtlCache
import com.example.data.remote.PublicFallbackClient
import com.example.data.remote.RequestCoalescer
import com.example.data.remote.XEndpointRegistry
import com.example.data.remote.XGraphqlClient
import com.example.domain.model.SearchTab
import com.example.domain.model.TimelinePage
import com.example.domain.model.TrendItem
import com.example.domain.model.TrendsResult
import com.example.domain.model.Tweet
import com.example.domain.model.UniversalSearchSuggestions
import com.example.domain.model.XUser
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Dedicated Universal Search Repository for Ilay for X.
 * Performance & responsiveness features:
 * - Shared KSP-generated Moshi JsonAdapter (zero reflection)
 * - Cancellation-safe in-flight request coalescing (concurrent identical queries share one HTTP call)
 * - Bounded LRU TTL caches + instant cache peek (`peekCachedSearch`)
 * - 20-second cached local Room tweet snapshot (avoids full SQLite JSON deserialization per keystroke)
 * - Queries global tweet & #hashtag content across X SearchTimeline, adaptive search,
 *   universal typeahead (`result_type=events,users,topics`), Guest/Syndication, and cached posts.
 */
class XSearchRepository(
    private val db: IlayDatabase,
    private val preferencesRepository: PreferencesRepository,
    private val graphqlClient: XGraphqlClient,
    private val publicClient: PublicFallbackClient,
    private val endpointRegistry: XEndpointRegistry? = null,
    private val tweetAdapter: JsonAdapter<Tweet>,
    private val trendsResultAdapter: JsonAdapter<TrendsResult>? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val postsCache = BoundedTtlCache<String, TimelinePage>(MAX_CACHE_ENTRIES, POSTS_CACHE_TTL_MS)
    private val peopleCache = BoundedTtlCache<String, List<XUser>>(MAX_CACHE_ENTRIES, PEOPLE_CACHE_TTL_MS)
    private val suggestionsCache = BoundedTtlCache<String, UniversalSearchSuggestions>(MAX_CACHE_ENTRIES, SUGGESTIONS_CACHE_TTL_MS)
    private val trendsCache = BoundedTtlCache<String, TrendsResult>(2, TRENDS_MAX_AGE_MS)
    private val localTweetsCache = BoundedTtlCache<String, List<Tweet>>(2, LOCAL_SNAPSHOT_TTL_MS)

    private val postSearchCoalescer = RequestCoalescer<String, Result<TimelinePage>>()
    private val peopleSearchCoalescer = RequestCoalescer<String, Result<List<XUser>>>()
    private val suggestionsCoalescer = RequestCoalescer<String, UniversalSearchSuggestions?>()
    private val trendsCoalescer = RequestCoalescer<String, Result<TrendsResult>>()

    val recentSearchesFlow: Flow<List<RecentSearchEntity>> = db.recentSearchDao().observeRecent()

    fun clearCaches() {
        postsCache.clear()
        peopleCache.clear()
        suggestionsCache.clear()
        trendsCache.clear()
        localTweetsCache.clear()
    }

    /**
     * Instant zero-network lookup for warm search cache hits so UI renders immediately on tab switch
     * or repeat queries without flashing a spinner.
     */
    fun peekCachedSearch(query: String, tab: SearchTab): TimelinePage? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return null
        val cacheKey = "${tab.name}_${trimmed.lowercase()}_"
        return postsCache.get(cacheKey)
    }

    fun peekCachedPeople(query: String): List<XUser>? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return null
        return peopleCache.get(trimmed.lowercase())
    }

    /**
     * Queries universal global tweet & #hashtag content with in-flight request coalescing.
     */
    suspend fun searchGlobalTweets(
        query: String,
        tab: SearchTab,
        cursor: String? = null,
        recordHistory: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<TimelinePage> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return Result.success(TimelinePage(emptyList()))
        }

        if (recordHistory) {
            withContext(Dispatchers.IO) {
                db.recentSearchDao().upsert(RecentSearchEntity(trimmed))
            }
        }

        val cacheKey = "${tab.name}_${trimmed.lowercase()}_${cursor.orEmpty()}"
        if (cursor == null && !forceRefresh) {
            postsCache.get(cacheKey)?.let { return Result.success(it) }
        }
        val coalesceKey = if (forceRefresh) "${cacheKey}_force" else cacheKey

        return postSearchCoalescer.coalesce(coalesceKey) {
            withContext(Dispatchers.IO) {
                executeGlobalTweetSearch(trimmed, tab, cursor, cacheKey)
            }
        }
    }

    private suspend fun executeGlobalTweetSearch(
        trimmed: String,
        tab: SearchTab,
        cursor: String?,
        cacheKey: String,
    ): Result<TimelinePage> {
        // Direct post URL resolution (only when user explicitly pastes a full status link)
        val statusIdMatch = STATUS_URL_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
        val prefs = preferencesRepository.preferencesFlow.first()
        if (statusIdMatch != null) {
            return publicClient.fetchPublicTweet("i", statusIdMatch, prefs.customProxyBaseUrl).map { tweet ->
                TimelinePage(
                    tweets = listOf(tweet),
                    isFromPublicFallback = true,
                    sourceInfo = "Direct Status Lookup",
                )
            }
        }

        val account = db.accountDao().getActiveAccount()

        // 1. Authenticated universal tweet & #hashtag search (GraphQL SearchTimeline -> adaptive.json -> HTML)
        if (account != null) {
            val res = graphqlClient.searchTimeline(
                account = account,
                query = trimmed,
                tab = tab,
                cursor = cursor,
            )
            if (res.isSuccess && res.getOrNull()?.tweets.orEmpty().isNotEmpty()) {
                val page = res.getOrThrow()
                if (cursor == null) {
                    postsCache.put(cacheKey, page)
                }
                return Result.success(page)
            }
        }

        // 2. Unified Nitter fallback search (default https://nitter.netbub.com or custom Settings URL)
        val publicGlobal = publicClient.searchPublicGlobalTweets(
            query = trimmed,
            tab = tab,
            customProxyBaseUrl = prefs.customProxyBaseUrl,
            cursor = cursor,
        )
        if (publicGlobal.isSuccess && publicGlobal.getOrNull()?.tweets.orEmpty().isNotEmpty()) {
            val page = publicGlobal.getOrThrow()
            if (cursor == null) {
                postsCache.put(cacheKey, page)
            }
            return Result.success(page)
        }

        // 3. Offline / Guest / Fallback: filter cached tweet CONTENT (uses 20s in-memory snapshot)
        if (cursor == null) {
            val localMatches = searchCachedTweetContent(trimmed, tab)
            if (localMatches.isNotEmpty()) {
                return Result.success(
                    TimelinePage(
                        tweets = localMatches,
                        isFromPublicFallback = true,
                        sourceInfo = if (account == null) {
                            "Cached Content & #Hashtag Matches · Sign in for full global X search"
                        } else {
                            "Cached Content Matches (X search temporarily throttled)"
                        },
                    )
                )
            }
        }

        val message = if (account == null) {
            "No cached posts match \"$trimmed\". Sign in to X to search global live posts by keyword or #hashtag."
        } else {
            "No global posts found matching \"$trimmed\"."
        }
        return Result.failure(IllegalStateException(message))
    }

    /**
     * Fetches universal search suggestions (matching #hashtags, trending topics, and accounts)
     * from X's universal typeahead (`result_type=events,users,topics`) with in-flight coalescing.
     * Designed to run IN PARALLEL with `searchGlobalTweets` so chips appear immediately.
     */
    suspend fun searchUniversalSuggestions(
        query: String,
        recentTweets: List<Tweet> = emptyList(),
    ): UniversalSearchSuggestions = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.startsWith("quoted_tweet_id:", ignoreCase = true)) {
            return@withContext UniversalSearchSuggestions()
        }

        val cacheKey = trimmed.lowercase()
        val remoteSuggestions = fetchCoalescedTypeahead(cacheKey, trimmed)
        val localPool = if (recentTweets.isEmpty() && remoteSuggestions?.hashtags.orEmpty().size < 4) {
            getCachedTweetsSnapshot()
        } else {
            emptyList()
        }

        buildSuggestionsFromSources(
            trimmed = trimmed,
            remoteSuggestions = remoteSuggestions,
            tweets = recentTweets,
            localFallbackTweets = localPool,
        )
    }

    /**
     * Pure in-memory helper to enrich already-fetched typeahead suggestions with #hashtags
     * discovered inside newly returned tweets — zero network or SQLite overhead.
     */
    fun mergeTweetHashtags(
        query: String,
        base: UniversalSearchSuggestions,
        tweets: List<Tweet>,
    ): UniversalSearchSuggestions {
        val trimmed = query.trim()
        if (trimmed.isBlank() || tweets.isEmpty()) return base
        return buildSuggestionsFromSources(
            trimmed = trimmed,
            remoteSuggestions = base,
            tweets = tweets,
            localFallbackTweets = emptyList(),
        )
    }

    private suspend fun fetchCoalescedTypeahead(
        cacheKey: String,
        trimmed: String,
    ): UniversalSearchSuggestions? {
        suggestionsCache.get(cacheKey)?.let { return it }

        return suggestionsCoalescer.coalesce(cacheKey) {
            val account = db.accountDao().getActiveAccount()
            if (account != null) {
                graphqlClient.fetchUniversalTypeahead(account, trimmed).getOrNull()?.also {
                    suggestionsCache.put(cacheKey, it)
                }
            } else {
                null
            }
        }
    }

    private fun buildSuggestionsFromSources(
        trimmed: String,
        remoteSuggestions: UniversalSearchSuggestions?,
        tweets: List<Tweet>,
        localFallbackTweets: List<Tweet>,
    ): UniversalSearchSuggestions {
        val hashtagCounts = LinkedHashMap<String, Int>()
        val bareQuery = trimmed.removePrefix("#").removePrefix("@").lowercase()

        remoteSuggestions?.hashtags.orEmpty().forEach { tag ->
            val normalized = if (tag.startsWith("#")) tag else "#$tag"
            hashtagCounts[normalized] = (hashtagCounts[normalized] ?: 0) + 10
        }

        tweets.forEach { tweet ->
            HASHTAG_REGEX.findAll(tweet.text).forEach { match ->
                val tag = match.value
                val bonus = if (tag.lowercase().contains(bareQuery)) 4 else 1
                hashtagCounts[tag] = (hashtagCounts[tag] ?: 0) + bonus
            }
            tweet.quotedTweet?.let { qt ->
                HASHTAG_REGEX.findAll(qt.text).forEach { match ->
                    val tag = match.value
                    hashtagCounts[tag] = (hashtagCounts[tag] ?: 0) + 1
                }
            }
        }

        if (hashtagCounts.size < 4 && bareQuery.length >= 2 && localFallbackTweets.isNotEmpty()) {
            localFallbackTweets.forEach { tweet ->
                HASHTAG_REGEX.findAll(tweet.text).forEach { match ->
                    val tag = match.value
                    if (tag.lowercase().contains(bareQuery)) {
                        hashtagCounts[tag] = (hashtagCounts[tag] ?: 0) + 2
                    }
                }
            }
        }

        val sortedHashtags = hashtagCounts.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .distinctBy { it.lowercase() }
            .toMutableList()

        val compactTagCandidate = bareQuery.replace(NON_TAG_CHARS_REGEX, "")
        if (!trimmed.startsWith("#") &&
            compactTagCandidate.length in 2..28 &&
            !trimmed.contains(" ") &&
            sortedHashtags.none { it.equals("#$compactTagCandidate", ignoreCase = true) }
        ) {
            sortedHashtags.add(0, "#${trimmed.removePrefix("@")}")
        }

        val topics = remoteSuggestions?.topics.orEmpty()
            .filter { it.isNotBlank() && !it.equals(trimmed, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(5)

        return UniversalSearchSuggestions(
            hashtags = sortedHashtags.take(8),
            topics = topics,
            users = remoteSuggestions?.users.orEmpty(),
        )
    }

    /**
     * Fuzzy people search with in-flight request coalescing.
     */
    suspend fun searchPeople(
        query: String,
        recordHistory: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<List<XUser>> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return Result.success(emptyList())

        if (recordHistory) {
            withContext(Dispatchers.IO) {
                db.recentSearchDao().upsert(RecentSearchEntity(trimmed))
            }
        }

        val cacheKey = trimmed.lowercase()
        if (!forceRefresh) {
            peopleCache.get(cacheKey)?.let { return Result.success(it) }
        }
        val coalesceKey = if (forceRefresh) "${cacheKey}_force" else cacheKey

        return peopleSearchCoalescer.coalesce(coalesceKey) {
            withContext(Dispatchers.IO) {
                executePeopleSearch(trimmed, cacheKey)
            }
        }
    }

    private suspend fun executePeopleSearch(
        trimmed: String,
        cacheKey: String,
    ): Result<List<XUser>> {
        val account = db.accountDao().getActiveAccount()
        if (account != null) {
            val res = graphqlClient.searchPeople(account, trimmed)
            if (res.isSuccess) {
                val baseUsers = res.getOrNull().orEmpty()
                val enriched = enrichMissingPeopleMetrics(baseUsers)
                peopleCache.put(cacheKey, enriched)
                return Result.success(enriched)
            }
            return res
        }

        val prefs = preferencesRepository.preferencesFlow.first()
        val nitterUsers = publicClient.searchPublicUsers(trimmed, prefs.customProxyBaseUrl)
            .getOrNull()
            .orEmpty()
        if (nitterUsers.isNotEmpty()) {
            peopleCache.put(cacheKey, nitterUsers)
            return Result.success(nitterUsers)
        }

        val tokens = trimmed.removePrefix("@").lowercase().split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        val cachedAuthors = getCachedTweetsSnapshot()
            .map { it.author }
            .distinctBy { it.handle.lowercase() }
            .filter { user ->
                val searchable = "${user.handle} ${user.displayName} ${user.bio}".lowercase()
                tokens.all { token -> searchable.contains(token) }
            }

        return if (cachedAuthors.isNotEmpty()) {
            Result.success(enrichMissingPeopleMetrics(cachedAuthors, prefs.customProxyBaseUrl))
        } else {
            val directHandle = trimmed.removePrefix("@")
            if (directHandle.matches(Regex("[A-Za-z0-9_]{1,15}"))) {
                val pub = publicClient.fetchPublicProfile(directHandle, prefs.customProxyBaseUrl).getOrNull()
                if (pub != null) {
                    peopleCache.put(cacheKey, listOf(pub))
                    return Result.success(listOf(pub))
                }
            }
            Result.failure(IllegalStateException("No people found matching \"$trimmed\"."))
        }
    }

    private suspend fun enrichMissingPeopleMetrics(
        users: List<XUser>,
        customProxyBaseUrl: String = "",
    ): List<XUser> = coroutineScope {
        val needsEnrichment = users
            .filter { it.followersCount == 0L && it.followingCount == 0L && it.tweetsCount == 0L }
            .take(6)
        if (needsEnrichment.isEmpty()) return@coroutineScope users

        val fetchedByHandle = needsEnrichment.map { candidate ->
            async(Dispatchers.IO) {
                publicClient.fetchPublicProfile(candidate.handle, customProxyBaseUrl).getOrNull()
            }
        }.awaitAll().filterNotNull().associateBy { it.handle.lowercase() }

        if (fetchedByHandle.isEmpty()) return@coroutineScope users
        users.map { user ->
            val rich = fetchedByHandle[user.handle.lowercase()] ?: return@map user
            user.copy(
                id = user.id.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: rich.id.ifBlank { user.id },
                displayName = user.displayName.ifBlank { rich.displayName },
                avatarUrl = user.avatarUrl.ifBlank { rich.avatarUrl },
                bannerUrl = user.bannerUrl.ifBlank { rich.bannerUrl },
                bio = user.bio.ifBlank { rich.bio },
                isVerified = user.isVerified || rich.isVerified,
                followersCount = maxOf(user.followersCount, rich.followersCount),
                followingCount = maxOf(user.followingCount, rich.followingCount),
                tweetsCount = maxOf(user.tweetsCount, rich.tweetsCount),
            )
        }
    }

    /**
     * Fetches trends with an explicit 60-minute timestamp freshness check across both
     * in-memory and persisted trend caches. If cached data is older than [TRENDS_MAX_AGE_MS]
     * (60 minutes) or [forceRefresh] is requested, evicts stale entries and forces a live
     * network refresh (`CacheControl.FORCE_NETWORK`).
     */
    suspend fun fetchTrends(forceRefresh: Boolean = false): Result<TrendsResult> {
        val checkNow = nowProvider()
        var staleDetected = false

        if (!forceRefresh) {
            val cached = resolveCachedTrends()
            if (cached != null && !cached.isStale(nowEpochMs = checkNow, maxAgeMs = TRENDS_MAX_AGE_MS)) {
                trendsCache.put(TRENDS_CACHE_KEY, cached)
                return Result.success(cached)
            }
            if (cached != null) {
                staleDetected = true
                trendsCache.remove(TRENDS_CACHE_KEY)
            }
        } else {
            trendsCache.remove(TRENDS_CACHE_KEY)
            localTweetsCache.remove(SNAPSHOT_CACHE_KEY)
        }

        val shouldForceNetwork = forceRefresh || staleDetected
        val coalesceKey = if (shouldForceNetwork) "${TRENDS_CACHE_KEY}_force" else TRENDS_CACHE_KEY
        return trendsCoalescer.coalesce(coalesceKey) {
            withContext(Dispatchers.IO) {
                val now = nowProvider()
                val account = db.accountDao().getActiveAccount()

                // 1. Live trends directly from X servers (GraphQL ExploreSidebar/ExplorePage -> guide.json -> REST trends)
                if (account != null) {
                    val res = graphqlClient.fetchTrends(account, forceNetwork = shouldForceNetwork)
                    val items = res.getOrNull().orEmpty()
                    if (items.isNotEmpty()) {
                        val result = TrendsResult(
                            items = items,
                            sourceLabel = "Live from X",
                            fetchedAtEpochMs = now,
                        )
                        cacheAndPersistTrends(result)
                        return@withContext Result.success(result)
                    }
                }

                // 2. Dynamic real-time topic extraction from the user's freshest cached timeline posts
                //    (never falls back to static hardcoded fake trends; kept in memory only)
                val derived = extractLiveTrendsFromTimeline(getCachedTweetsSnapshot(), now)
                if (derived.isNotEmpty()) {
                    val result = TrendsResult(
                        items = derived,
                        sourceLabel = "Rising in your timeline",
                        fetchedAtEpochMs = now,
                    )
                    trendsCache.put(TRENDS_CACHE_KEY, result)
                    return@withContext Result.success(result)
                }

                Result.failure(
                    IllegalStateException(
                        if (account == null) {
                            "Sign in to X or pull to refresh to load live trends."
                        } else {
                            "X trends are temporarily unavailable. Pull down to retry."
                        }
                    )
                )
            }
        }
    }

    private suspend fun resolveCachedTrends(): TrendsResult? {
        trendsCache.get(TRENDS_CACHE_KEY)?.let { return it }
        val adapter = trendsResultAdapter ?: return null
        val (json, savedTimestampMs) = runCatching {
            withContext(Dispatchers.IO) { preferencesRepository.getCachedTrendsSnapshot() }
        }.getOrNull() ?: return null
        if (json.isNullOrBlank()) return null
        val parsed = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
        val effectiveTimestamp = when {
            savedTimestampMs > 0L -> savedTimestampMs
            parsed.fetchedAtEpochMs > 0L -> parsed.fetchedAtEpochMs
            else -> 0L
        }
        return parsed.copy(fetchedAtEpochMs = effectiveTimestamp)
    }

    private suspend fun cacheAndPersistTrends(result: TrendsResult) {
        trendsCache.put(TRENDS_CACHE_KEY, result)
        val adapter = trendsResultAdapter ?: return
        runCatching {
            val json = adapter.toJson(result)
            preferencesRepository.saveCachedTrendsSnapshot(json, result.fetchedAtEpochMs)
        }
    }

    private fun extractLiveTrendsFromTimeline(tweets: List<Tweet>, nowEpochMs: Long): List<TrendItem> {
        if (tweets.isEmpty()) return emptyList()
        data class TopicBucket(
            var displayLabel: String,
            var score: Double = 0.0,
            var mentions: Int = 0,
            var totalLikes: Long = 0L,
            var isHashtag: Boolean = false,
        )
        val buckets = LinkedHashMap<String, TopicBucket>()

        for (tweet in tweets.take(150)) {
            val ageHours = ((nowEpochMs - tweet.createdAtEpochMs).coerceAtLeast(0L)) / 3_600_000.0
            val recencyWeight = kotlin.math.exp(-ageHours / 18.0)
            val engagementWeight = 1.0 + kotlin.math.ln(1.0 + (tweet.metrics.likes + tweet.metrics.reposts * 2L).coerceAtLeast(0L)) * 0.45
            val tweetWeight = recencyWeight * engagementWeight

            val seenInTweet = HashSet<String>()
            HASHTAG_REGEX.findAll(tweet.text).forEach { m ->
                val tag = m.value
                val key = tag.lowercase()
                if (seenInTweet.add(key)) {
                    val b = buckets.getOrPut(key) { TopicBucket(displayLabel = tag, isHashtag = true) }
                    b.score += tweetWeight * 1.6
                    b.mentions += 1
                    b.totalLikes += tweet.metrics.likes
                }
            }

            // Also detect multi-mention capitalized topics / proper nouns when hashtags are sparse
            PROPER_TOPIC_REGEX.findAll(tweet.text).forEach { m ->
                val topic = m.value.trim()
                val key = topic.lowercase()
                if (key !in TREND_STOPWORDS && topic.length in 3..26 && seenInTweet.add(key)) {
                    val b = buckets.getOrPut(key) { TopicBucket(displayLabel = topic, isHashtag = false) }
                    b.score += tweetWeight * 0.9
                    b.mentions += 1
                    b.totalLikes += tweet.metrics.likes
                }
            }
        }

        return buckets.values
            .filter { it.isHashtag || it.mentions >= 2 }
            .sortedByDescending { it.score }
            .take(12)
            .map { bucket ->
                val postWord = if (bucket.mentions == 1) "post" else "posts"
                TrendItem(
                    id = "live_${bucket.displayLabel.lowercase().replace(NON_TAG_CHARS_REGEX, "_")}",
                    name = bucket.displayLabel,
                    category = if (bucket.isHashtag) "Hashtag · Your Timeline" else "Topic · Your Timeline",
                    tweetVolumeLabel = "${bucket.mentions} recent $postWord",
                    query = bucket.displayLabel,
                )
            }
    }

    suspend fun deleteRecentSearch(query: String) = withContext(Dispatchers.IO) {
        db.recentSearchDao().delete(query)
    }

    suspend fun clearRecentSearches() = withContext(Dispatchers.IO) {
        db.recentSearchDao().clearAll()
    }

    /**
     * Caches deserialized Room tweets in memory for 20s to avoid full-table Moshi deserialization
     * on every debounced keystroke or fallback search.
     */
    private suspend fun getCachedTweetsSnapshot(): List<Tweet> {
        localTweetsCache.get(SNAPSHOT_CACHE_KEY)?.let { return it }
        val parsed = db.tweetCacheDao().getAllCachedTweets()
            .mapNotNull { runCatching { tweetAdapter.fromJson(it.tweetJson) }.getOrNull() }
            .distinctBy { it.id }
        localTweetsCache.put(SNAPSHOT_CACHE_KEY, parsed)
        return parsed
    }

    private suspend fun searchCachedTweetContent(query: String, tab: SearchTab): List<Tweet> {
        val rawTokens = query.lowercase()
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() && !it.startsWith("filter:") }
        val fromHandle = rawTokens
            .firstOrNull { it.startsWith("from:") }
            ?.removePrefix("from:")
            ?.removePrefix("@")
            ?.trim()
            .orEmpty()
        val tokens = rawTokens.filterNot { it.startsWith("from:") }
        if (tokens.isEmpty() && fromHandle.isBlank()) return emptyList()
        return getCachedTweetsSnapshot()
            .filter { tweet ->
                if (fromHandle.isNotBlank() && !tweet.author.handle.equals(fromHandle, ignoreCase = true)) {
                    return@filter false
                }
                if (tokens.isEmpty()) return@filter true
                val content = buildString {
                    append(tweet.text)
                    tweet.quotedTweet?.let {
                        append(' ')
                        append(it.text)
                    }
                    tweet.linkPreview?.let {
                        append(' ')
                        append(it.title)
                        append(' ')
                        append(it.description)
                        append(' ')
                        append(it.domain)
                    }
                    tweet.article?.let {
                        append(' ')
                        append(it.title)
                        append(' ')
                        append(it.previewText)
                    }
                }.lowercase()
                tokens.all { token ->
                    val bare = token.removePrefix("#")
                    content.contains(token) ||
                        (bare.isNotBlank() && (content.contains("#$bare") || content.contains(bare)))
                }
            }
            .let { list -> if (tab == SearchTab.MEDIA) list.filter { it.media.isNotEmpty() } else list }
            .let { list ->
                if (tab == SearchTab.LATEST) {
                    list.sortedByDescending { it.createdAtEpochMs }
                } else {
                    list.sortedByDescending { it.metrics.likes + it.metrics.reposts * 2L }
                }
            }
    }

    companion object {
        private const val POSTS_CACHE_TTL_MS = 90_000L
        private const val PEOPLE_CACHE_TTL_MS = 120_000L
        private const val SUGGESTIONS_CACHE_TTL_MS = 120_000L
        const val TRENDS_MAX_AGE_MS = TrendsResult.MAX_CACHE_AGE_MS
        private const val LOCAL_SNAPSHOT_TTL_MS = 20_000L
        private const val MAX_CACHE_ENTRIES = 60
        private const val TRENDS_CACHE_KEY = "trends"
        private const val SNAPSHOT_CACHE_KEY = "snapshot"

        private val HASHTAG_REGEX = Regex("#[A-Za-z0-9_]{2,}")
        private val PROPER_TOPIC_REGEX = Regex("""\b[A-Z][A-Za-z0-9]{2,}(?:\s+[A-Z][A-Za-z0-9]{2,})?\b""")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NON_TAG_CHARS_REGEX = Regex("[^a-z0-9_]")
        private val STATUS_URL_REGEX = Regex("(?i)(?:x\\.com|twitter\\.com)/[^/]+/status/(\\d+)")
        private val TREND_STOPWORDS = setOf(
            "the", "this", "that", "they", "them", "there", "these", "those", "here", "what",
            "when", "where", "which", "while", "with", "from", "just", "have", "been", "will",
            "would", "could", "should", "about", "after", "before", "breaking", "update", "today",
            "tomorrow", "yesterday", "new", "now", "how", "why", "who", "you", "your", "our",
            "more", "some", "many", "very", "also", "still", "even", "only", "back", "over",
        )
    }
}
