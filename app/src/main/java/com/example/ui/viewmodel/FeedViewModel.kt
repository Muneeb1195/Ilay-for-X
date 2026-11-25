package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.remote.XApiException
import com.example.data.repository.TimelineRefreshSource
import com.example.data.repository.XRepository
import com.example.domain.model.FeedType
import com.example.domain.model.TimelinePage
import com.example.domain.model.Tweet
import com.example.domain.ranking.FeedbackUndoToken
import com.example.domain.ranking.TweetImpressionEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUiState(
    val selectedFeed: FeedType = FeedType.FOR_YOU,
    val tweets: List<Tweet> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isUserRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextCursor: String? = null,
    val isPublicFallback: Boolean = false,
    val sourceInfo: String = "",
    val pendingNewPostsCount: Int = 0,
    val lastRefreshedAtEpochMs: Long = 0L,
    val errorMessage: String? = null,
    val statusBannerMessage: String? = null,
    val rateLimitResetAtEpochMs: Long? = null,
    val isAuthErrorBanner: Boolean = false,
)

data class FeedFeedbackSnackbar(
    val message: String,
    val undoToken: FeedbackUndoToken? = null,
)

class FeedViewModel(
    private val repository: XRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    private val _feedbackSnackbars = MutableSharedFlow<FeedFeedbackSnackbar>(extraBufferCapacity = 4)
    val feedbackSnackbars: SharedFlow<FeedFeedbackSnackbar> = _feedbackSnackbars.asSharedFlow()

    val activeAccount: StateFlow<AccountEntity?> = repository.activeAccountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allAccounts: StateFlow<List<AccountEntity>> = repository.allAccountsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val preferences: StateFlow<AppPreferences> = repository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    private var lastAccountId: String? = "__INIT__"
    private var loadFeedJob: Job? = null
    private var loadMoreJob: Job? = null
    private var pendingBackgroundPage: TimelinePage? = null

    init {
        viewModelScope.launch {
            repository.preferencesRepository.ensureFirstInstallRecorded()
        }
        // Hydrate Room-cached timeline on frame 1 before account flow/network settle
        viewModelScope.launch {
            val initialCached = repository.getCachedTimeline(_uiState.value.selectedFeed)
            if (initialCached.isNotEmpty() && _uiState.value.tweets.isEmpty()) {
                _uiState.update {
                    it.copy(
                        tweets = initialCached,
                        isLoading = false,
                        isRefreshing = true,
                    )
                }
            }
        }
        // Stay consistent with StartupPrefetchWorker background refreshes between manual pulls
        viewModelScope.launch {
            repository.timelineSyncEvents.collect { event ->
                if (event.source != TimelineRefreshSource.BACKGROUND_WORKER) return@collect
                val current = _uiState.value
                if (event.feedType != current.selectedFeed) return@collect
                val incoming = event.page.tweets
                if (incoming.isEmpty()) return@collect

                if (current.tweets.isEmpty()) {
                    pendingBackgroundPage = null
                    _uiState.update {
                        it.copy(
                            tweets = incoming,
                            nextCursor = event.page.nextCursor ?: it.nextCursor,
                            isLoading = false,
                            isRefreshing = false,
                            isPublicFallback = event.page.isFromPublicFallback,
                            sourceInfo = event.page.sourceInfo,
                            pendingNewPostsCount = 0,
                            lastRefreshedAtEpochMs = event.syncedAtEpochMs,
                            errorMessage = null,
                        )
                    }
                } else {
                    val existingIds = current.tweets.mapTo(HashSet(current.tweets.size)) { it.id }
                    val newCount = incoming.count { it.id !in existingIds }
                    if (newCount > 0) {
                        pendingBackgroundPage = event.page
                        _uiState.update {
                            it.copy(
                                pendingNewPostsCount = newCount,
                                lastRefreshedAtEpochMs = event.syncedAtEpochMs,
                            )
                        }
                    } else {
                        // Silently refresh engagement metrics for currently displayed posts without shifting scroll
                        val incomingById = incoming.associateBy { it.id }
                        _uiState.update { cur ->
                            cur.copy(
                                tweets = cur.tweets.map { existing -> incomingById[existing.id] ?: existing },
                                lastRefreshedAtEpochMs = event.syncedAtEpochMs,
                            )
                        }
                    }
                }
            }
        }
        // Apply poll vote updates locally without re-fetching any tweet
        viewModelScope.launch {
            repository.pollUpdates.collect { update ->
                pendingBackgroundPage = pendingBackgroundPage?.let { page ->
                    page.copy(
                        tweets = page.tweets.map { item ->
                            when {
                                item.id == update.tweetId -> item.copy(poll = update.poll)
                                item.quotedTweet?.id == update.tweetId -> item.copy(
                                    quotedTweet = item.quotedTweet.copy(poll = update.poll)
                                )
                                else -> item
                            }
                        }
                    )
                }
                _uiState.update { cur ->
                    val changed = cur.tweets.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId }
                    if (!changed) cur else cur.copy(
                        tweets = cur.tweets.map { item ->
                            when {
                                item.id == update.tweetId -> item.copy(poll = update.poll)
                                item.quotedTweet?.id == update.tweetId -> item.copy(
                                    quotedTweet = item.quotedTweet.copy(poll = update.poll)
                                )
                                else -> item
                            }
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.activeAccountFlow
                .distinctUntilChanged { old, new -> old?.userId == new?.userId }
                .collect { account ->
                    if (lastAccountId != account?.userId) {
                        lastAccountId = account?.userId
                        if (account != null && (account.handle.isBlank() || account.handle == "x_user" || account.handle == "x_account")) {
                            launch { repository.refreshActiveAccountProfile() }
                        }
                        loadFeed(_uiState.value.selectedFeed, forceRefresh = true)
                    }
                }
        }
        // Immediately reflect local Mute/Block/Keyword rule changes in the displayed feed & pending background posts
        viewModelScope.launch {
            var previousModSignature = -1
            combine(repository.userModerationsFlow, repository.mutedRulesFlow) { mods, rules ->
                mods to rules
            }.collect { (mods, rules) ->
                val activeCount = mods.size + rules.size
                val excludeRisky = preferences.value.excludeRiskyAndNsfw
                pendingBackgroundPage = pendingBackgroundPage?.let { page ->
                    val filteredPending = repository.filterTweetsForFeed(
                        tweets = page.tweets,
                        mutedRules = rules,
                        moderations = mods,
                        excludeRiskyAndNsfw = excludeRisky,
                    )
                    page.copy(tweets = filteredPending)
                }
                val currentTweets = _uiState.value.tweets
                if (currentTweets.isNotEmpty()) {
                    val filtered = repository.filterTweetsForFeed(
                        tweets = currentTweets,
                        mutedRules = rules,
                        moderations = mods,
                        excludeRiskyAndNsfw = excludeRisky,
                    )
                    if (filtered.size != currentTweets.size) {
                        _uiState.update { it.copy(tweets = filtered) }
                    } else if (previousModSignature > activeCount) {
                        val reranked = repository.getCachedTimeline(_uiState.value.selectedFeed)
                        if (reranked.isNotEmpty()) {
                            _uiState.update { it.copy(tweets = reranked) }
                        }
                    }
                }
                previousModSignature = activeCount
            }
        }
    }

    fun dismissOnboardingHints() {
        viewModelScope.launch {
            repository.preferencesRepository.dismissOnboardingHints()
        }
    }

    fun dismissStatusBanner() {
        _uiState.update {
            it.copy(
                statusBannerMessage = null,
                rateLimitResetAtEpochMs = null,
                isAuthErrorBanner = false,
            )
        }
    }

    fun selectFeed(feedType: FeedType) {
        if (_uiState.value.selectedFeed == feedType && _uiState.value.tweets.isNotEmpty()) return
        pendingBackgroundPage = null
        _uiState.update {
            it.copy(
                selectedFeed = feedType,
                pendingNewPostsCount = 0,
                errorMessage = null,
            )
        }
        loadFeed(feedType, forceRefresh = true)
    }

    fun refresh() {
        pendingBackgroundPage = null
        _uiState.update { it.copy(pendingNewPostsCount = 0) }
        loadFeed(_uiState.value.selectedFeed, forceRefresh = true, userInitiatedRefresh = true)
    }

    fun applyPendingBackgroundPosts() {
        val page = pendingBackgroundPage
        pendingBackgroundPage = null
        if (page != null && page.tweets.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    tweets = page.tweets,
                    nextCursor = page.nextCursor ?: it.nextCursor,
                    isPublicFallback = page.isFromPublicFallback,
                    sourceInfo = page.sourceInfo,
                    pendingNewPostsCount = 0,
                    lastRefreshedAtEpochMs = System.currentTimeMillis(),
                )
            }
        } else {
            refresh()
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val cursor = state.nextCursor ?: return
        if (state.isLoadingMore || state.isRefreshing || state.isUserRefreshing) return
        val targetFeed = state.selectedFeed
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val result = repository.refreshTimeline(targetFeed, cursor = cursor)
            if (_uiState.value.selectedFeed != targetFeed) return@launch
            result.onSuccess { page ->
                _uiState.update { cur ->
                    cur.copy(
                        tweets = (cur.tweets + page.tweets).distinctBy { it.id },
                        nextCursor = page.nextCursor,
                        isLoadingMore = false,
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    private fun loadFeed(
        feedType: FeedType,
        forceRefresh: Boolean,
        userInitiatedRefresh: Boolean = false,
    ) {
        loadMoreJob?.cancel()
        loadFeedJob?.cancel()
        val previousIds = if (userInitiatedRefresh) {
            _uiState.value.tweets.mapTo(HashSet()) { it.id }
        } else {
            emptySet()
        }

        loadFeedJob = viewModelScope.launch {
            val cached = repository.getCachedTimeline(feedType)
            if (cached.isNotEmpty() && !userInitiatedRefresh) {
                _uiState.update {
                    it.copy(
                        selectedFeed = feedType,
                        tweets = cached,
                        isLoading = false,
                        isRefreshing = forceRefresh,
                        isUserRefreshing = false,
                        isLoadingMore = false,
                        pendingNewPostsCount = 0,
                        errorMessage = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        selectedFeed = feedType,
                        isLoading = !userInitiatedRefresh && it.tweets.isEmpty(),
                        isRefreshing = !userInitiatedRefresh && it.tweets.isNotEmpty(),
                        isUserRefreshing = userInitiatedRefresh,
                        isLoadingMore = false,
                        pendingNewPostsCount = 0,
                        errorMessage = null,
                    )
                }
            }

            if (!forceRefresh && cached.isNotEmpty()) return@launch

            val res = repository.refreshTimeline(
                feedType = feedType,
                cursor = null,
                forceNetwork = userInitiatedRefresh,
                source = if (userInitiatedRefresh) TimelineRefreshSource.USER_PULL else TimelineRefreshSource.FOREGROUND,
            )
            if (_uiState.value.selectedFeed != feedType) return@launch
            res.onSuccess { page ->
                val now = System.currentTimeMillis()
                pendingBackgroundPage = null
                _uiState.update {
                    it.copy(
                        tweets = page.tweets,
                        nextCursor = page.nextCursor,
                        isLoading = false,
                        isRefreshing = false,
                        isUserRefreshing = false,
                        isPublicFallback = page.isFromPublicFallback,
                        sourceInfo = page.sourceInfo,
                        pendingNewPostsCount = 0,
                        lastRefreshedAtEpochMs = now,
                        errorMessage = null,
                        statusBannerMessage = null,
                        rateLimitResetAtEpochMs = null,
                        isAuthErrorBanner = false,
                    )
                }
                if (userInitiatedRefresh && previousIds.isNotEmpty()) {
                    val newCount = page.tweets.count { it.id !in previousIds }
                    val msg = when {
                        newCount == 1 -> "Loaded 1 new post"
                        newCount > 1 -> "Loaded $newCount new posts"
                        else -> "Timeline is up to date"
                    }
                    _snackbarEvents.tryEmit(msg)
                }
            }.onFailure { err ->
                val resetAt = (err as? XApiException.RateLimited)?.resetAtEpochMs
                val isAuthErr = err is XApiException.AuthOrChallengeError
                val bannerText = when (err) {
                    is XApiException.RateLimited -> err.message
                    is XApiException.AuthOrChallengeError -> err.message
                    else -> err.message ?: "Unable to refresh right now · Showing cached posts"
                }
                _uiState.update { cur ->
                    cur.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isUserRefreshing = false,
                        errorMessage = if (cur.tweets.isEmpty()) bannerText else null,
                        statusBannerMessage = if (cur.tweets.isNotEmpty()) bannerText else null,
                        rateLimitResetAtEpochMs = if (cur.tweets.isNotEmpty()) resetAt else null,
                        isAuthErrorBanner = if (cur.tweets.isNotEmpty()) isAuthErr else false,
                    )
                }
            }
        }
    }

    fun toggleLike(tweet: Tweet) {
        val original = tweet
        val optimistic = tweet.copy(
            viewerState = tweet.viewerState.copy(isLiked = !tweet.viewerState.isLiked),
            metrics = tweet.metrics.copy(
                likes = (tweet.metrics.likes + if (!tweet.viewerState.isLiked) 1 else -1).coerceAtLeast(0L)
            )
        )
        replaceTweetInState(optimistic)
        viewModelScope.launch {
            repository.toggleLike(original).onFailure { err ->
                replaceTweetInState(original)
                _snackbarEvents.tryEmit(err.message ?: "Sign in to like posts")
            }
        }
    }

    fun toggleRepost(tweet: Tweet) {
        val original = tweet
        val optimistic = tweet.copy(
            viewerState = tweet.viewerState.copy(isReposted = !tweet.viewerState.isReposted),
            metrics = tweet.metrics.copy(
                reposts = (tweet.metrics.reposts + if (!tweet.viewerState.isReposted) 1 else -1).coerceAtLeast(0L)
            )
        )
        replaceTweetInState(optimistic)
        viewModelScope.launch {
            repository.toggleRepost(original).onFailure { err ->
                replaceTweetInState(original)
                _snackbarEvents.tryEmit(err.message ?: "Sign in to repost")
            }
        }
    }

    fun toggleBookmark(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleBookmark(tweet).onSuccess { updated ->
                replaceTweetInState(updated)
                val label = if (updated.viewerState.isBookmarked) "Saved to Bookmarks" else "Removed from Bookmarks"
                _snackbarEvents.tryEmit(label)
            }
        }
    }

    fun applyPollVotedTweet(updated: Tweet) {
        replaceTweetInState(updated)
    }

    fun prependCreatedTweet(tweet: Tweet) {
        _uiState.update { cur ->
            cur.copy(tweets = listOf(tweet) + cur.tweets.filterNot { it.id == tweet.id })
        }
    }

    fun deleteTweet(tweet: Tweet) {
        viewModelScope.launch {
            repository.deleteTweet(tweet.id).onSuccess {
                _uiState.update { cur -> cur.copy(tweets = cur.tweets.filterNot { it.id == tweet.id }) }
                _snackbarEvents.tryEmit("Post deleted")
            }.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "Unable to delete post")
            }
        }
    }

    fun recordViewportImpressions(events: List<TweetImpressionEvent>) {
        if (events.isEmpty()) return
        viewModelScope.launch {
            repository.recordViewportImpressions(events)
        }
    }

    fun markNotInterested(tweet: Tweet) {
        pendingBackgroundPage = pendingBackgroundPage?.let { page ->
            page.copy(tweets = page.tweets.filterNot { it.id == tweet.id })
        }
        _uiState.update { cur ->
            cur.copy(tweets = cur.tweets.filterNot { it.id == tweet.id })
        }
        viewModelScope.launch {
            val undoToken = repository.markNotInterested(tweet)
            _feedbackSnackbars.tryEmit(
                FeedFeedbackSnackbar(
                    message = "Post hidden · We'll show less like this",
                    undoToken = undoToken,
                )
            )
        }
    }

    fun markShowLessFromAuthor(tweet: Tweet) {
        val handle = tweet.author.handle.trim().removePrefix("@")
        pendingBackgroundPage = pendingBackgroundPage?.let { page ->
            page.copy(tweets = page.tweets.filterNot { it.id == tweet.id })
        }
        _uiState.update { cur ->
            cur.copy(tweets = cur.tweets.filterNot { it.id == tweet.id })
        }
        viewModelScope.launch {
            val undoToken = repository.markShowLessFromAuthor(tweet)
            _feedbackSnackbars.tryEmit(
                FeedFeedbackSnackbar(
                    message = "Showing less from @$handle in For You",
                    undoToken = undoToken,
                )
            )
        }
    }

    fun undoFeedback(token: FeedbackUndoToken) {
        viewModelScope.launch {
            repository.undoRankingFeedback(token)
            _uiState.update { cur ->
                if (cur.tweets.any { it.id == token.tweet.id }) cur
                else cur.copy(tweets = listOf(token.tweet) + cur.tweets)
            }
        }
    }

    fun switchAccount(userId: String) {
        viewModelScope.launch {
            repository.switchAccount(userId)
            _snackbarEvents.tryEmit("Switched active X account")
        }
    }

    fun signInWithCookies(
        authToken: String,
        ct0: String,
        twidUserId: String? = null,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            repository.signInWithCookies(authToken, ct0, twidUserId).onSuccess { acc ->
                _snackbarEvents.tryEmit("Signed in as @${acc.handle}")
                onSuccess()
                refresh()
            }.onFailure { err ->
                _snackbarEvents.tryEmit(err.message ?: "Invalid session cookies")
            }
        }
    }

    fun removeAccount(userId: String) {
        viewModelScope.launch {
            repository.removeAccount(userId)
            _snackbarEvents.tryEmit("Account removed")
        }
    }

    private fun replaceTweetInState(updated: Tweet) {
        _uiState.update { cur ->
            cur.copy(tweets = cur.tweets.map { if (it.id == updated.id) updated else it })
        }
    }

    companion object {
        fun factory(repository: XRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(repository) as T
            }
    }
}
