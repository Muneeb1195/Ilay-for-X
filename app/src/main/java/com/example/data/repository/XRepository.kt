package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.CachedProfileEntity
import com.example.data.local.CachedTweetEntity
import com.example.data.local.DraftTweetEntity
import com.example.data.local.EndpointHashEntity
import com.example.data.local.IlayDatabase
import com.example.data.local.MutedRuleEntity
import com.example.data.local.PreferencesRepository
import com.example.data.local.RecentSearchEntity
import com.example.data.local.TopicInterestEntity
import com.example.data.local.UserModerationEntity
import com.example.data.remote.BoundedTtlCache
import com.example.data.remote.PublicFallbackClient
import com.example.data.remote.RequestCoalescer
import com.example.data.remote.TranslationResult
import com.example.data.remote.XApiException
import com.example.data.remote.XEndpointRegistry
import com.example.data.remote.XGraphqlClient
import com.example.data.remote.XPollParser
import com.example.domain.model.FeedType
import com.example.domain.model.NotificationItem
import com.example.domain.model.Poll
import com.example.domain.model.ProfileTab
import com.example.domain.model.QuotedTweet
import com.example.domain.model.SearchTab
import com.example.domain.model.ThreadConversation
import com.example.domain.model.TimelinePage
import com.example.domain.model.TrendsResult
import com.example.domain.model.Tweet
import com.example.domain.model.UniversalSearchSuggestions
import com.example.domain.model.XUser
import com.example.domain.ranking.FeedbackUndoToken
import com.example.domain.ranking.LocalForYouRanker
import com.example.domain.ranking.RankingSignalStore
import com.example.domain.ranking.TweetImpressionEvent
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class BookmarkSyncPage(
    val syncedCount: Int,
    val hasMore: Boolean,
)

enum class TimelineRefreshSource {
    USER_PULL,
    FOREGROUND,
    BACKGROUND_WORKER,
}

data class TimelineSyncEvent(
    val feedType: FeedType,
    val page: TimelinePage,
    val source: TimelineRefreshSource,
    val syncedAtEpochMs: Long = System.currentTimeMillis(),
)

data class PollVoteUpdate(
    val tweetId: String,
    val poll: Poll,
)

/**
 * Central Repository for Ilay for X. Coordinates reverse-engineered X GraphQL calls,
 * zero-auth public open-endpoint fallbacks, Room offline caches, in-memory TTL response
 * caching, multi-account sessions, native translation, and Ilay's on-device "For You" ranker.
 */
class XRepository(
    private val appContext: Context,
    private val db: IlayDatabase,
    val preferencesRepository: PreferencesRepository,
    private val graphqlClient: XGraphqlClient,
    private val publicClient: PublicFallbackClient,
    private val endpointRegistry: XEndpointRegistry,
    private val ranker: LocalForYouRanker,
    private val tweetAdapter: JsonAdapter<Tweet>,
    private val userAdapter: JsonAdapter<XUser>,
    val searchRepository: XSearchRepository,
    val bookmarkRepository: BookmarkRepository,
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Bounded LRU TTL caches & cancellation-safe in-flight coalescers
    private val profileCache = BoundedTtlCache<String, XUser>(maxEntries = 100, defaultTtlMs = 120_000L)
    private val profileTabCache = BoundedTtlCache<String, List<Tweet>>(maxEntries = 40, defaultTtlMs = 60_000L)
    private val threadCache = BoundedTtlCache<String, ThreadConversation>(maxEntries = 60, defaultTtlMs = 90_000L)
    private val seededThreadTweets = BoundedTtlCache<String, Tweet>(maxEntries = 30, defaultTtlMs = 5 * 60_000L)
    private val translationCache = BoundedTtlCache<String, TranslationResult>(maxEntries = 150, defaultTtlMs = 30 * 60_000L)
    private val notificationsCache = BoundedTtlCache<Boolean, List<NotificationItem>>(maxEntries = 4, defaultTtlMs = 60_000L)
    private val timelineCoalescer = RequestCoalescer<String, Result<TimelinePage>>()
    private val profileCoalescer = RequestCoalescer<String, Result<XUser>>()
    private val profileTabCoalescer = RequestCoalescer<String, Result<List<Tweet>>>()
    private val threadCoalescer = RequestCoalescer<String, Result<ThreadConversation>>()
    private val notificationsCoalescer = RequestCoalescer<Boolean, Result<List<NotificationItem>>>()
    private val staleRegistryChecked = AtomicBoolean(false)
    private val foregroundFeedSettled = CompletableDeferred<Unit>()
    @Volatile
    private var lastForYouSuccessAtMs: Long = 0L
    @Volatile
    private var lastFollowingFetchAtMs: Long = 0L
    @Volatile
    private var lastTopicBlendAtMs: Long = 0L
    @Volatile
    private var topicBlendRotationIndex: Int = 0
    private val bookmarksSyncMutex = Mutex()
    @Volatile
    private var bookmarksNextCursor: String? = null

    val rankingSignals = RankingSignalStore(db)

    /** Signals that the cold-start foreground feed attempt has completed so background prefetch may start. */
    fun markForegroundFeedSettled() {
        foregroundFeedSettled.complete(Unit)
    }

    /** Waits for cold-start foreground UI load to finish (or [maxWaitMs]) so StartupPrefetchWorker never contends with UI. */
    suspend fun awaitForegroundReadyForPrefetch(maxWaitMs: Long = 5_000L) {
        withTimeoutOrNull(maxWaitMs) {
            foregroundFeedSettled.await()
        }
    }

    fun wasForYouRefreshedRecently(withinMs: Long = 30_000L): Boolean =
        (System.currentTimeMillis() - lastForYouSuccessAtMs) in 0L until withinMs

    private val _timelineSyncEvents = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = 4)
    val timelineSyncEvents: SharedFlow<TimelineSyncEvent> = _timelineSyncEvents.asSharedFlow()

    private val _pollUpdates = MutableSharedFlow<PollVoteUpdate>(extraBufferCapacity = 8)
    val pollUpdates: SharedFlow<PollVoteUpdate> = _pollUpdates.asSharedFlow()

    val activeAccountFlow: Flow<AccountEntity?> = db.accountDao().observeActiveAccount()
    val allAccountsFlow: Flow<List<AccountEntity>> = db.accountDao().observeAccounts()
    val preferencesFlow: Flow<AppPreferences> = preferencesRepository.preferencesFlow
    val mutedRulesFlow: Flow<List<MutedRuleEntity>> = db.mutedRuleDao().observeAll()
    val userModerationsFlow: Flow<List<UserModerationEntity>> = db.userModerationDao().observeAll()
    val affinitiesFlow: Flow<List<AuthorAffinityEntity>> = db.affinityDao().observeAll()
    val topicInterestsFlow: Flow<List<TopicInterestEntity>> = rankingSignals.topicInterestsFlow
    val seenCountFlow: Flow<Int> = rankingSignals.seenCountFlow
    val dismissedCountFlow: Flow<Int> = rankingSignals.dismissedCountFlow
    val riskySuppressedCountFlow: Flow<Int> = rankingSignals.riskySuppressedCountFlow
    val draftsFlow: Flow<List<DraftTweetEntity>> = db.draftDao().observeDrafts()
    val recentSearchesFlow: Flow<List<RecentSearchEntity>> = searchRepository.recentSearchesFlow
    val endpointDiagnosticsFlow: Flow<List<EndpointHashEntity>> = endpointRegistry.observeDiagnostics()

    private fun clearSessionCaches() {
        profileCache.clear()
        profileTabCache.clear()
        threadCache.clear()
        notificationsCache.clear()
        bookmarksNextCursor = null
        searchRepository.clearCaches()
        backgroundScope.launch {
            runCatching { preferencesRepository.clearCachedTrendsSnapshot() }
        }
    }

    fun observeBookmarks(query: String): Flow<List<Pair<BookmarkEntity, Tweet>>> =
        bookmarkRepository.observeBookmarks(query = query)

    suspend fun getCachedTimeline(feedType: FeedType): List<Tweet> = withContext(Dispatchers.IO) {
        val cached = db.tweetCacheDao().getFeedOnce(feedType.name)
            .mapNotNull { runCatching { tweetAdapter.fromJson(it.tweetJson) }.getOrNull() }
        applyBookmarkedFlagsAndRank(cached, feedType)
    }

    suspend fun refreshTimeline(
        feedType: FeedType,
        cursor: String? = null,
        forceNetwork: Boolean = false,
        source: TimelineRefreshSource = TimelineRefreshSource.FOREGROUND,
    ): Result<TimelinePage> {
        val coalesceKey = "${feedType.name}_${cursor.orEmpty()}"
        return timelineCoalescer.coalesce(coalesceKey) {
            withContext(Dispatchers.IO) {
                try {
                    val account = db.accountDao().getActiveAccount()
                    val prefs = preferencesFlow.first()
                    rankingSignals.ensureInitialized()

                    if (account != null && staleRegistryChecked.compareAndSet(false, true)) {
                        // Refresh stale hashes silently in the background; never block interactive timeline loads
                        endpointRegistry.scheduleBackgroundRefresh(force = false)
                    }

                    val sourceTags = HashMap<String, String>()
                    val rawPageResult: Result<TimelinePage> = if (account != null) {
                        val latestOnly = feedType == FeedType.FOLLOWING
                        val shouldBlendForYou = feedType == FeedType.FOR_YOU &&
                            cursor == null &&
                            prefs.blendMultiSourceForYou

                        if (shouldBlendForYou) {
                            coroutineScope {
                                val primaryDeferred = async {
                                    withTimeoutOrNull(HOME_TIMELINE_BUDGET_MS) {
                                        graphqlClient.fetchHomeTimeline(account, latestOnly = false, cursor = null)
                                    } ?: Result.failure(
                                        TimeoutException("X took too long to respond · Showing cached posts")
                                    )
                                }
                                val followingBlendDeferred = async {
                                    fetchFollowingBlendCandidates(account)
                                }
                                val topicBlendDeferred = async {
                                    fetchTopTopicBlendCandidates()
                                }

                                val primaryRes = primaryDeferred.await()
                                if (primaryRes.isSuccess && primaryRes.getOrNull()?.tweets.isNullOrEmpty()) {
                                    Result.failure(IllegalStateException("X returned no new posts right now · Showing cached posts"))
                                } else if (primaryRes.isSuccess) {
                                    val basePage = primaryRes.getOrThrow()
                                    val mergedById = LinkedHashMap<String, Tweet>(basePage.tweets.size + 40)
                                    basePage.tweets.forEach { t ->
                                        mergedById[t.id] = t
                                        sourceTags[t.id] = LocalForYouRanker.SOURCE_FOR_YOU
                                    }
                                    followingBlendDeferred.await().forEach { t ->
                                        if (t.id !in mergedById) {
                                            mergedById[t.id] = t
                                            sourceTags[t.id] = LocalForYouRanker.SOURCE_FOLLOWING
                                        }
                                    }
                                    val (topicLabel, topicTweets) = topicBlendDeferred.await()
                                    topicTweets.forEach { t ->
                                        if (t.id !in mergedById) {
                                            mergedById[t.id] = t
                                            sourceTags[t.id] = "${LocalForYouRanker.SOURCE_TOPIC_PREFIX}$topicLabel"
                                        }
                                    }
                                    Result.success(basePage.copy(tweets = mergedById.values.toList()))
                                } else {
                                    primaryRes
                                }
                            }
                        } else {
                            val gqlRes = withTimeoutOrNull(HOME_TIMELINE_BUDGET_MS) {
                                graphqlClient.fetchHomeTimeline(account, latestOnly = latestOnly, cursor = cursor)
                            } ?: Result.failure(
                                TimeoutException("X took too long to respond · Showing cached posts")
                            )
                            if (latestOnly && gqlRes.isSuccess) {
                                lastFollowingFetchAtMs = System.currentTimeMillis()
                            }
                            if (gqlRes.isSuccess && cursor == null && gqlRes.getOrNull()?.tweets.isNullOrEmpty()) {
                                Result.failure(IllegalStateException("X returned no new posts right now · Showing cached posts"))
                            } else {
                                gqlRes
                            }
                        }
                    } else {
                        val publicPage = withTimeoutOrNull(HOME_TIMELINE_BUDGET_MS) {
                            publicClient.fetchPublicDiscoveryFeed(
                                handles = prefs.publicPreviewHandles,
                                customProxyBaseUrl = prefs.customProxyBaseUrl,
                                forceRefresh = forceNetwork,
                            )
                        }
                        if (publicPage != null && publicPage.tweets.isNotEmpty()) {
                            Result.success(publicPage)
                        } else {
                            Result.failure(IllegalStateException("Unable to load public X timelines. Check your connection or sign in to X."))
                        }
                    }

                    rawPageResult.map { page ->
                        val ranked = applyBookmarkedFlagsAndRank(
                            tweets = page.tweets,
                            feedType = feedType,
                            prefsOverride = prefs,
                            sourceTags = sourceTags,
                        )
                        val finalPage = page.copy(tweets = ranked)
                        if (cursor == null && ranked.isNotEmpty()) {
                            cacheTweets(feedType.name, ranked)
                            if (feedType == FeedType.FOR_YOU) {
                                lastForYouSuccessAtMs = System.currentTimeMillis()
                            }
                            _timelineSyncEvents.tryEmit(
                                TimelineSyncEvent(
                                    feedType = feedType,
                                    page = finalPage,
                                    source = source,
                                )
                            )
                        }
                        finalPage
                    }
                } finally {
                    if (source == TimelineRefreshSource.FOREGROUND || source == TimelineRefreshSource.USER_PULL) {
                        markForegroundFeedSettled()
                    }
                }
            }
        }
    }

    private suspend fun fetchFollowingBlendCandidates(account: AccountEntity): List<Tweet> {
        val now = System.currentTimeMillis()
        val cachedFollowing = db.tweetCacheDao().getFeedOnce(FeedType.FOLLOWING.name, limit = 30)
            .mapNotNull { runCatching { tweetAdapter.fromJson(it.tweetJson) }.getOrNull() }
        if (cachedFollowing.isNotEmpty() && (now - lastFollowingFetchAtMs) < FOLLOWING_BLEND_CACHE_TTL_MS) {
            return cachedFollowing
        }
        val fetched = withTimeoutOrNull(FOLLOWING_BLEND_BUDGET_MS) {
            graphqlClient.fetchHomeTimeline(account, latestOnly = true, cursor = null).getOrNull()?.tweets
        }.orEmpty()
        if (fetched.isNotEmpty()) {
            lastFollowingFetchAtMs = System.currentTimeMillis()
            return fetched.take(25)
        }
        return cachedFollowing
    }

    private suspend fun fetchTopTopicBlendCandidates(): Pair<String, List<Tweet>> {
        val now = System.currentTimeMillis()
        if (now - lastTopicBlendAtMs < TOPIC_BLEND_COOLDOWN_MS) {
            return "" to emptyList()
        }
        val topTopics = db.topicInterestDao().getTopPositiveOnce(limit = 8)
            .filterNot {
                LocalForYouRanker.isRiskyOrNsfwToken(it.tokenLower) ||
                    LocalForYouRanker.isRiskyOrNsfwHandleOrText("", it.displayLabel)
            }
        if (topTopics.isEmpty()) return "" to emptyList()
        val chosen = topTopics[topicBlendRotationIndex % topTopics.size]
        topicBlendRotationIndex = (topicBlendRotationIndex + 1) % topTopics.size
        lastTopicBlendAtMs = now

        val fetched = withTimeoutOrNull(TOPIC_BLEND_BUDGET_MS) {
            searchRepository.searchGlobalTweets(
                query = chosen.displayLabel,
                tab = SearchTab.TOP,
                cursor = null,
                recordHistory = false,
                forceRefresh = false,
            ).getOrNull()?.tweets
        }.orEmpty()
        return chosen.displayLabel to fetched.take(15)
    }

    fun observeUserModeration(handle: String): Flow<UserModerationEntity?> {
        val clean = handle.trim().removePrefix("@").lowercase()
        return db.userModerationDao().observeByHandle(clean)
    }

    suspend fun filterTweetsForFeed(
        tweets: List<Tweet>,
        mutedRules: List<MutedRuleEntity>,
        moderations: List<UserModerationEntity>,
        excludeRiskyAndNsfw: Boolean = true,
    ): List<Tweet> = withContext(Dispatchers.Default) {
        val suppressedRisky = if (excludeRiskyAndNsfw) rankingSignals.getSuppressedRiskyHandles() else emptySet()
        ranker.filterMuted(
            tweets = tweets,
            mutedRules = mutedRules,
            moderations = moderations,
            excludeRiskyAndNsfw = excludeRiskyAndNsfw,
            suppressedRiskyHandles = suppressedRisky,
        )
    }

    private suspend fun applyBookmarkedFlagsAndRank(
        tweets: List<Tweet>,
        feedType: FeedType,
        prefsOverride: AppPreferences? = null,
        sourceTags: Map<String, String> = emptyMap(),
    ): List<Tweet> = coroutineScope {
        rankingSignals.ensureInitialized()
        val bookmarkedDeferred = async(Dispatchers.IO) { db.bookmarkDao().getBookmarkedIdsOnce().toHashSet() }
        val prefsDeferred = async(Dispatchers.IO) { prefsOverride ?: preferencesFlow.first() }
        val affinitiesDeferred = async(Dispatchers.IO) { db.affinityDao().getAllOnce() }
        val mutedDeferred = async(Dispatchers.IO) { db.mutedRuleDao().getAllOnce() }
        val moderationsDeferred = async(Dispatchers.IO) { db.userModerationDao().getAllOnce() }
        val seenDeferred = async(Dispatchers.IO) { db.seenTweetDao().getRecentOnce(limit = 3000) }
        val topicsDeferred = if (feedType == FeedType.FOR_YOU) {
            async(Dispatchers.IO) { db.topicInterestDao().getAllOnce() }
        } else null
        val bookmarkTextsDeferred = if (feedType == FeedType.FOR_YOU) {
            async(Dispatchers.IO) { db.bookmarkDao().getRecentSearchableTexts() }
        } else null
        val recentQueriesDeferred = if (feedType == FeedType.FOR_YOU) {
            async(Dispatchers.IO) { db.recentSearchDao().getRecentQueriesOnce() }
        } else null

        val bookmarkedIds = bookmarkedDeferred.await()
        val prefs = prefsDeferred.await()
        val affinities = affinitiesDeferred.await()
        val mutedRules = mutedDeferred.await()
        val moderations = moderationsDeferred.await()
        val seenRecords = seenDeferred.await()
        val seenById = seenRecords.associateBy { it.tweetId }
        val suppressedRiskyHandles = affinities
            .filter { it.isRiskyOrNsfw }
            .mapTo(HashSet()) { it.handleLower.trim().removePrefix("@").lowercase() }
        val dismissedIds = seenRecords
            .filter { it.isDismissed }
            .mapTo(HashSet()) { it.tweetId }

        val flagged = tweets.map { t ->
            if (t.id in bookmarkedIds && !t.viewerState.isBookmarked) {
                t.copy(viewerState = t.viewerState.copy(isBookmarked = true))
            } else t
        }

        when (feedType) {
            FeedType.FOR_YOU -> ranker.rankForYou(
                tweets = flagged,
                affinities = affinities,
                mutedRules = mutedRules,
                prefs = prefs,
                bookmarkedTexts = bookmarkTextsDeferred?.await().orEmpty(),
                recentSearchQueries = recentQueriesDeferred?.await().orEmpty(),
                moderations = moderations,
                seenByTweetId = seenById,
                topicInterests = topicsDeferred?.await().orEmpty(),
                sourceTags = sourceTags,
            )
            FeedType.FOLLOWING ->
                ranker.filterMuted(
                    tweets = flagged,
                    mutedRules = mutedRules,
                    moderations = moderations,
                    excludeRiskyAndNsfw = prefs.excludeRiskyAndNsfw,
                    suppressedRiskyHandles = suppressedRiskyHandles,
                    dismissedTweetIds = dismissedIds,
                ).sortedByDescending { it.createdAtEpochMs }
        }
    }

    private suspend fun cacheTweets(bucket: String, tweets: List<Tweet>) {
        val entities = tweets.map { t ->
            CachedTweetEntity(
                compositeKey = "${bucket}_${t.id}",
                tweetId = t.id,
                feedBucket = bucket,
                authorHandle = t.author.handle,
                tweetJson = tweetAdapter.toJson(t),
                createdAtEpochMs = t.createdAtEpochMs,
            )
        }
        db.tweetCacheDao().replaceBucket(bucket, entities)
    }

    fun seedThreadFocalTweet(tweet: Tweet) {
        if (tweet.id.isNotBlank()) {
            seededThreadTweets.put(tweet.id, tweet)
        }
    }

    fun peekSeededThreadTweet(tweetId: String): Tweet? = seededThreadTweets.get(tweetId)

    fun peekCachedThread(tweetId: String): ThreadConversation? = threadCache.get(tweetId)

    suspend fun findCachedTweet(tweetId: String): Tweet? = withContext(Dispatchers.IO) {
        db.tweetCacheDao().findByTweetId(tweetId)?.let {
            runCatching { tweetAdapter.fromJson(it.tweetJson) }.getOrNull()
        }
    }

    /**
     * Resolves the original parent tweet when capturing a reply post screenshot:
     * 1) Checks in-memory thread/seeded caches and local Room/bookmark caches first (0ms).
     * 2) Automatically fetches the parent tweet from X/public fallback under a bounded 5s timeout.
     */
    suspend fun resolveParentTweetForScreenshot(
        parentTweetId: String,
        parentHandle: String? = null,
    ): Tweet? {
        if (parentTweetId.isBlank()) return null
        peekSeededThreadTweet(parentTweetId)?.let { return it }
        peekCachedThread(parentTweetId)?.focalTweet?.let { return it }
        findCachedTweet(parentTweetId)?.let { return it }
        bookmarkRepository.getBookmarkById(parentTweetId)?.let { return it }

        return withTimeoutOrNull(5_000L) {
            loadThread(
                tweetId = parentTweetId,
                authorHandle = parentHandle.orEmpty(),
                forceRefresh = false,
            ).getOrNull()?.focalTweet
        }
    }

    suspend fun loadThread(
        tweetId: String,
        authorHandle: String = "",
        forceRefresh: Boolean = false,
    ): Result<ThreadConversation> {
        if (!forceRefresh) {
            threadCache.get(tweetId)?.let { return Result.success(it) }
        }
        return threadCoalescer.coalesce(tweetId) {
            withContext(Dispatchers.IO) {
                loadThreadNetwork(tweetId, authorHandle)
            }
        }
    }

    private suspend fun loadThreadNetwork(
        tweetId: String,
        authorHandle: String,
    ): Result<ThreadConversation> {
        val networkRes = withTimeoutOrNull(SCREEN_CHAIN_BUDGET_MS) {
            val account = db.accountDao().getActiveAccount()
            var gqlFailure: Throwable? = null
            if (account != null) {
                val gql = graphqlClient.fetchThreadDetail(account, tweetId)
                if (gql.isSuccess) {
                    gql.getOrNull()?.let { threadCache.put(tweetId, it) }
                    return@withTimeoutOrNull gql
                }
                gqlFailure = gql.exceptionOrNull()
                // On rate-limit or auth challenge, skip extra public fetches if we already have the focal tweet locally
                if (gqlFailure is XApiException.RateLimited || gqlFailure is XApiException.AuthOrChallengeError) {
                    val local = peekSeededThreadTweet(tweetId)
                        ?: findCachedTweet(tweetId)
                        ?: bookmarkRepository.getBookmarkById(tweetId)
                    if (local != null) {
                        return@withTimeoutOrNull Result.success(ThreadConversation(emptyList(), local, emptyList()))
                    }
                    return@withTimeoutOrNull Result.failure(gqlFailure)
                }
            }
            val prefs = preferencesFlow.first()
            val publicThread = publicClient.fetchPublicThread(
                handle = authorHandle,
                tweetId = tweetId,
                customProxyBaseUrl = prefs.customProxyBaseUrl,
            )
            if (publicThread.isSuccess) {
                val conv = publicThread.getOrThrow()
                threadCache.put(tweetId, conv)
                return@withTimeoutOrNull Result.success(conv)
            }
            null
        }
        if (networkRes != null) return networkRes

        val offlineTweet = peekSeededThreadTweet(tweetId)
            ?: findCachedTweet(tweetId)
            ?: bookmarkRepository.getBookmarkById(tweetId)
        return if (offlineTweet != null) {
            Result.success(ThreadConversation(emptyList(), offlineTweet, emptyList()))
        } else {
            Result.failure(TimeoutException("Unable to load thread in time"))
        }
    }

    private suspend fun persistCachedProfile(user: XUser) {
        val handleLower = user.handle.trim().removePrefix("@").lowercase()
        if (handleLower.isBlank() || isPlaceholderHandle(handleLower)) return
        profileCache.put(handleLower, user)
        runCatching {
            db.profileCacheDao().upsert(
                CachedProfileEntity(
                    handleLower = handleLower,
                    userId = user.id,
                    userJson = userAdapter.toJson(user),
                    cachedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Reads a cached XUser instantly from RAM or persistent Room storage so Profile screens
     * never start from an empty page after cold start.
     */
    suspend fun getCachedUserProfile(
        handle: String = "",
        userId: String = "",
    ): XUser? = withContext(Dispatchers.IO) {
        val cleanHandle = handle.trim().removePrefix("@").lowercase()
        if (cleanHandle.isNotBlank() && !isPlaceholderHandle(cleanHandle)) {
            profileCache.get(cleanHandle)?.let { return@withContext mergeModerationIntoUser(it, syncRemoteFlags = false) }
            val entity = db.profileCacheDao().getByHandle(cleanHandle)
            val parsed = entity?.let { runCatching { userAdapter.fromJson(it.userJson) }.getOrNull() }
            if (parsed != null) {
                val merged = mergeModerationIntoUser(parsed, syncRemoteFlags = false)
                profileCache.put(cleanHandle, merged)
                return@withContext merged
            }
        }
        val cleanId = userId.trim()
        if (cleanId.isNotBlank()) {
            val entity = db.profileCacheDao().getByUserId(cleanId)
            val parsed = entity?.let { runCatching { userAdapter.fromJson(it.userJson) }.getOrNull() }
            if (parsed != null) {
                val merged = mergeModerationIntoUser(parsed, syncRemoteFlags = false)
                val key = merged.handle.trim().removePrefix("@").lowercase()
                if (key.isNotBlank()) profileCache.put(key, merged)
                return@withContext merged
            }
        }
        null
    }

    /**
     * Pre-warms the signed-in account's Room-cached profile into RAM during app startup.
     */
    suspend fun warmUpActiveProfileCache() = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount() ?: return@withContext
        getCachedUserProfile(handle = account.handle, userId = account.userId)
    }

    private fun profileTabBucket(handle: String, tab: ProfileTab): String =
        "PROFILE_${handle.trim().removePrefix("@").lowercase()}_${tab.name}"

    suspend fun getCachedProfileTabTweets(handle: String, tab: ProfileTab): List<Tweet> =
        withContext(Dispatchers.IO) {
            val clean = handle.trim().removePrefix("@").lowercase()
            if (clean.isBlank() || isPlaceholderHandle(clean)) return@withContext emptyList()
            db.tweetCacheDao().getFeedOnce(profileTabBucket(clean, tab))
                .mapNotNull { runCatching { tweetAdapter.fromJson(it.tweetJson) }.getOrNull() }
                .filterNot { it.id.startsWith("profile_") }
        }

    /**
     * Resolves and auto-heals the currently signed-in X user's real profile.
     * Persists the resolved profile in Room so subsequent app launches render immediately.
     */
    suspend fun refreshActiveAccountProfile(): Result<XUser> = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("Not signed in"))

        val twidCandidate = account.userId.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        val viewerRes = graphqlClient.fetchViewerProfile(account, twidCandidate)

        val baseUser: XUser? = when {
            viewerRes.isSuccess -> viewerRes.getOrNull()
            !isPlaceholderHandle(account.handle) -> {
                loadUserProfile(account.handle, forceRefresh = true).getOrNull()
            }
            else -> null
        }

        val resolvedUser = baseUser?.let { enrichUserProfileIfSparse(it) }

        if (resolvedUser != null && resolvedUser.handle.isNotBlank()) {
            val updatedEntity = AccountEntity(
                userId = resolvedUser.id.ifBlank { account.userId },
                handle = resolvedUser.handle,
                displayName = resolvedUser.displayName.ifBlank { resolvedUser.handle },
                avatarUrl = resolvedUser.avatarUrl.ifBlank { account.avatarUrl },
                bannerUrl = resolvedUser.bannerUrl.ifBlank { account.bannerUrl },
                authToken = account.authToken,
                ct0 = account.ct0,
                isActive = true,
                addedAtEpochMs = account.addedAtEpochMs,
            )
            if (updatedEntity.userId != account.userId) {
                db.accountDao().deleteAccount(account.userId)
            }
            db.accountDao().upsertAccount(updatedEntity)
            persistCachedProfile(resolvedUser)
            Result.success(resolvedUser)
        } else if (!isPlaceholderHandle(account.handle)) {
            val cached = getCachedUserProfile(account.handle, account.userId)
            val prefs = preferencesFlow.first()
            val publicEnriched = cached ?: publicClient.fetchPublicProfile(
                handle = account.handle,
                customProxyBaseUrl = prefs.customProxyBaseUrl,
            ).getOrNull()
            val fallbackUser = publicEnriched ?: XUser(
                id = account.userId,
                handle = account.handle,
                displayName = account.displayName.ifBlank { account.handle },
                avatarUrl = account.avatarUrl,
                bannerUrl = account.bannerUrl,
            )
            persistCachedProfile(fallbackUser)
            Result.success(fallbackUser)
        } else {
            Result.failure(
                viewerRes.exceptionOrNull()
                    ?: IllegalStateException("Could not resolve your X profile. Try signing in again.")
            )
        }
    }

    private suspend fun enrichUserProfileIfSparse(user: XUser): XUser {
        if (user.handle.isBlank() || isPlaceholderHandle(user.handle)) return user
        val missingStats = user.followersCount == 0L && user.followingCount == 0L && user.tweetsCount == 0L
        val missingVisuals = user.avatarUrl.isBlank() || user.bannerUrl.isBlank()
        if (!missingStats && !missingVisuals) return user

        val prefs = preferencesFlow.first()
        val fx = publicClient.fetchPublicProfile(
            handle = user.handle,
            customProxyBaseUrl = prefs.customProxyBaseUrl,
        ).getOrNull() ?: return user
        return user.copy(
            id = user.id.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: fx.id.ifBlank { user.id },
            displayName = user.displayName.takeIf { it.isNotBlank() && it != user.handle } ?: fx.displayName,
            avatarUrl = user.avatarUrl.ifBlank { fx.avatarUrl },
            bannerUrl = user.bannerUrl.ifBlank { fx.bannerUrl },
            bio = user.bio.ifBlank { fx.bio },
            location = user.location.ifBlank { fx.location },
            websiteUrl = user.websiteUrl.ifBlank { fx.websiteUrl },
            followersCount = if (user.followersCount > 0L) user.followersCount else fx.followersCount,
            followingCount = if (user.followingCount > 0L) user.followingCount else fx.followingCount,
            tweetsCount = if (user.tweetsCount > 0L) user.tweetsCount else fx.tweetsCount,
            joinedLabel = user.joinedLabel.ifBlank { fx.joinedLabel },
            joinedAtEpochMs = if (user.joinedAtEpochMs > 0L) user.joinedAtEpochMs else fx.joinedAtEpochMs,
            isVerified = user.isVerified || fx.isVerified,
        )
    }

    private fun isPlaceholderHandle(handle: String): Boolean {
        val clean = handle.trim().lowercase()
        return clean.isBlank() || clean == "x_user" || clean == "x_account" || clean == "unknown"
    }

    suspend fun loadUserProfile(
        handle: String,
        forceRefresh: Boolean = false,
    ): Result<XUser> {
        val clean = handle.trim().removePrefix("@")
        if (clean.isBlank()) return Result.failure(IllegalArgumentException("Empty handle"))
        val cacheKey = clean.lowercase()
        if (!forceRefresh) {
            profileCache.get(cacheKey)?.let {
                return Result.success(mergeModerationIntoUser(it, syncRemoteFlags = false))
            }
        }
        return profileCoalescer.coalesce(cacheKey) {
            withContext(Dispatchers.IO) {
                val account = db.accountDao().getActiveAccount()
                val prefs = preferencesFlow.first()
                var fromAuthenticatedX = false
                val resolved: Result<XUser> = withTimeoutOrNull(SCREEN_CHAIN_BUDGET_MS) {
                    if (account != null) {
                        coroutineScope {
                            // Race GraphQL + REST v1.1 concurrently instead of sequential 7s+7s stalls
                            val gqlDeferred = async { graphqlClient.fetchUserByScreenName(account, clean) }
                            val restDeferred = async { graphqlClient.fetchUserRestV1(account, screenName = clean) }
                            val gql = gqlDeferred.await()
                            if (gql.isSuccess) {
                                restDeferred.cancel()
                                fromAuthenticatedX = true
                                return@coroutineScope Result.success(enrichUserProfileIfSparse(gql.getOrThrow()))
                            }
                            val restV1 = restDeferred.await()
                            if (restV1.isSuccess) {
                                fromAuthenticatedX = true
                                return@coroutineScope Result.success(enrichUserProfileIfSparse(restV1.getOrThrow()))
                            }
                            val err = gql.exceptionOrNull()
                            if (err is XApiException.AuthOrChallengeError) {
                                return@coroutineScope Result.failure(err)
                            }
                            publicClient.fetchPublicProfile(clean, prefs.customProxyBaseUrl, forceRefresh)
                        }
                    } else {
                        publicClient.fetchPublicProfile(clean, prefs.customProxyBaseUrl, forceRefresh)
                    }
                } ?: Result.failure(TimeoutException("Profile lookup timed out"))

                val mergedResult = resolved.map { loadedUser ->
                    val merged = mergeModerationIntoUser(loadedUser, syncRemoteFlags = fromAuthenticatedX)
                    persistCachedProfile(merged)
                    rankingSignals.recordProfileVisit(merged)
                    merged
                }
                mergedResult
            }
        }
    }

    private suspend fun mergeModerationIntoUser(
        user: XUser,
        syncRemoteFlags: Boolean,
    ): XUser {
        val handleLower = user.handle.trim().removePrefix("@").lowercase()
        if (handleLower.isBlank() || isPlaceholderHandle(handleLower)) return user
        val existing = db.userModerationDao().getByHandle(handleLower)
        val effectiveMuted = if (syncRemoteFlags && user.isMuted) true else (existing?.isMuted ?: user.isMuted)
        val effectiveBlocked = if (syncRemoteFlags && user.isBlocked) true else (existing?.isBlocked ?: user.isBlocked)

        if (syncRemoteFlags && (effectiveMuted || effectiveBlocked) &&
            (existing == null || existing.isMuted != effectiveMuted || existing.isBlocked != effectiveBlocked ||
                (existing.userId.isBlank() && user.id.isNotBlank()))
        ) {
            db.userModerationDao().upsert(
                UserModerationEntity(
                    handleLower = handleLower,
                    userId = user.id.ifBlank { existing?.userId.orEmpty() },
                    displayName = user.displayName.ifBlank { existing?.displayName.orEmpty() },
                    isMuted = effectiveMuted,
                    isBlocked = effectiveBlocked,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }

        return user.copy(
            isMuted = effectiveMuted,
            isBlocked = effectiveBlocked,
            isFollowing = if (effectiveBlocked) false else user.isFollowing,
        )
    }

    suspend fun loadUserTweets(
        user: XUser,
        operationName: String = "UserTweets",
        cacheTab: ProfileTab? = null,
        forceRefresh: Boolean = false,
    ): Result<List<Tweet>> {
        val cacheKey = "${user.handle.trim().removePrefix("@").lowercase()}_$operationName"
        if (!forceRefresh) {
            profileTabCache.get(cacheKey)?.let { return Result.success(it) }
        }
        return profileTabCoalescer.coalesce(cacheKey) {
            withContext(Dispatchers.IO) {
                val account = db.accountDao().getActiveAccount()
                val prefs = preferencesFlow.first()
                val effectiveUserId = when {
                    user.id.isNotBlank() && user.id.all(Char::isDigit) -> user.id
                    account != null &&
                        account.handle.equals(user.handle, ignoreCase = true) &&
                        account.userId.isNotBlank() &&
                        account.userId.all(Char::isDigit) -> account.userId
                    else -> ""
                }
                val isAuthOnlyTab = operationName == "Likes"
                val nitterTabPath = when (operationName) {
                    "UserTweetsAndReplies" -> "with_replies"
                    "UserMedia" -> "media"
                    else -> ""
                }

                val result: Result<List<Tweet>> = if (account != null && effectiveUserId.isNotBlank()) {
                    val gql = graphqlClient.fetchUserTimeline(account, effectiveUserId, operationName)
                    if (gql.isSuccess) {
                        gql.map { page -> page.tweets.filterNot { it.id.startsWith("profile_") } }
                    } else if (!isAuthOnlyTab) {
                        publicClient.fetchPublicTimelineForHandle(
                            handle = user.handle,
                            customProxyBaseUrl = prefs.customProxyBaseUrl,
                            tabPath = nitterTabPath,
                            forceRefresh = forceRefresh,
                        ).map { list -> list.filterNot { it.id.startsWith("profile_") } }
                    } else {
                        gql.map { it.tweets }
                    }
                } else if (!isAuthOnlyTab) {
                    publicClient.fetchPublicTimelineForHandle(
                        handle = user.handle,
                        customProxyBaseUrl = prefs.customProxyBaseUrl,
                        tabPath = nitterTabPath,
                        forceRefresh = forceRefresh,
                    ).map { list -> list.filterNot { it.id.startsWith("profile_") } }
                } else {
                    Result.success(emptyList())
                }
                if (result.isSuccess) {
                    val tweets = result.getOrNull().orEmpty()
                    profileTabCache.put(cacheKey, tweets)
                    if (cacheTab != null) {
                        cacheTweets(profileTabBucket(user.handle, cacheTab), tweets)
                    }
                }
                result
            }
        }
    }

    /**
     * Loads X's native Profile Articles tab via GraphQL `UserArticlesTweets`, falling back to
     * filtering the user's cached or live timeline for posts containing native `XArticle` nodes
     * or `/article/` links. Persists results to Room and memory TTL cache for instant tab switches.
     */
    suspend fun loadUserArticles(
        user: XUser,
        forceRefresh: Boolean = false,
    ): Result<List<Tweet>> {
        val cacheKey = "${user.handle.trim().removePrefix("@").lowercase()}_UserArticlesTweets"
        if (!forceRefresh) {
            profileTabCache.get(cacheKey)?.let { return Result.success(it) }
        }
        return profileTabCoalescer.coalesce(cacheKey) {
            withContext(Dispatchers.IO) {
                val account = db.accountDao().getActiveAccount()
                val bucket = profileTabBucket(user.handle, ProfileTab.ARTICLES)
                if (account != null && user.id.isNotBlank() && user.id.all { it.isDigit() }) {
                    val articlesRes = graphqlClient.fetchUserArticles(account, user.id)
                    if (articlesRes.isSuccess) {
                        val items = articlesRes.getOrNull()?.tweets.orEmpty()
                        if (items.isNotEmpty()) {
                            profileTabCache.put(cacheKey, items)
                            cacheTweets(bucket, items)
                            return@withContext Result.success(items)
                        }
                    }
                }
                // Check already-cached Posts tab before firing a redundant UserTweets network call
                val cachedPosts = getCachedProfileTabTweets(user.handle, ProfileTab.POSTS)
                val sourceTweets = if (cachedPosts.isNotEmpty()) {
                    cachedPosts
                } else {
                    loadUserTweets(user, operationName = "UserTweets", cacheTab = null).getOrNull().orEmpty()
                }
                val filtered = sourceTweets.filter { tweet ->
                    tweet.article != null ||
                        tweet.linkPreview?.url?.contains("/article/", ignoreCase = true) == true
                }
                profileTabCache.put(cacheKey, filtered)
                if (filtered.isNotEmpty()) {
                    cacheTweets(bucket, filtered)
                }
                Result.success(filtered)
            }
        }
    }

    fun peekCachedSearch(query: String, tab: SearchTab): TimelinePage? =
        searchRepository.peekCachedSearch(query, tab)

    fun peekCachedPeople(query: String): List<XUser>? =
        searchRepository.peekCachedPeople(query)

    suspend fun searchPosts(
        query: String,
        tab: SearchTab,
        cursor: String? = null,
        recordHistory: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<TimelinePage> {
        if (recordHistory) {
            backgroundScope.launch { rankingSignals.recordSearchInterest(query) }
        }
        return searchRepository.searchGlobalTweets(query, tab, cursor, recordHistory, forceRefresh)
    }

    suspend fun searchPeople(
        query: String,
        recordHistory: Boolean = false,
        forceRefresh: Boolean = false,
    ): Result<List<XUser>> = searchRepository.searchPeople(query, recordHistory, forceRefresh)

    suspend fun searchUniversalSuggestions(
        query: String,
        recentTweets: List<Tweet> = emptyList(),
    ): UniversalSearchSuggestions = searchRepository.searchUniversalSuggestions(query, recentTweets)

    fun mergeTweetHashtags(
        query: String,
        base: UniversalSearchSuggestions,
        tweets: List<Tweet>,
    ): UniversalSearchSuggestions = searchRepository.mergeTweetHashtags(query, base, tweets)

    suspend fun translateTweet(
        tweet: Tweet,
        targetLanguageCode: String = Locale.getDefault().language.ifBlank { "en" },
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        val cacheKey = "${tweet.id}_$targetLanguageCode"
        translationCache.get(cacheKey)?.let { return@withContext Result.success(it) }
        val account = db.accountDao().getActiveAccount()
        graphqlClient.translateTweet(account, tweet.id, tweet.text, targetLanguageCode)
            .onSuccess { translationCache.put(cacheKey, it) }
    }

    suspend fun fetchTrends(forceRefresh: Boolean = false): Result<TrendsResult> =
        searchRepository.fetchTrends(forceRefresh)

    suspend fun fetchNotifications(
        mentionsOnly: Boolean,
        forceRefresh: Boolean = false,
    ): Result<List<NotificationItem>> {
        if (!forceRefresh) {
            notificationsCache.get(mentionsOnly)?.let { return Result.success(it) }
        }
        return notificationsCoalescer.coalesce(mentionsOnly) {
            withContext(Dispatchers.IO) {
                val account = db.accountDao().getActiveAccount()
                    ?: return@withContext Result.failure(IllegalStateException("Sign in to X to view notifications and mentions."))
                graphqlClient.fetchNotifications(account, mentionsOnly)
                    .onSuccess { notificationsCache.put(mentionsOnly, it) }
            }
        }
    }

    suspend fun toggleLike(tweet: Tweet): Result<Tweet> = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("Sign in to X to like posts."))
        val targetLike = !tweet.viewerState.isLiked
        val updated = tweet.copy(
            viewerState = tweet.viewerState.copy(isLiked = targetLike),
            metrics = tweet.metrics.copy(
                likes = (tweet.metrics.likes + if (targetLike) 1 else -1).coerceAtLeast(0L)
            )
        )
        db.tweetCacheDao().updateTweetJsonEverywhere(tweet.id, tweetAdapter.toJson(updated))
        graphqlClient.setLike(account, tweet.id, targetLike).fold(
            onSuccess = {
                if (targetLike) {
                    rankingSignals.recordTweetInteraction(tweet, like = true)
                }
                Result.success(updated)
            },
            onFailure = { err ->
                db.tweetCacheDao().updateTweetJsonEverywhere(tweet.id, tweetAdapter.toJson(tweet))
                Result.failure(err)
            },
        )
    }

    suspend fun toggleRepost(tweet: Tweet): Result<Tweet> = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("Sign in to X to repost."))
        val targetRepost = !tweet.viewerState.isReposted
        val updated = tweet.copy(
            viewerState = tweet.viewerState.copy(isReposted = targetRepost),
            metrics = tweet.metrics.copy(
                reposts = (tweet.metrics.reposts + if (targetRepost) 1 else -1).coerceAtLeast(0L)
            )
        )
        db.tweetCacheDao().updateTweetJsonEverywhere(tweet.id, tweetAdapter.toJson(updated))
        graphqlClient.setRepost(account, tweet.id, targetRepost).fold(
            onSuccess = {
                if (targetRepost) {
                    rankingSignals.recordTweetInteraction(tweet, repost = true)
                }
                Result.success(updated)
            },
            onFailure = { err ->
                db.tweetCacheDao().updateTweetJsonEverywhere(tweet.id, tweetAdapter.toJson(tweet))
                Result.failure(err)
            },
        )
    }

    suspend fun toggleBookmark(tweet: Tweet, folderTag: String = "Saved"): Result<Tweet> =
        withContext(Dispatchers.IO) {
            val activeAccount = db.accountDao().getActiveAccount()
            val (isNowBookmarked, updated) = bookmarkRepository.toggleBookmark(
                tweet = tweet,
                folderTag = folderTag,
                isSyncedWithX = activeAccount != null,
            )
            if (isNowBookmarked) {
                rankingSignals.recordTweetInteraction(tweet, bookmark = true)
            }
            db.tweetCacheDao().updateTweetJsonEverywhere(tweet.id, tweetAdapter.toJson(updated))

            activeAccount?.let { account ->
                graphqlClient.setBookmark(account, tweet.id, isNowBookmarked)
            }
            Result.success(updated)
        }

    suspend fun loadTweetQuotes(
        tweetId: String,
        cursor: String? = null,
    ): Result<TimelinePage> = searchRepository.searchGlobalTweets(
        query = "quoted_tweet_id:$tweetId",
        tab = SearchTab.TOP,
        cursor = cursor,
        recordHistory = false,
    )

    fun hasMoreRemoteBookmarks(): Boolean = !bookmarksNextCursor.isNullOrBlank()

    /**
     * Syncs bookmarks from X with full cursor pagination and strict newest-to-oldest ordering.
     * - When [loadMore] is false, fetches the newest page and stores its bottom cursor.
     * - When [loadMore] is true, fetches the next older page using the stored cursor and
     *   appends it strictly below previously synced bookmarks in Room.
     */
    suspend fun syncRemoteBookmarks(loadMore: Boolean = false): Result<BookmarkSyncPage> =
        withContext(Dispatchers.IO) {
            bookmarksSyncMutex.withLock {
                val account = db.accountDao().getActiveAccount()
                    ?: return@withLock Result.failure(IllegalStateException("Sign in to sync X bookmarks."))
                val cursorToUse = if (loadMore) {
                    bookmarksNextCursor ?: return@withLock Result.success(BookmarkSyncPage(0, false))
                } else {
                    null
                }
                graphqlClient.fetchBookmarks(account = account, cursor = cursorToUse, count = 40).map { page ->
                    val count = bookmarkRepository.saveAllBookmarks(
                        tweets = page.tweets,
                        folderTag = "Synced from X",
                        isSyncedWithX = true,
                        appendOlderPage = loadMore,
                    )
                    val next = page.nextCursor?.takeIf { it.isNotBlank() && it != cursorToUse && page.tweets.isNotEmpty() }
                    bookmarksNextCursor = next
                    BookmarkSyncPage(syncedCount = count, hasMore = next != null)
                }
            }
        }

    suspend fun publishTweet(
        text: String,
        replyToTweetId: String? = null,
        replyToHandle: String? = null,
        quoteTweetUrl: String? = null,
        quotedTweetFallback: QuotedTweet? = null,
        mediaUris: List<Uri> = emptyList(),
    ): Result<Tweet?> = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("Sign in to X to publish posts."))

        val uploadedMediaIds = if (mediaUris.isEmpty()) {
            emptyList()
        } else {
            val results = coroutineScope {
                mediaUris.take(4).map { uri ->
                    async(Dispatchers.IO) {
                        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes == null || bytes.isEmpty()) return@async Result.success<String?>(null)
                        val mime = appContext.contentResolver.getType(uri) ?: "image/jpeg"
                        graphqlClient.uploadImageBytes(account, bytes, mime).map { it as String? }
                    }
                }.awaitAll()
            }
            val firstFailure = results.firstOrNull { it.isFailure }
            if (firstFailure != null) {
                return@withContext Result.failure(
                    firstFailure.exceptionOrNull() ?: IllegalStateException("Photo upload failed")
                )
            }
            results.mapNotNull { it.getOrNull() }
        }

        val res = graphqlClient.createTweet(
            account = account,
            text = text,
            replyToTweetId = replyToTweetId,
            quoteTweetUrl = quoteTweetUrl,
            mediaIds = uploadedMediaIds,
        ).map { created ->
            if (created != null && quotedTweetFallback != null && created.quotedTweet == null) {
                created.copy(
                    quotedTweet = quotedTweetFallback,
                    quotedTweetId = created.quotedTweetId ?: quotedTweetFallback.id,
                )
            } else {
                created
            }
        }
        if (res.isSuccess && !replyToHandle.isNullOrBlank()) {
            recordAffinityInteraction(replyToHandle, reply = true)
        }
        res
    }

    suspend fun deleteTweet(tweetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("Sign in to delete posts."))
        graphqlClient.deleteTweet(account, tweetId).onSuccess {
            db.tweetCacheDao().deleteByTweetId(tweetId)
            db.bookmarkDao().deleteById(tweetId)
            threadCache.remove(tweetId)
            profileTabCache.clear()
        }
    }

    suspend fun votePoll(tweet: Tweet, choiceIndex: Int): Result<Tweet> = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("Sign in to X to vote in polls."))
        val poll = tweet.poll
            ?: return@withContext Result.failure(IllegalStateException("Poll unavailable"))
        if (poll.isClosedAt()) {
            return@withContext Result.failure(IllegalStateException("This poll has ended."))
        }
        if (poll.votedChoiceIndex != null) {
            return@withContext Result.success(tweet)
        }
        if (poll.choices.none { it.index == choiceIndex }) {
            return@withContext Result.failure(IllegalArgumentException("Invalid poll choice"))
        }
        val uri = poll.cardUri
        if (uri.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Poll card URI unavailable"))
        }
        graphqlClient.votePoll(
            account = account,
            tweetId = tweet.id,
            cardUri = uri,
            choiceIndex = choiceIndex,
            choiceCount = poll.choices.size,
            cardNameOverride = poll.cardName,
        ).map { responseBody ->
            val updatedPoll = XPollParser.mergeVoteResponse(
                previous = poll,
                responseBody = responseBody,
                votedChoiceIndex = choiceIndex,
            )
            val updatedTweet = tweet.copy(poll = updatedPoll)

            // Persist locally in Room timeline + bookmark snapshots without re-fetching
            db.tweetCacheDao().updateTweetJsonEverywhere(tweet.id, tweetAdapter.toJson(updatedTweet))
            bookmarkRepository.updateTweetSnapshotIfBookmarked(updatedTweet)

            // Patch in-memory caches in-place so navigation between tabs/threads stays consistent
            patchPollInMemoryCaches(tweet.id, updatedPoll)

            // Notify active ViewModels (Feed, Thread, Explore, Profile) to update UI locally
            _pollUpdates.tryEmit(PollVoteUpdate(tweetId = tweet.id, poll = updatedPoll))
            updatedTweet
        }
    }

    private fun patchPollInMemoryCaches(tweetId: String, updatedPoll: Poll) {
        fun patchTweet(item: Tweet): Tweet = when {
            item.id == tweetId -> item.copy(poll = updatedPoll)
            item.quotedTweet?.id == tweetId -> item.copy(
                quotedTweet = item.quotedTweet.copy(poll = updatedPoll)
            )
            else -> item
        }

        threadCache.transformAll { conv ->
            val focalChanged = conv.focalTweet.id == tweetId || conv.focalTweet.quotedTweet?.id == tweetId
            val ancestorChanged = conv.ancestors.any { it.id == tweetId || it.quotedTweet?.id == tweetId }
            val replyChanged = conv.replies.any { it.id == tweetId || it.quotedTweet?.id == tweetId }
            if (!focalChanged && !ancestorChanged && !replyChanged) {
                conv
            } else {
                conv.copy(
                    ancestors = conv.ancestors.map(::patchTweet),
                    focalTweet = patchTweet(conv.focalTweet),
                    replies = conv.replies.map(::patchTweet),
                )
            }
        }
        profileTabCache.transformAll { list ->
            if (list.none { it.id == tweetId || it.quotedTweet?.id == tweetId }) list
            else list.map(::patchTweet)
        }
    }

    suspend fun setFollow(user: XUser, follow: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val account = db.accountDao().getActiveAccount()
            ?: return@withContext Result.failure(IllegalStateException("Sign in to follow accounts."))
        profileCache.remove(user.handle.lowercase())
        graphqlClient.setFollow(account, user.id, follow)
    }

    /**
     * Toggles Mute for [user], persisting immediately to local Room `user_moderations`
     * (so For You & Following exclude their content immediately) and syncing with X when signed in.
     * Returns `Result.success(true)` if synced with X servers, or `Result.success(false)` if applied locally only.
     */
    suspend fun setMuteUser(user: XUser, mute: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val handleLower = user.handle.trim().removePrefix("@").lowercase()
        if (handleLower.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid handle"))
        }
        val existing = db.userModerationDao().getByHandle(handleLower)
        val nextBlocked = existing?.isBlocked ?: user.isBlocked
        if (!mute && !nextBlocked) {
            db.userModerationDao().deleteByHandle(handleLower)
        } else {
            db.userModerationDao().upsert(
                UserModerationEntity(
                    handleLower = handleLower,
                    userId = user.id.ifBlank { existing?.userId.orEmpty() },
                    displayName = user.displayName.ifBlank { existing?.displayName.orEmpty() },
                    isMuted = mute,
                    isBlocked = nextBlocked,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
        if (!mute) {
            db.mutedRuleDao().deleteHandleRule(handleLower)
        }
        persistCachedProfile(user.copy(isMuted = mute, isBlocked = nextBlocked))

        val account = db.accountDao().getActiveAccount()
        val syncedRemote = if (account != null) {
            graphqlClient.setMuteUser(account, user.id, user.handle, mute).isSuccess
        } else {
            false
        }
        Result.success(syncedRemote)
    }

    /**
     * Toggles Block for [user], persisting immediately to local Room `user_moderations`,
     * clearing follow/affinity boost state when blocking, and syncing with X when signed in.
     * Returns `Result.success(true)` if synced with X servers, or `Result.success(false)` if applied locally only.
     */
    suspend fun setBlockUser(user: XUser, block: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val handleLower = user.handle.trim().removePrefix("@").lowercase()
        if (handleLower.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid handle"))
        }
        val existing = db.userModerationDao().getByHandle(handleLower)
        val nextMuted = existing?.isMuted ?: user.isMuted
        if (!block && !nextMuted) {
            db.userModerationDao().deleteByHandle(handleLower)
        } else {
            db.userModerationDao().upsert(
                UserModerationEntity(
                    handleLower = handleLower,
                    userId = user.id.ifBlank { existing?.userId.orEmpty() },
                    displayName = user.displayName.ifBlank { existing?.displayName.orEmpty() },
                    isMuted = nextMuted,
                    isBlocked = block,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
        if (block) {
            db.affinityDao().deleteByHandle(handleLower)
        } else if (!nextMuted) {
            db.mutedRuleDao().deleteHandleRule(handleLower)
        }
        persistCachedProfile(
            user.copy(
                isMuted = nextMuted,
                isBlocked = block,
                isFollowing = if (block) false else user.isFollowing,
            )
        )

        val account = db.accountDao().getActiveAccount()
        val syncedRemote = if (account != null) {
            graphqlClient.setBlockUser(account, user.id, user.handle, block).isSuccess
        } else {
            false
        }
        Result.success(syncedRemote)
    }

    suspend fun unmuteUserModeration(entity: UserModerationEntity): Result<Boolean> {
        val stub = getCachedUserProfile(handle = entity.handleLower, userId = entity.userId)
            ?: XUser(
                id = entity.userId,
                handle = entity.handleLower,
                displayName = entity.displayName.ifBlank { entity.handleLower },
                isMuted = entity.isMuted,
                isBlocked = entity.isBlocked,
            )
        return setMuteUser(stub, mute = false)
    }

    suspend fun unblockUserModeration(entity: UserModerationEntity): Result<Boolean> {
        val stub = getCachedUserProfile(handle = entity.handleLower, userId = entity.userId)
            ?: XUser(
                id = entity.userId,
                handle = entity.handleLower,
                displayName = entity.displayName.ifBlank { entity.handleLower },
                isMuted = entity.isMuted,
                isBlocked = entity.isBlocked,
            )
        return setBlockUser(stub, block = false)
    }

    suspend fun signInWithCookies(
        authToken: String,
        ct0: String,
        twidUserId: String? = null,
    ): Result<AccountEntity> = withContext(Dispatchers.IO) {
        val cleanAuth = authToken.trim()
        val cleanCt0 = ct0.trim()
        val cleanTwid = twidUserId?.trim()?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        if (cleanAuth.isBlank() || cleanCt0.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Both auth_token and ct0 cookies are required."))
        }
        val probe = AccountEntity(
            userId = cleanTwid ?: "pending",
            handle = "x_account",
            displayName = "X Account",
            avatarUrl = "",
            bannerUrl = "",
            authToken = cleanAuth,
            ct0 = cleanCt0,
            isActive = true,
        )
        val verified = graphqlClient.fetchViewerProfile(probe, cleanTwid)
        val entity = if (verified.isSuccess) {
            val u = verified.getOrThrow()
            profileCache.put(u.handle.lowercase(), u)
            AccountEntity(
                userId = u.id.ifBlank { cleanTwid ?: "session_${cleanAuth.take(8)}" },
                handle = u.handle,
                displayName = u.displayName.ifBlank { u.handle },
                avatarUrl = u.avatarUrl,
                bannerUrl = u.bannerUrl,
                authToken = cleanAuth,
                ct0 = cleanCt0,
                isActive = true,
            )
        } else {
            val fallbackId = cleanTwid ?: "session_${cleanAuth.take(8)}"
            AccountEntity(
                userId = fallbackId,
                handle = "x_user",
                displayName = "X Session",
                avatarUrl = "",
                bannerUrl = "",
                authToken = cleanAuth,
                ct0 = cleanCt0,
                isActive = true,
            )
        }
        clearSessionCaches()
        db.accountDao().clearActiveFlags()
        db.accountDao().upsertAccount(entity)
        verified.getOrNull()?.let { persistCachedProfile(it) }
        Result.success(entity)
    }

    suspend fun switchAccount(userId: String) = withContext(Dispatchers.IO) {
        clearSessionCaches()
        db.accountDao().switchActiveAccount(userId)
    }

    suspend fun removeAccount(userId: String) = withContext(Dispatchers.IO) {
        clearSessionCaches()
        db.accountDao().deleteAccount(userId)
        val remaining = db.accountDao().getAllAccounts()
        if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
            db.accountDao().switchActiveAccount(remaining.first().userId)
        }
    }

    suspend fun togglePinCreatorBoost(handle: String, displayName: String = "") =
        withContext(Dispatchers.IO) {
            val lower = handle.trim().removePrefix("@").lowercase()
            val existing = db.affinityDao().getByHandle(lower)
            val updated = (existing ?: AuthorAffinityEntity(handleLower = lower, displayName = displayName))
                .copy(
                    displayName = displayName.ifBlank { existing?.displayName.orEmpty() },
                    isPinnedBoost = !(existing?.isPinnedBoost ?: false),
                    lastInteractedEpochMs = System.currentTimeMillis(),
                )
            db.affinityDao().upsert(updated)
        }

    suspend fun isCreatorPinned(handle: String): Boolean = withContext(Dispatchers.IO) {
        db.affinityDao().getByHandle(handle.trim().removePrefix("@").lowercase())?.isPinnedBoost ?: false
    }

    suspend fun recordAffinityInteraction(
        handle: String,
        displayName: String = "",
        like: Boolean = false,
        repost: Boolean = false,
        reply: Boolean = false,
        bookmark: Boolean = false,
        profileOpen: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val lower = handle.trim().removePrefix("@").lowercase()
        if (lower.isBlank()) return@withContext
        if (LocalForYouRanker.isRiskyOrNsfwHandleOrText(lower, displayName)) {
            rankingSignals.flagCreatorAsRiskyOrNsfw(lower, displayName)
            return@withContext
        }
        val current = db.affinityDao().getByHandle(lower)
            ?: AuthorAffinityEntity(handleLower = lower, displayName = displayName)
        if (current.isRiskyOrNsfw) return@withContext
        db.affinityDao().upsert(
            current.copy(
                displayName = displayName.ifBlank { current.displayName },
                likesGiven = current.likesGiven + if (like) 1 else 0,
                repostsGiven = current.repostsGiven + if (repost) 1 else 0,
                repliesGiven = current.repliesGiven + if (reply) 1 else 0,
                bookmarksGiven = current.bookmarksGiven + if (bookmark) 1 else 0,
                profileOpens = current.profileOpens + if (profileOpen) 1 else 0,
                lastInteractedEpochMs = System.currentTimeMillis(),
            )
        )
    }

    fun recordThreadOpen(tweet: Tweet) {
        backgroundScope.launch {
            rankingSignals.recordTweetInteraction(tweet, threadOpen = true)
        }
    }

    suspend fun recordViewportImpressions(events: List<TweetImpressionEvent>) =
        rankingSignals.recordViewportImpressions(events)

    suspend fun markNotInterested(tweet: Tweet): FeedbackUndoToken =
        rankingSignals.markNotInterested(tweet)

    suspend fun markShowLessFromAuthor(tweet: Tweet): FeedbackUndoToken =
        rankingSignals.markShowLessFromAuthor(tweet)

    suspend fun undoRankingFeedback(token: FeedbackUndoToken) =
        rankingSignals.undoFeedback(token)

    suspend fun addManualTopic(rawInput: String): Result<TopicInterestEntity> =
        rankingSignals.addManualTopic(rawInput)

    suspend fun setTopicManualState(tokenLower: String, state: String) =
        rankingSignals.setTopicManualState(tokenLower, state)

    suspend fun deleteTopic(tokenLower: String) =
        rankingSignals.deleteTopic(tokenLower)

    suspend fun setAuthorSoftDemoted(handleLower: String, demoted: Boolean) =
        rankingSignals.setAuthorSoftDemoted(handleLower, demoted)

    suspend fun resetAuthorAffinity(handleLower: String) =
        rankingSignals.resetAuthorAffinity(handleLower)

    suspend fun clearSeenHistory() =
        rankingSignals.clearSeenHistory()

    suspend fun resetLocalForYouModel() =
        rankingSignals.resetAllSafeSignals()

    suspend fun addMutedRule(value: String, isHandle: Boolean) = withContext(Dispatchers.IO) {
        val clean = value.trim().let { if (isHandle) it.removePrefix("@") else it }
        if (clean.isBlank()) return@withContext
        if (isHandle) {
            val handleLower = clean.lowercase()
            val existing = db.userModerationDao().getByHandle(handleLower)
            db.userModerationDao().upsert(
                UserModerationEntity(
                    handleLower = handleLower,
                    userId = existing?.userId.orEmpty(),
                    displayName = existing?.displayName.orEmpty(),
                    isMuted = true,
                    isBlocked = existing?.isBlocked ?: false,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            )
            // Keep muted_rules free of duplicate handle rows since user_moderations is canonical
            db.mutedRuleDao().deleteHandleRule(handleLower)
        } else {
            db.mutedRuleDao().insert(MutedRuleEntity(value = clean, isHandle = false))
        }
    }

    suspend fun removeMutedRule(id: Long) = withContext(Dispatchers.IO) {
        db.mutedRuleDao().deleteById(id)
    }

    suspend fun saveDraft(draft: DraftTweetEntity): Long = withContext(Dispatchers.IO) {
        db.draftDao().upsert(draft)
    }

    suspend fun deleteDraft(id: Long) = withContext(Dispatchers.IO) {
        db.draftDao().deleteById(id)
    }

    suspend fun deleteRecentSearch(query: String) = searchRepository.deleteRecentSearch(query)

    suspend fun clearRecentSearches() = searchRepository.clearRecentSearches()

    suspend fun ensureEndpointRegistryReady() = endpointRegistry.ensureInitialized()

    suspend fun refreshEndpointRegistry(): Result<Int> = endpointRegistry.refreshFromRemote(force = true)

    fun clearPublicFallbackCaches() {
        publicClient.clearCaches()
        profileCache.clear()
        profileTabCache.clear()
        threadCache.clear()
    }

    companion object {
        private const val HOME_TIMELINE_BUDGET_MS = 6_500L
        private const val SCREEN_CHAIN_BUDGET_MS = 6_500L
        private const val FOLLOWING_BLEND_BUDGET_MS = 3_500L
        private const val FOLLOWING_BLEND_CACHE_TTL_MS = 5L * 60L * 1000L
        private const val TOPIC_BLEND_BUDGET_MS = 3_000L
        private const val TOPIC_BLEND_COOLDOWN_MS = 10L * 60L * 1000L
    }
}
