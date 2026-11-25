package com.example.ui.viewmodel

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.local.AuthorAffinityEntity
import com.example.data.local.BookmarkEntity
import com.example.data.local.DraftTweetEntity
import com.example.data.local.EndpointHashEntity
import com.example.data.local.MutedRuleEntity
import com.example.data.local.RecentSearchEntity
import com.example.data.local.UserModerationEntity
import com.example.data.repository.XRepository
import com.example.domain.model.FeedDensity
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.NotificationItem
import com.example.domain.model.ProfileTab
import com.example.domain.model.SearchTab
import com.example.domain.model.SwipeAction
import com.example.domain.model.ThreadConversation
import com.example.domain.model.TrendItem
import com.example.domain.model.TrendsResult
import com.example.domain.model.Tweet
import com.example.domain.model.XArticle
import com.example.domain.model.XUser
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ===================== EXPLORE & SEARCH =====================

data class ExploreUiState(
    val query: String = "",
    val activeTab: SearchTab = SearchTab.TOP,
    val trends: List<TrendItem> = emptyList(),
    val trendsSource: String = "",
    val trendsUpdatedAtEpochMs: Long = 0L,
    val trendsError: String? = null,
    val relatedHashtags: List<String> = emptyList(),
    val relatedTopics: List<String> = emptyList(),
    val tweetResults: List<Tweet> = emptyList(),
    val peopleResults: List<XUser> = emptyList(),
    val nextCursor: String? = null,
    val sourceInfo: String = "",
    val isLoading: Boolean = false,
    val isUserRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isDebouncing: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null,
)

@OptIn(FlowPreview::class)
class ExploreViewModel(private val repository: XRepository) : ViewModel() {
    private data class SearchRequest(
        val query: String,
        val tab: SearchTab,
        val explicitSubmitId: Long,
    )

    private val _uiState = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    val recentSearches: StateFlow<List<RecentSearchEntity>> = repository.recentSearchesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val queryInputFlow = MutableStateFlow("")
    private val tabInputFlow = MutableStateFlow(SearchTab.TOP)
    private val explicitSubmitFlow = MutableStateFlow(0L)
    private var lastHandledSubmitId = 0L
    private var trendsJob: Job? = null
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                queryInputFlow,
                tabInputFlow,
                explicitSubmitFlow,
            ) { rawQuery, tab, submitId ->
                SearchRequest(rawQuery.trim(), tab, submitId)
            }
                .distinctUntilChanged()
                .debounce { req ->
                    val isExplicit = req.explicitSubmitId > lastHandledSubmitId
                    when {
                        req.query.isBlank() -> 0L
                        isExplicit -> 0L
                        else -> SEARCH_DEBOUNCE_MS
                    }
                }
                .collectLatest { req ->
                    val isExplicit = req.explicitSubmitId > lastHandledSubmitId
                    if (isExplicit) {
                        lastHandledSubmitId = req.explicitSubmitId
                    }

                    if (req.query.isBlank()) {
                        _uiState.update {
                            it.copy(
                                isDebouncing = false,
                                isLoading = false,
                                hasSearched = false,
                                relatedHashtags = emptyList(),
                                relatedTopics = emptyList(),
                                tweetResults = emptyList(),
                                peopleResults = emptyList(),
                                nextCursor = null,
                                sourceInfo = "",
                                errorMessage = null,
                            )
                        }
                        return@collectLatest
                    }

                    // Avoid burning network calls on a single typed character unless explicitly submitted
                    if (!isExplicit && req.query.length < MIN_AUTO_SEARCH_LENGTH) {
                        _uiState.update { it.copy(isDebouncing = false) }
                        return@collectLatest
                    }

                    executeSearch(
                        query = req.query,
                        tab = req.tab,
                        recordHistory = isExplicit,
                    )
                }
        }
        viewModelScope.launch {
            repository.pollUpdates.collect { update ->
                _uiState.update { cur ->
                    val changed = cur.tweetResults.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId }
                    if (!changed) cur else cur.copy(
                        tweetResults = cur.tweetResults.map { item ->
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
    }

    fun ensureLoaded() {
        val state = _uiState.value
        val now = System.currentTimeMillis()
        val ageMs = now - state.trendsUpdatedAtEpochMs
        val stale = state.trendsUpdatedAtEpochMs <= 0L || ageMs < 0L || ageMs >= TRENDS_STALE_AFTER_MS
        if (state.trends.isEmpty() || stale) {
            loadTrends(forceRefresh = stale && state.trends.isNotEmpty(), userInitiated = false)
        }
    }

    fun loadTrends(forceRefresh: Boolean = false, userInitiated: Boolean = false) {
        trendsJob?.cancel()
        trendsJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isUserRefreshing = userInitiated,
                    isLoading = !userInitiated && it.trends.isEmpty() && !it.hasSearched,
                    trendsError = null,
                )
            }
            repository.fetchTrends(forceRefresh)
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            trends = result.items,
                            trendsSource = result.sourceLabel,
                            trendsUpdatedAtEpochMs = result.fetchedAtEpochMs,
                            trendsError = null,
                            isLoading = false,
                            isUserRefreshing = false,
                        )
                    }
                    if (userInitiated) {
                        _events.tryEmit("Trends updated (${result.sourceLabel})")
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            trendsError = err.message ?: "Unable to load live trends",
                            isLoading = false,
                            isUserRefreshing = false,
                        )
                    }
                    if (userInitiated) {
                        _events.tryEmit(err.message ?: "Unable to refresh trends")
                    }
                }
        }
    }

    fun refresh() {
        val state = _uiState.value
        if (state.hasSearched && state.query.trim().isNotBlank()) {
            refreshJob?.cancel()
            refreshJob = viewModelScope.launch {
                executeSearch(
                    query = state.query.trim(),
                    tab = state.activeTab,
                    recordHistory = false,
                    forceRefresh = true,
                    userInitiated = true,
                )
            }
        } else {
            loadTrends(forceRefresh = true, userInitiated = true)
        }
    }

    fun updateQuery(q: String) {
        val trimmed = q.trim()
        _uiState.update {
            it.copy(
                query = q,
                hasSearched = if (trimmed.isBlank()) false else it.hasSearched,
                isDebouncing = trimmed.length >= MIN_AUTO_SEARCH_LENGTH,
                errorMessage = if (trimmed.isBlank()) null else it.errorMessage,
            )
        }
        queryInputFlow.value = q
    }

    fun selectTab(tab: SearchTab) {
        _uiState.update { it.copy(activeTab = tab) }
        tabInputFlow.value = tab
        if (_uiState.value.query.trim().isNotBlank()) {
            explicitSubmitFlow.value += 1L
        }
    }

    fun submitSearch(queryOverride: String? = null, tabOverride: SearchTab? = null) {
        val q = (queryOverride ?: _uiState.value.query).trim()
        val tab = tabOverride ?: _uiState.value.activeTab
        if (q.isBlank()) return
        _uiState.update {
            it.copy(
                query = q,
                activeTab = tab,
                isDebouncing = false,
                hasSearched = true,
                errorMessage = null,
            )
        }
        queryInputFlow.value = q
        tabInputFlow.value = tab
        explicitSubmitFlow.value += 1L
    }

    private suspend fun executeSearch(
        query: String,
        tab: SearchTab,
        recordHistory: Boolean,
        forceRefresh: Boolean = false,
        userInitiated: Boolean = false,
    ) = coroutineScope {
        // Instant zero-spinner render when the exact query/tab is already warm in cache
        val cachedPage = if (!forceRefresh && tab != SearchTab.PEOPLE) repository.peekCachedSearch(query, tab) else null
        val cachedPeople = if (!forceRefresh && tab == SearchTab.PEOPLE) repository.peekCachedPeople(query) else null

        _uiState.update {
            it.copy(
                isDebouncing = false,
                isLoading = !userInitiated && cachedPage == null && cachedPeople == null,
                isUserRefreshing = userInitiated,
                isLoadingMore = false,
                hasSearched = true,
                tweetResults = if (cachedPage != null) {
                    if (tab == SearchTab.MEDIA) cachedPage.tweets.filter { t -> t.media.isNotEmpty() } else cachedPage.tweets
                } else it.tweetResults,
                peopleResults = cachedPeople ?: it.peopleResults,
                sourceInfo = cachedPage?.sourceInfo ?: it.sourceInfo,
                errorMessage = null,
            )
        }

        // Launch universal typeahead concurrently so hashtag & topic chips appear immediately
        val suggestionsDeferred = async {
            repository.searchUniversalSuggestions(query).also { early ->
                if (early.hashtags.isNotEmpty() || early.topics.isNotEmpty()) {
                    _uiState.update { cur ->
                        cur.copy(
                            relatedHashtags = early.hashtags,
                            relatedTopics = early.topics,
                        )
                    }
                }
            }
        }

        if (tab == SearchTab.PEOPLE) {
            val peopleDeferred = async {
                repository.searchPeople(query, recordHistory = recordHistory, forceRefresh = forceRefresh)
            }
            val peopleResult = peopleDeferred.await()
            val suggestions = suggestionsDeferred.await()

            peopleResult
                .onSuccess { people ->
                    _uiState.update {
                        it.copy(
                            peopleResults = people,
                            relatedHashtags = suggestions.hashtags,
                            relatedTopics = suggestions.topics,
                            nextCursor = null,
                            sourceInfo = "X People Search",
                            isLoading = false,
                            isUserRefreshing = false,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            peopleResults = if (userInitiated) it.peopleResults else emptyList(),
                            relatedHashtags = suggestions.hashtags,
                            relatedTopics = suggestions.topics,
                            nextCursor = null,
                            sourceInfo = if (userInitiated) it.sourceInfo else "",
                            isLoading = false,
                            isUserRefreshing = false,
                            errorMessage = err.message,
                        )
                    }
                    if (userInitiated) {
                        _events.tryEmit(err.message ?: "Unable to refresh people results")
                    }
                }
        } else {
            val postsDeferred = async {
                repository.searchPosts(
                    query = query,
                    tab = tab,
                    cursor = null,
                    recordHistory = recordHistory,
                    forceRefresh = forceRefresh,
                )
            }
            val postsResult = postsDeferred.await()
            val baseSuggestions = suggestionsDeferred.await()

            postsResult
                .onSuccess { page ->
                    val filtered = if (tab == SearchTab.MEDIA) {
                        page.tweets.filter { it.media.isNotEmpty() }
                    } else {
                        page.tweets
                    }
                    // Pure in-memory hashtag merge (zero extra network/DB call)
                    val mergedSuggestions = repository.mergeTweetHashtags(query, baseSuggestions, filtered)
                    _uiState.update {
                        it.copy(
                            tweetResults = filtered,
                            relatedHashtags = mergedSuggestions.hashtags,
                            relatedTopics = mergedSuggestions.topics,
                            nextCursor = page.nextCursor,
                            sourceInfo = page.sourceInfo,
                            isLoading = false,
                            isUserRefreshing = false,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            tweetResults = if (userInitiated) it.tweetResults else emptyList(),
                            relatedHashtags = baseSuggestions.hashtags,
                            relatedTopics = baseSuggestions.topics,
                            nextCursor = if (userInitiated) it.nextCursor else null,
                            sourceInfo = if (userInitiated) it.sourceInfo else "",
                            isLoading = false,
                            isUserRefreshing = false,
                            errorMessage = err.message,
                        )
                    }
                    if (userInitiated) {
                        _events.tryEmit(err.message ?: "Unable to refresh search results")
                    }
                }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val cursor = state.nextCursor ?: return
        val q = state.query.trim()
        if (q.isBlank() || state.isLoading || state.isLoadingMore || state.activeTab == SearchTab.PEOPLE) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            repository.searchPosts(q, state.activeTab, cursor = cursor, recordHistory = false)
                .onSuccess { page ->
                    val incoming = if (state.activeTab == SearchTab.MEDIA) {
                        page.tweets.filter { it.media.isNotEmpty() }
                    } else {
                        page.tweets
                    }
                    _uiState.update { cur ->
                        cur.copy(
                            tweetResults = (cur.tweetResults + incoming).distinctBy { it.id },
                            nextCursor = page.nextCursor,
                            isLoadingMore = false,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
        }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch { repository.deleteRecentSearch(query) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { repository.clearRecentSearches() }
    }

    fun toggleLike(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleLike(tweet).onSuccess { updated ->
                _uiState.update { cur ->
                    cur.copy(tweetResults = cur.tweetResults.map { if (it.id == updated.id) updated else it })
                }
            }
        }
    }

    fun toggleRepost(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleRepost(tweet).onSuccess { updated ->
                _uiState.update { cur ->
                    cur.copy(tweetResults = cur.tweetResults.map { if (it.id == updated.id) updated else it })
                }
            }
        }
    }

    fun toggleBookmark(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleBookmark(tweet).onSuccess { updated ->
                _uiState.update { cur ->
                    cur.copy(tweetResults = cur.tweetResults.map { if (it.id == updated.id) updated else it })
                }
            }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val MIN_AUTO_SEARCH_LENGTH = 2
        private const val TRENDS_STALE_AFTER_MS = TrendsResult.MAX_CACHE_AGE_MS

        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ExploreViewModel(repository) as T
        }
    }
}

// ===================== ACTIVITY / NOTIFICATIONS =====================

data class ActivityUiState(
    val mentionsOnly: Boolean = false,
    val items: List<NotificationItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class ActivityViewModel(private val repository: XRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    val activeAccount: StateFlow<AccountEntity?> = repository.activeAccountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var lastLoadedAccountId: String? = "__NONE__"
    private var refreshJob: Job? = null

    fun ensureLoaded() {
        val currentId = activeAccount.value?.userId
        if (lastLoadedAccountId != currentId || (_uiState.value.items.isEmpty() && !_uiState.value.isLoading)) {
            lastLoadedAccountId = currentId
            refresh()
        }
    }

    fun setFilter(mentionsOnly: Boolean) {
        if (_uiState.value.mentionsOnly == mentionsOnly && _uiState.value.items.isNotEmpty()) return
        _uiState.update { it.copy(mentionsOnly = mentionsOnly) }
        refresh(forceRefresh = false)
    }

    fun refresh(forceRefresh: Boolean = false) {
        val targetMentionsOnly = _uiState.value.mentionsOnly
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = it.items.isEmpty() || forceRefresh, errorMessage = null) }
            repository.fetchNotifications(targetMentionsOnly, forceRefresh = forceRefresh)
                .onSuccess { list ->
                    if (_uiState.value.mentionsOnly == targetMentionsOnly) {
                        _uiState.update { it.copy(items = list, isLoading = false) }
                    }
                }
                .onFailure { err ->
                    if (_uiState.value.mentionsOnly == targetMentionsOnly) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = err.message) }
                    }
                }
        }
    }

    companion object {
        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ActivityViewModel(repository) as T
        }
    }
}

// ===================== BOOKMARKS =====================

internal fun computeBookmarkFolders(items: List<Pair<BookmarkEntity, Tweet>>): List<Pair<String, Int>> {
    if (items.isEmpty()) return emptyList()
    val counts = LinkedHashMap<String, Int>()
    for ((entity, _) in items) {
        val tag = entity.folderTag.trim().ifBlank { "Saved" }
        counts[tag] = (counts[tag] ?: 0) + 1
    }
    return counts.entries
        .sortedWith(
            compareBy<Map.Entry<String, Int>>(
                {
                    when (it.key) {
                        "Synced from X" -> 0
                        "Saved" -> 1
                        else -> 2
                    }
                },
                { it.key.lowercase() }
            )
        )
        .map { it.key to it.value }
}

class BookmarksViewModel(private val repository: XRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFolder = MutableStateFlow<String?>(null)
    val selectedFolder: StateFlow<String?> = _selectedFolder.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreRemote = MutableStateFlow(true)
    val hasMoreRemote: StateFlow<Boolean> = _hasMoreRemote.asStateFlow()

    private var hasAutoSynced = false

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val allBookmarksFlow = repository.observeBookmarks("")

    val totalBookmarkCount: StateFlow<Int> = combine(allBookmarksFlow, _searchQuery) { all, _ -> all.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val folders: StateFlow<List<Pair<String, Int>>> = combine(allBookmarksFlow, _selectedFolder) { all, _ ->
        computeBookmarkFolders(all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<Pair<BookmarkEntity, Tweet>>> = combine(
        _searchQuery.flatMapLatest { query -> repository.observeBookmarks(query) },
        _selectedFolder,
    ) { items, folder ->
        val filtered = if (folder.isNullOrBlank()) {
            items
        } else {
            items.filter { it.first.folderTag.equals(folder, ignoreCase = true) }
        }
        filtered.sortedByDescending { it.first.savedAtEpochMs }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearch(q: String) {
        _searchQuery.value = q
    }

    fun selectFolder(folder: String?) {
        _selectedFolder.value = folder?.takeIf { it.isNotBlank() }
    }

    fun moveToFolder(tweetId: String, folderTag: String) {
        val clean = folderTag.trim().ifBlank { "Saved" }
        viewModelScope.launch {
            repository.updateBookmarkFolder(tweetId, clean)
            _events.tryEmit("Moved to $clean")
        }
    }

    fun ensureInitialSync(isSignedIn: Boolean) {
        if (!isSignedIn || hasAutoSynced || _isSyncing.value) return
        hasAutoSynced = true
        viewModelScope.launch {
            _isSyncing.value = true
            repository.syncRemoteBookmarks(loadMore = false)
                .onSuccess { page ->
                    _hasMoreRemote.value = page.hasMore
                }
            _isSyncing.value = false
        }
    }

    fun syncFromX() {
        if (_isSyncing.value) return
        hasAutoSynced = true
        viewModelScope.launch {
            _isSyncing.value = true
            repository.syncRemoteBookmarks(loadMore = false)
                .onSuccess { page ->
                    _hasMoreRemote.value = page.hasMore
                    _events.tryEmit("Synced ${page.syncedCount} newest bookmarks from X")
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Sync requires X sign-in")
                }
            _isSyncing.value = false
        }
    }

    fun loadMoreBookmarks() {
        if (_isSyncing.value || _isLoadingMore.value || !_hasMoreRemote.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            val result = if (repository.hasMoreRemoteBookmarks()) {
                repository.syncRemoteBookmarks(loadMore = true)
            } else {
                // Establish initial cursor first, then fetch page 2 if available
                val firstPage = repository.syncRemoteBookmarks(loadMore = false)
                if (firstPage.isSuccess && repository.hasMoreRemoteBookmarks()) {
                    repository.syncRemoteBookmarks(loadMore = true)
                } else {
                    firstPage
                }
            }
            result.onSuccess { page ->
                _hasMoreRemote.value = page.hasMore
            }.onFailure {
                _hasMoreRemote.value = false
            }
            _isLoadingMore.value = false
        }
    }

    fun removeBookmark(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleBookmark(tweet)
            _events.tryEmit("Removed from Bookmarks")
        }
    }

    fun toggleLike(tweet: Tweet) {
        viewModelScope.launch { repository.toggleLike(tweet) }
    }

    fun toggleRepost(tweet: Tweet) {
        viewModelScope.launch { repository.toggleRepost(tweet) }
    }

    companion object {
        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BookmarksViewModel(repository) as T
        }
    }
}

// ===================== THREAD READER =====================

enum class ThreadTab(val label: String) {
    REPLIES("Replies"),
    QUOTES("Quotes"),
}

data class ThreadUiState(
    val conversation: ThreadConversation? = null,
    val selectedTab: ThreadTab = ThreadTab.REPLIES,
    val quotes: List<Tweet> = emptyList(),
    val quotesCursor: String? = null,
    val isLoading: Boolean = true,
    val isLoadingReplies: Boolean = false,
    val isLoadingQuotes: Boolean = false,
    val isLoadingMoreQuotes: Boolean = false,
    val quotesLoadedOnce: Boolean = false,
    val quotesError: String? = null,
    val errorMessage: String? = null,
)

class ThreadViewModel(private val repository: XRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ThreadUiState())
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var loadJob: Job? = null
    private var quotesJob: Job? = null

    init {
        viewModelScope.launch {
            repository.pollUpdates.collect { update ->
                fun patch(item: Tweet): Tweet = when {
                    item.id == update.tweetId -> item.copy(poll = update.poll)
                    item.quotedTweet?.id == update.tweetId -> item.copy(
                        quotedTweet = item.quotedTweet.copy(poll = update.poll)
                    )
                    else -> item
                }
                _uiState.update { cur ->
                    val conv = cur.conversation
                    val convChanged = conv != null && (
                        conv.focalTweet.id == update.tweetId ||
                            conv.focalTweet.quotedTweet?.id == update.tweetId ||
                            conv.ancestors.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId } ||
                            conv.replies.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId }
                        )
                    val quotesChanged = cur.quotes.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId }
                    if (!convChanged && !quotesChanged) cur else cur.copy(
                        conversation = conv?.copy(
                            ancestors = conv.ancestors.map(::patch),
                            focalTweet = patch(conv.focalTweet),
                            replies = conv.replies.map(::patch),
                        ),
                        quotes = cur.quotes.map(::patch),
                    )
                }
            }
        }
    }

    fun load(tweetId: String, authorHandle: String = "") {
        quotesJob?.cancel()
        loadJob?.cancel()

        // Synchronous instant check so frame 0 renders the focal tweet with zero loading flash
        val cachedConv = repository.peekCachedThread(tweetId)
        val seededTweet = repository.peekSeededThreadTweet(tweetId)

        if (cachedConv != null) {
            _uiState.update {
                it.copy(
                    conversation = cachedConv,
                    isLoading = false,
                    isLoadingReplies = false,
                    errorMessage = null,
                    selectedTab = ThreadTab.REPLIES,
                    quotes = emptyList(),
                    quotesCursor = null,
                    quotesLoadedOnce = false,
                    quotesError = null,
                )
            }
            return
        }

        val initialConversation = seededTweet?.let {
            ThreadConversation(ancestors = emptyList(), focalTweet = it, replies = emptyList())
        }
        _uiState.update {
            it.copy(
                conversation = initialConversation,
                isLoading = initialConversation == null,
                isLoadingReplies = initialConversation != null,
                errorMessage = null,
                selectedTab = ThreadTab.REPLIES,
                quotes = emptyList(),
                quotesCursor = null,
                quotesLoadedOnce = false,
                quotesError = null,
            )
        }

        loadJob = viewModelScope.launch {
            if (initialConversation == null) {
                val localTweet = repository.findCachedTweet(tweetId)
                if (localTweet != null) {
                    _uiState.update {
                        it.copy(
                            conversation = ThreadConversation(
                                ancestors = emptyList(),
                                focalTweet = localTweet,
                                replies = emptyList(),
                            ),
                            isLoading = false,
                            isLoadingReplies = true,
                        )
                    }
                }
            }

            repository.loadThread(tweetId, authorHandle)
                .onSuccess { conv ->
                    _uiState.update { cur ->
                        val existingFocal = cur.conversation?.focalTweet
                        // Preserve any local state (e.g. optimistic vote/bookmark) if server returns sparser node
                        val mergedFocal = if (existingFocal != null && existingFocal.id == conv.focalTweet.id) {
                            conv.focalTweet.copy(
                                poll = conv.focalTweet.poll ?: existingFocal.poll,
                                quotedTweet = conv.focalTweet.quotedTweet ?: existingFocal.quotedTweet,
                            )
                        } else {
                            conv.focalTweet
                        }
                        cur.copy(
                            conversation = conv.copy(focalTweet = mergedFocal),
                            isLoading = false,
                            isLoadingReplies = false,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { cur ->
                        // Keep seeded focal tweet visible if reply fetch fails instead of blanking the screen
                        cur.copy(
                            isLoading = false,
                            isLoadingReplies = false,
                            errorMessage = if (cur.conversation == null) {
                                err.message ?: "Unable to load thread"
                            } else null,
                        )
                    }
                }
        }
    }

    fun selectTab(tab: ThreadTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == ThreadTab.QUOTES && !_uiState.value.quotesLoadedOnce && !_uiState.value.isLoadingQuotes) {
            loadQuotes(reset = true)
        }
    }

    fun loadQuotes(reset: Boolean = true) {
        val focalId = _uiState.value.conversation?.focalTweet?.id ?: return
        if (_uiState.value.isLoadingQuotes || _uiState.value.isLoadingMoreQuotes) return
        val cursor = if (reset) null else _uiState.value.quotesCursor
        if (!reset && cursor == null) return

        quotesJob?.cancel()
        quotesJob = viewModelScope.launch {
            _uiState.update {
                if (reset) it.copy(isLoadingQuotes = true, quotesError = null)
                else it.copy(isLoadingMoreQuotes = true, quotesError = null)
            }
            repository.loadTweetQuotes(focalId, cursor = cursor)
                .onSuccess { page ->
                    _uiState.update { cur ->
                        val merged = if (reset) page.tweets else (cur.quotes + page.tweets).distinctBy { it.id }
                        cur.copy(
                            quotes = merged,
                            quotesCursor = page.nextCursor,
                            isLoadingQuotes = false,
                            isLoadingMoreQuotes = false,
                            quotesLoadedOnce = true,
                            quotesError = null,
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingQuotes = false,
                            isLoadingMoreQuotes = false,
                            quotesLoadedOnce = true,
                            quotesError = err.message ?: "Unable to load quote posts",
                        )
                    }
                }
        }
    }

    fun loadMoreQuotes() = loadQuotes(reset = false)

    fun toggleLike(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleLike(tweet)
                .onSuccess { updateTweetInConversation(it) }
                .onFailure { _events.tryEmit(it.message ?: "Sign in to like") }
        }
    }

    fun toggleRepost(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleRepost(tweet)
                .onSuccess { updateTweetInConversation(it) }
                .onFailure { _events.tryEmit(it.message ?: "Sign in to repost") }
        }
    }

    fun toggleBookmark(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleBookmark(tweet).onSuccess {
                updateTweetInConversation(it)
                _events.tryEmit(if (it.viewerState.isBookmarked) "Bookmarked" else "Bookmark removed")
            }
        }
    }

    private fun updateTweetInConversation(updated: Tweet) {
        _uiState.update { cur ->
            val conv = cur.conversation
            cur.copy(
                conversation = conv?.copy(
                    ancestors = conv.ancestors.map { if (it.id == updated.id) updated else it },
                    focalTweet = if (conv.focalTweet.id == updated.id) updated else conv.focalTweet,
                    replies = conv.replies.map { if (it.id == updated.id) updated else it },
                ),
                quotes = cur.quotes.map { if (it.id == updated.id) updated else it },
            )
        }
    }

    companion object {
        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ThreadViewModel(repository) as T
        }
    }
}

// ===================== USER PROFILE & ARTICLES =====================

data class ProfileUiState(
    val user: XUser? = null,
    val selectedTab: ProfileTab = ProfileTab.POSTS,
    val tweets: List<Tweet> = emptyList(),
    val articles: List<Tweet> = emptyList(),
    val isPinnedBoost: Boolean = false,
    val isMuted: Boolean = false,
    val isBlocked: Boolean = false,
    val isLoading: Boolean = true,
    val isUserRefreshing: Boolean = false,
    val isTabLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchTab: SearchTab = SearchTab.LATEST,
    val searchResults: List<Tweet> = emptyList(),
    val searchNextCursor: String? = null,
    val isSearching: Boolean = false,
    val isLoadingMoreSearch: Boolean = false,
    val hasSearchedProfile: Boolean = false,
    val searchError: String? = null,
)

internal fun buildProfileSearchQuery(handle: String, query: String): String {
    val cleanHandle = handle.trim().removePrefix("@")
    val cleanQuery = query.trim()
    if (cleanHandle.isBlank()) return cleanQuery
    if (cleanQuery.isBlank()) return ""
    val alreadyScoped = Regex("(?i)(?:^|\\s)from:@?${Regex.escape(cleanHandle)}(?:\\s|$)")
        .containsMatchIn(cleanQuery)
    return if (alreadyScoped) cleanQuery else "from:$cleanHandle $cleanQuery"
}

internal fun filterProfileLocalTweets(
    tweets: List<Tweet>,
    query: String,
    tab: SearchTab,
): List<Tweet> {
    val tokens = query.trim().lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() && !it.startsWith("from:") && !it.startsWith("filter:") }
    if (tokens.isEmpty()) return emptyList()
    return tweets
        .distinctBy { it.id }
        .filter { tweet ->
            val searchable = buildString {
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
                searchable.contains(token) ||
                    (bare.isNotBlank() && (searchable.contains("#$bare") || searchable.contains(bare)))
            }
        }
        .let { list -> if (tab == SearchTab.MEDIA) list.filter { it.media.isNotEmpty() } else list }
        .let { list ->
            if (tab == SearchTab.TOP) {
                list.sortedByDescending { it.metrics.likes + it.metrics.reposts * 2L }
            } else {
                list.sortedByDescending { it.createdAtEpochMs }
            }
        }
}

class ProfileViewModel(private val repository: XRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _bookmarkFilter = MutableStateFlow("")
    val bookmarkFilter: StateFlow<String> = _bookmarkFilter.asStateFlow()

    private val _selectedBookmarkFolder = MutableStateFlow<String?>(null)
    val selectedBookmarkFolder: StateFlow<String?> = _selectedBookmarkFolder.asStateFlow()

    private val _isSyncingBookmarks = MutableStateFlow(false)
    val isSyncingBookmarks: StateFlow<Boolean> = _isSyncingBookmarks.asStateFlow()

    private val _isLoadingMoreBookmarks = MutableStateFlow(false)
    val isLoadingMoreBookmarks: StateFlow<Boolean> = _isLoadingMoreBookmarks.asStateFlow()

    private val _hasMoreRemoteBookmarks = MutableStateFlow(true)
    val hasMoreRemoteBookmarks: StateFlow<Boolean> = _hasMoreRemoteBookmarks.asStateFlow()

    private val allBookmarksFlow = repository.bookmarkRepository.observeBookmarks(query = "")

    val totalBookmarksCount: StateFlow<Int> = combine(allBookmarksFlow, _bookmarkFilter) { all, _ -> all.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val bookmarkFolders: StateFlow<List<Pair<String, Int>>> = combine(allBookmarksFlow, _selectedBookmarkFolder) { all, _ ->
        computeBookmarkFolders(all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val savedBookmarks: StateFlow<List<Pair<BookmarkEntity, Tweet>>> = combine(
        _bookmarkFilter.flatMapLatest { query -> repository.bookmarkRepository.observeBookmarks(query = query) },
        _selectedBookmarkFolder,
    ) { items, folder ->
        val filtered = if (folder.isNullOrBlank()) {
            items
        } else {
            items.filter { it.first.folderTag.equals(folder, ignoreCase = true) }
        }
        filtered.sortedByDescending { it.first.savedAtEpochMs }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var viewingOwnProfile = false
    private var hasAutoSyncedBookmarks = false
    private var tabLoadJob: Job? = null
    private var refreshJob: Job? = null
    private var profileSearchJob: Job? = null
    private var moderationObserveJob: Job? = null
    private var lastLoadedHandle: String? = null

    init {
        viewModelScope.launch {
            repository.pollUpdates.collect { update ->
                fun patch(item: Tweet): Tweet = when {
                    item.id == update.tweetId -> item.copy(poll = update.poll)
                    item.quotedTweet?.id == update.tweetId -> item.copy(
                        quotedTweet = item.quotedTweet.copy(poll = update.poll)
                    )
                    else -> item
                }
                _uiState.update { cur ->
                    val changed = cur.tweets.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId } ||
                        cur.articles.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId } ||
                        cur.searchResults.any { it.id == update.tweetId || it.quotedTweet?.id == update.tweetId }
                    if (!changed) cur else cur.copy(
                        tweets = cur.tweets.map(::patch),
                        articles = cur.articles.map(::patch),
                        searchResults = cur.searchResults.map(::patch),
                    )
                }
            }
        }
    }

    fun updateBookmarkFilter(query: String) {
        _bookmarkFilter.value = query
    }

    fun selectBookmarkFolder(folder: String?) {
        _selectedBookmarkFolder.value = folder?.takeIf { it.isNotBlank() }
    }

    fun moveBookmarkToFolder(tweetId: String, folderTag: String) {
        val clean = folderTag.trim().ifBlank { "Saved" }
        viewModelScope.launch {
            repository.updateBookmarkFolder(tweetId, clean)
            _events.tryEmit("Moved to $clean")
        }
    }

    fun syncBookmarksFromX() {
        if (_isSyncingBookmarks.value) return
        hasAutoSyncedBookmarks = true
        viewModelScope.launch {
            _isSyncingBookmarks.value = true
            repository.syncRemoteBookmarks(loadMore = false)
                .onSuccess { page ->
                    _hasMoreRemoteBookmarks.value = page.hasMore
                    _events.tryEmit("Synced ${page.syncedCount} newest bookmarks")
                }
                .onFailure { err ->
                    _events.tryEmit(err.message ?: "Sign in to sync X bookmarks")
                }
            _isSyncingBookmarks.value = false
        }
    }

    fun loadMoreBookmarks() {
        if (_isSyncingBookmarks.value || _isLoadingMoreBookmarks.value || !_hasMoreRemoteBookmarks.value) return
        viewModelScope.launch {
            _isLoadingMoreBookmarks.value = true
            val result = if (repository.hasMoreRemoteBookmarks()) {
                repository.syncRemoteBookmarks(loadMore = true)
            } else {
                val firstPage = repository.syncRemoteBookmarks(loadMore = false)
                if (firstPage.isSuccess && repository.hasMoreRemoteBookmarks()) {
                    repository.syncRemoteBookmarks(loadMore = true)
                } else {
                    firstPage
                }
            }
            result.onSuccess { page ->
                _hasMoreRemoteBookmarks.value = page.hasMore
            }.onFailure {
                _hasMoreRemoteBookmarks.value = false
            }
            _isLoadingMoreBookmarks.value = false
        }
    }

    fun loadSelfProfile(account: AccountEntity) {
        viewingOwnProfile = true
        viewModelScope.launch {
            val isPlaceholder = account.handle.isBlank() || account.handle == "x_user" || account.handle == "x_account"
            val currentTab = _uiState.value.selectedTab

            // 1. Instant Room cache hydration (< 20ms) so profile NEVER reloads from an empty page on startup
            val cachedUser = repository.getCachedUserProfile(handle = account.handle, userId = account.userId)
            val displayUser = cachedUser ?: if (!isPlaceholder) {
                XUser(
                    id = account.userId,
                    handle = account.handle,
                    displayName = account.displayName.ifBlank { account.handle },
                    avatarUrl = account.avatarUrl,
                    bannerUrl = account.bannerUrl,
                )
            } else {
                null
            }

            if (displayUser != null) {
                val cachedTabItems = if (currentTab != ProfileTab.BOOKMARKS) {
                    repository.getCachedProfileTabTweets(displayUser.handle, currentTab)
                } else {
                    emptyList()
                }
                _uiState.update { cur ->
                    cur.copy(
                        user = displayUser,
                        tweets = if (currentTab != ProfileTab.ARTICLES && currentTab != ProfileTab.BOOKMARKS && cachedTabItems.isNotEmpty()) {
                            cachedTabItems
                        } else cur.tweets,
                        articles = if (currentTab == ProfileTab.ARTICLES && cachedTabItems.isNotEmpty()) {
                            cachedTabItems
                        } else cur.articles,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                // Kick off tab content refresh immediately in parallel if we already have a real user id
                if (displayUser.id.all(Char::isDigit) && displayUser.id.isNotBlank()) {
                    loadTabContent(displayUser, currentTab)
                }
            } else {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            // 2. Refresh latest profile stats/bio in background without wiping visible cached UI
            repository.refreshActiveAccountProfile()
                .onSuccess { freshUser ->
                    val pinned = repository.isCreatorPinned(freshUser.handle)
                    val tabAlreadyStarted = displayUser != null &&
                        displayUser.id == freshUser.id &&
                        displayUser.id.all(Char::isDigit) &&
                        displayUser.id.isNotBlank()
                    _uiState.update {
                        it.copy(
                            user = freshUser,
                            isPinnedBoost = pinned,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                    if (!tabAlreadyStarted) {
                        loadTabContent(freshUser, _uiState.value.selectedTab)
                    }
                }
                .onFailure { err ->
                    _uiState.update { cur ->
                        cur.copy(
                            isLoading = false,
                            errorMessage = if (cur.user == null) err.message ?: "Unable to load your X profile" else null,
                        )
                    }
                }
        }
    }

    fun loadProfile(handle: String, activeAccountHandle: String? = null) {
        val cleanHandle = handle.trim().removePrefix("@")
        if (!lastLoadedHandle.equals(cleanHandle, ignoreCase = true)) {
            closeSearch()
        }
        lastLoadedHandle = cleanHandle
        viewingOwnProfile = !activeAccountHandle.isNullOrBlank() &&
            activeAccountHandle.equals(cleanHandle, ignoreCase = true)
        if (!viewingOwnProfile && cleanHandle.isNotBlank()) {
            observeModerationForHandle(cleanHandle)
        } else {
            moderationObserveJob?.cancel()
            _uiState.update { it.copy(isMuted = false, isBlocked = false) }
        }
        viewModelScope.launch {
            val safeTab = if (!viewingOwnProfile && (_uiState.value.selectedTab == ProfileTab.LIKES || _uiState.value.selectedTab == ProfileTab.BOOKMARKS)) {
                ProfileTab.POSTS
            } else {
                _uiState.value.selectedTab
            }

            // 1. Hydrate from Room/RAM cache first for sub-50ms display
            val cachedUser = repository.getCachedUserProfile(handle = handle)
            val cachedTabItems = if (cachedUser != null && safeTab != ProfileTab.BOOKMARKS) {
                repository.getCachedProfileTabTweets(cachedUser.handle, safeTab)
            } else {
                emptyList()
            }
            val pinned = repository.isCreatorPinned(handle)

            if (cachedUser != null) {
                _uiState.update {
                    it.copy(
                        user = cachedUser,
                        selectedTab = safeTab,
                        tweets = if (safeTab != ProfileTab.ARTICLES && safeTab != ProfileTab.BOOKMARKS) cachedTabItems else it.tweets,
                        articles = if (safeTab == ProfileTab.ARTICLES) cachedTabItems else it.articles,
                        isPinnedBoost = pinned && !cachedUser.isBlocked,
                        isMuted = cachedUser.isMuted,
                        isBlocked = cachedUser.isBlocked,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                if (cachedUser.id.all(Char::isDigit) && cachedUser.id.isNotBlank()) {
                    loadTabContent(cachedUser, safeTab)
                }
            } else {
                _uiState.update { cur ->
                    cur.copy(isLoading = true, errorMessage = null, selectedTab = safeTab, isPinnedBoost = pinned)
                }
            }

            // 2. Refresh from network
            repository.loadUserProfile(handle, forceRefresh = cachedUser != null)
                .onSuccess { freshUser ->
                    val tabAlreadyStarted = cachedUser != null &&
                        cachedUser.id == freshUser.id &&
                        cachedUser.id.all(Char::isDigit) &&
                        cachedUser.id.isNotBlank()
                    _uiState.update {
                        it.copy(
                            user = freshUser,
                            isPinnedBoost = pinned && !freshUser.isBlocked,
                            isMuted = freshUser.isMuted,
                            isBlocked = freshUser.isBlocked,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                    if (!tabAlreadyStarted) {
                        loadTabContent(freshUser, _uiState.value.selectedTab)
                    }
                }
                .onFailure { err ->
                    _uiState.update { cur ->
                        cur.copy(
                            isLoading = false,
                            errorMessage = if (cur.user == null) err.message ?: "Profile unavailable" else null,
                        )
                    }
                }
        }
    }

    private fun observeModerationForHandle(handle: String) {
        moderationObserveJob?.cancel()
        moderationObserveJob = viewModelScope.launch {
            repository.observeUserModeration(handle).collect { mod ->
                val muted = mod?.isMuted ?: false
                val blocked = mod?.isBlocked ?: false
                _uiState.update { cur ->
                    cur.copy(
                        isMuted = muted,
                        isBlocked = blocked,
                        isPinnedBoost = if (blocked) false else cur.isPinnedBoost,
                        user = cur.user?.copy(
                            isMuted = muted,
                            isBlocked = blocked,
                            isFollowing = if (blocked) false else cur.user.isFollowing,
                        ),
                    )
                }
            }
        }
    }

    fun refresh() {
        val currentUser = _uiState.value.user
        val handle = currentUser?.handle?.takeIf { it.isNotBlank() } ?: lastLoadedHandle
        val tab = _uiState.value.selectedTab

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isUserRefreshing = true, errorMessage = null) }

            val profileResult = if (viewingOwnProfile) {
                repository.refreshActiveAccountProfile()
            } else if (!handle.isNullOrBlank()) {
                repository.loadUserProfile(handle, forceRefresh = true)
            } else {
                Result.failure(IllegalStateException("Profile unavailable"))
            }

            val resolvedUser = profileResult.getOrNull() ?: currentUser
            if (resolvedUser != null) {
                val pinned = repository.isCreatorPinned(resolvedUser.handle)
                _uiState.update {
                    it.copy(
                        user = resolvedUser,
                        isPinnedBoost = pinned,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
                refreshTabForce(resolvedUser, tab)
            } else {
                val msg = profileResult.exceptionOrNull()?.message ?: "Unable to refresh profile"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isUserRefreshing = false,
                        errorMessage = msg,
                    )
                }
                _events.tryEmit(msg)
            }
        }
    }

    private suspend fun refreshTabForce(user: XUser, tab: ProfileTab) {
        when (tab) {
            ProfileTab.BOOKMARKS -> {
                if (viewingOwnProfile) {
                    _isSyncingBookmarks.value = true
                    repository.syncRemoteBookmarks(loadMore = false)
                        .onSuccess { page ->
                            _hasMoreRemoteBookmarks.value = page.hasMore
                        }
                        .onFailure { err ->
                            _events.tryEmit(err.message ?: "Unable to sync bookmarks")
                        }
                    _isSyncingBookmarks.value = false
                }
                _uiState.update { it.copy(isUserRefreshing = false, isTabLoading = false) }
            }
            ProfileTab.ARTICLES -> {
                repository.loadUserArticles(user, forceRefresh = true)
                    .onSuccess { articles ->
                        _uiState.update {
                            it.copy(
                                articles = articles,
                                isUserRefreshing = false,
                                isTabLoading = false,
                            )
                        }
                    }
                    .onFailure { err ->
                        _uiState.update { it.copy(isUserRefreshing = false, isTabLoading = false) }
                        _events.tryEmit(err.message ?: "Unable to refresh Articles")
                    }
            }
            else -> {
                val op = when (tab) {
                    ProfileTab.POSTS -> "UserTweets"
                    ProfileTab.REPLIES -> "UserTweetsAndReplies"
                    ProfileTab.MEDIA -> "UserMedia"
                    ProfileTab.LIKES -> "Likes"
                    ProfileTab.ARTICLES, ProfileTab.BOOKMARKS -> "UserTweets"
                }
                repository.loadUserTweets(user, op, cacheTab = tab, forceRefresh = true)
                    .onSuccess { list ->
                        val filtered = if (tab == ProfileTab.MEDIA) list.filter { it.media.isNotEmpty() } else list
                        _uiState.update {
                            it.copy(
                                tweets = filtered,
                                isUserRefreshing = false,
                                isTabLoading = false,
                            )
                        }
                    }
                    .onFailure { err ->
                        _uiState.update { it.copy(isUserRefreshing = false, isTabLoading = false) }
                        _events.tryEmit(err.message ?: "Unable to refresh posts")
                    }
            }
        }
    }

    fun selectTab(tab: ProfileTab) {
        // X hides Likes & Bookmarks on other users' profiles
        if (!viewingOwnProfile && (tab == ProfileTab.LIKES || tab == ProfileTab.BOOKMARKS)) {
            return
        }
        _uiState.update { it.copy(selectedTab = tab) }
        _uiState.value.user?.let { loadTabContent(it, tab) }
    }

    private fun loadTabContent(user: XUser, tab: ProfileTab, forceRefresh: Boolean = false) {
        if (tab == ProfileTab.BOOKMARKS) {
            // Driven reactively from local Room BookmarkRepository; sync newest page once per session
            if (viewingOwnProfile && !_isSyncingBookmarks.value && (forceRefresh || !hasAutoSyncedBookmarks || savedBookmarks.value.isEmpty())) {
                syncBookmarksFromX()
            }
            return
        }
        tabLoadJob?.cancel()
        tabLoadJob = viewModelScope.launch {
            val cached = repository.getCachedProfileTabTweets(user.handle, tab)
            if (tab == ProfileTab.ARTICLES) {
                _uiState.update { cur ->
                    cur.copy(
                        articles = if (cached.isNotEmpty()) cached else cur.articles,
                        isTabLoading = cached.isEmpty(),
                    )
                }
                repository.loadUserArticles(user, forceRefresh = forceRefresh)
                    .onSuccess { articles ->
                        _uiState.update { it.copy(articles = articles, isTabLoading = false) }
                    }
                    .onFailure { err ->
                        _uiState.update { it.copy(isTabLoading = false) }
                        _events.tryEmit(err.message ?: "Unable to load Articles")
                    }
                return@launch
            }

            _uiState.update { cur ->
                cur.copy(
                    tweets = if (cached.isNotEmpty()) cached else emptyList(),
                    isTabLoading = cached.isEmpty(),
                )
            }
            val op = when (tab) {
                ProfileTab.POSTS -> "UserTweets"
                ProfileTab.REPLIES -> "UserTweetsAndReplies"
                ProfileTab.MEDIA -> "UserMedia"
                ProfileTab.LIKES -> "Likes"
                ProfileTab.ARTICLES, ProfileTab.BOOKMARKS -> "UserTweets"
            }
            repository.loadUserTweets(user, op, cacheTab = tab, forceRefresh = forceRefresh)
                .onSuccess { list ->
                    val filtered = if (tab == ProfileTab.MEDIA) list.filter { it.media.isNotEmpty() } else list
                    _uiState.update { it.copy(tweets = filtered, isTabLoading = false) }
                }
                .onFailure {
                    _uiState.update { it.copy(isTabLoading = false) }
                }
        }
    }

    fun toggleFollow() {
        val u = _uiState.value.user ?: return
        val next = !u.isFollowing
        _uiState.update { it.copy(user = u.copy(isFollowing = next)) }
        viewModelScope.launch {
            repository.setFollow(u, next).onFailure { err ->
                _uiState.update { it.copy(user = u) }
                _events.tryEmit(err.message ?: "Sign in to follow")
            }.onSuccess {
                _events.tryEmit(if (next) "Following @${u.handle}" else "Unfollowed @${u.handle}")
            }
        }
    }

    fun togglePinBoost() {
        val u = _uiState.value.user ?: return
        viewModelScope.launch {
            repository.togglePinCreatorBoost(u.handle, u.displayName)
            val nowPinned = repository.isCreatorPinned(u.handle)
            _uiState.update { it.copy(isPinnedBoost = nowPinned) }
            _events.tryEmit(
                if (nowPinned) "Boosted @${u.handle} in For You"
                else "Removed @${u.handle} boost"
            )
        }
    }

    fun toggleMuteUser() {
        val u = _uiState.value.user ?: return
        val nextMute = !_uiState.value.isMuted
        _uiState.update {
            it.copy(
                isMuted = nextMute,
                user = u.copy(isMuted = nextMute),
            )
        }
        viewModelScope.launch {
            repository.setMuteUser(u, nextMute)
                .onSuccess { syncedWithX ->
                    val suffix = if (syncedWithX) "" else " (local filter)"
                    _events.tryEmit(
                        if (nextMute) "Muted @${u.handle} · Hidden from feeds$suffix"
                        else "Unmuted @${u.handle}$suffix"
                    )
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isMuted = !nextMute, user = u) }
                    _events.tryEmit(err.message ?: "Unable to update mute setting")
                }
        }
    }

    fun setBlockUser(block: Boolean) {
        val u = _uiState.value.user ?: return
        _uiState.update {
            it.copy(
                isBlocked = block,
                isPinnedBoost = if (block) false else it.isPinnedBoost,
                user = u.copy(isBlocked = block, isFollowing = if (block) false else u.isFollowing),
            )
        }
        viewModelScope.launch {
            repository.setBlockUser(u, block)
                .onSuccess { syncedWithX ->
                    val suffix = if (syncedWithX) "" else " (local filter)"
                    _events.tryEmit(
                        if (block) "Blocked @${u.handle} · Excluded from feeds$suffix"
                        else "Unblocked @${u.handle}$suffix"
                    )
                    if (!block) {
                        loadTabContent(u.copy(isBlocked = false), _uiState.value.selectedTab, forceRefresh = true)
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isBlocked = !block, user = u) }
                    _events.tryEmit(err.message ?: "Unable to update block setting")
                }
        }
    }

    fun toggleLike(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleLike(tweet).onSuccess { updated ->
                replaceProfileTweet(updated)
            }
        }
    }

    fun toggleRepost(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleRepost(tweet).onSuccess { updated ->
                replaceProfileTweet(updated)
            }
        }
    }

    fun toggleBookmark(tweet: Tweet) {
        viewModelScope.launch {
            repository.toggleBookmark(tweet).onSuccess { updated ->
                replaceProfileTweet(updated)
                _events.tryEmit(
                    if (updated.viewerState.isBookmarked) "Saved for offline reading"
                    else "Removed from Bookmarks"
                )
            }
        }
    }

    fun openSearch() {
        _uiState.update { it.copy(isSearchOpen = true) }
    }

    fun closeSearch() {
        profileSearchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchOpen = false,
                searchQuery = "",
                searchResults = emptyList(),
                searchNextCursor = null,
                isSearching = false,
                isLoadingMoreSearch = false,
                hasSearchedProfile = false,
                searchError = null,
            )
        }
    }

    fun updateSearchQuery(query: String) {
        val trimmed = query.trim()
        val state = _uiState.value
        val localMatches = if (trimmed.isNotBlank()) {
            filterProfileLocalTweets(state.tweets + state.articles, trimmed, state.searchTab)
        } else {
            emptyList()
        }
        profileSearchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = query,
                searchResults = if (trimmed.isBlank()) emptyList() else localMatches,
                searchNextCursor = null,
                isSearching = trimmed.length >= PROFILE_MIN_AUTO_SEARCH_LEN,
                hasSearchedProfile = if (trimmed.isBlank()) false else localMatches.isNotEmpty(),
                searchError = null,
            )
        }
        if (trimmed.length >= PROFILE_MIN_AUTO_SEARCH_LEN) {
            profileSearchJob = viewModelScope.launch {
                delay(PROFILE_SEARCH_DEBOUNCE_MS)
                executeProfileSearch(trimmed, _uiState.value.searchTab)
            }
        }
    }

    fun selectSearchTab(tab: SearchTab) {
        if (tab == SearchTab.PEOPLE) return
        val trimmed = _uiState.value.searchQuery.trim()
        val localMatches = if (trimmed.isNotBlank()) {
            filterProfileLocalTweets(_uiState.value.tweets + _uiState.value.articles, trimmed, tab)
        } else {
            emptyList()
        }
        _uiState.update {
            it.copy(
                searchTab = tab,
                searchResults = if (trimmed.isBlank()) emptyList() else localMatches,
                searchNextCursor = null,
                searchError = null,
            )
        }
        if (trimmed.isNotBlank()) {
            submitProfileSearch()
        }
    }

    fun submitProfileSearch() {
        val trimmed = _uiState.value.searchQuery.trim()
        if (trimmed.isBlank()) return
        profileSearchJob?.cancel()
        profileSearchJob = viewModelScope.launch {
            executeProfileSearch(trimmed, _uiState.value.searchTab)
        }
    }

    private suspend fun executeProfileSearch(query: String, tab: SearchTab) {
        val handle = _uiState.value.user?.handle?.takeIf { it.isNotBlank() } ?: lastLoadedHandle.orEmpty()
        val scopedQuery = buildProfileSearchQuery(handle, query)
        if (scopedQuery.isBlank()) return

        val localMatches = filterProfileLocalTweets(_uiState.value.tweets + _uiState.value.articles, query, tab)
        val cachedPage = repository.peekCachedSearch(scopedQuery, tab)
        if (cachedPage != null) {
            val merged = mergeProfileSearchResults(cachedPage.tweets, localMatches, tab)
            _uiState.update {
                it.copy(
                    searchResults = merged,
                    searchNextCursor = cachedPage.nextCursor,
                    isSearching = false,
                    hasSearchedProfile = true,
                    searchError = null,
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    searchResults = localMatches,
                    isSearching = true,
                    hasSearchedProfile = true,
                    searchError = null,
                )
            }
        }

        repository.searchPosts(
            query = scopedQuery,
            tab = tab,
            cursor = null,
            recordHistory = false,
        ).onSuccess { page ->
            val merged = mergeProfileSearchResults(page.tweets, localMatches, tab)
            _uiState.update {
                it.copy(
                    searchResults = merged,
                    searchNextCursor = page.nextCursor,
                    isSearching = false,
                    hasSearchedProfile = true,
                    searchError = null,
                )
            }
        }.onFailure { err ->
            _uiState.update { cur ->
                cur.copy(
                    searchResults = localMatches,
                    searchNextCursor = null,
                    isSearching = false,
                    hasSearchedProfile = true,
                    searchError = if (localMatches.isEmpty()) err.message else null,
                )
            }
        }
    }

    fun loadMoreProfileSearch() {
        val state = _uiState.value
        val cursor = state.searchNextCursor ?: return
        val query = state.searchQuery.trim()
        if (query.isBlank() || state.isSearching || state.isLoadingMoreSearch) return
        val handle = state.user?.handle?.takeIf { it.isNotBlank() } ?: lastLoadedHandle.orEmpty()
        val scopedQuery = buildProfileSearchQuery(handle, query)
        if (scopedQuery.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreSearch = true) }
            repository.searchPosts(
                query = scopedQuery,
                tab = state.searchTab,
                cursor = cursor,
                recordHistory = false,
            ).onSuccess { page ->
                val incoming = if (state.searchTab == SearchTab.MEDIA) {
                    page.tweets.filter { it.media.isNotEmpty() }
                } else {
                    page.tweets
                }
                _uiState.update { cur ->
                    cur.copy(
                        searchResults = (cur.searchResults + incoming).distinctBy { it.id },
                        searchNextCursor = page.nextCursor,
                        isLoadingMoreSearch = false,
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(isLoadingMoreSearch = false) }
            }
        }
    }

    private fun mergeProfileSearchResults(
        remote: List<Tweet>,
        local: List<Tweet>,
        tab: SearchTab,
    ): List<Tweet> {
        val filteredRemote = if (tab == SearchTab.MEDIA) remote.filter { it.media.isNotEmpty() } else remote
        return (filteredRemote + local).distinctBy { it.id }
    }

    private fun replaceProfileTweet(updated: Tweet) {
        _uiState.update { cur ->
            cur.copy(
                tweets = cur.tweets.map { if (it.id == updated.id) updated else it },
                articles = cur.articles.map { if (it.id == updated.id) updated else it },
                searchResults = cur.searchResults.map { if (it.id == updated.id) updated else it },
            )
        }
    }

    companion object {
        private const val PROFILE_SEARCH_DEBOUNCE_MS = 300L
        private const val PROFILE_MIN_AUTO_SEARCH_LEN = 2

        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(repository) as T
        }
    }
}

// ===================== COMPOSER & DRAFTS =====================

data class ComposerUiState(
    val text: String = "",
    val replyToTweetId: String? = null,
    val replyToHandle: String? = null,
    val quoteTweetId: String? = null,
    val quoteTweetAuthor: String? = null,
    val quoteTweetSnippet: String? = null,
    val selectedMediaUris: List<Uri> = emptyList(),
    val isPosting: Boolean = false,
    val errorMessage: String? = null,
)

class ComposerViewModel(private val repository: XRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ComposerUiState())
    val uiState: StateFlow<ComposerUiState> = _uiState.asStateFlow()

    val drafts: StateFlow<List<DraftTweetEntity>> = repository.draftsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun configure(
        replyToId: String? = null,
        replyToHandle: String? = null,
        quoteId: String? = null,
        quoteAuthor: String? = null,
        quoteSnippet: String? = null,
        prefillText: String? = null,
    ) {
        _uiState.update {
            it.copy(
                text = if (!prefillText.isNullOrBlank() && it.text.isBlank()) prefillText else it.text,
                replyToTweetId = replyToId,
                replyToHandle = replyToHandle,
                quoteTweetId = quoteId,
                quoteTweetAuthor = quoteAuthor,
                quoteTweetSnippet = quoteSnippet,
                errorMessage = null,
            )
        }
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value, errorMessage = null) }
    }

    fun addMediaUris(uris: List<Uri>) {
        _uiState.update { cur ->
            cur.copy(selectedMediaUris = (cur.selectedMediaUris + uris).distinct().take(4))
        }
    }

    fun removeMediaUri(uri: Uri) {
        _uiState.update { cur ->
            cur.copy(selectedMediaUris = cur.selectedMediaUris.filterNot { it == uri })
        }
    }

    fun loadDraft(draft: DraftTweetEntity) {
        _uiState.update {
            it.copy(
                text = draft.text,
                replyToTweetId = draft.replyToTweetId,
                replyToHandle = draft.replyToHandle,
                quoteTweetId = draft.quoteTweetId,
                selectedMediaUris = draft.mediaUrisCsv.split(",")
                    .filter { s -> s.isNotBlank() }
                    .mapNotNull { s -> runCatching { s.toUri() }.getOrNull() },
            )
        }
    }

    fun saveDraft(onSaved: () -> Unit) {
        val state = _uiState.value
        if (state.text.isBlank() && state.selectedMediaUris.isEmpty()) return
        viewModelScope.launch {
            repository.saveDraft(
                DraftTweetEntity(
                    text = state.text,
                    replyToTweetId = state.replyToTweetId,
                    replyToHandle = state.replyToHandle,
                    quoteTweetId = state.quoteTweetId,
                    quoteTweetUrl = state.quoteTweetId?.let { "https://x.com/${state.quoteTweetAuthor ?: "i"}/status/$it" },
                    mediaUrisCsv = state.selectedMediaUris.joinToString(",") { it.toString() },
                )
            )
            onSaved()
        }
    }

    fun deleteDraft(id: Long) {
        viewModelScope.launch { repository.deleteDraft(id) }
    }

    fun publish(onPublished: (Tweet?) -> Unit) {
        val state = _uiState.value
        if (state.text.isBlank() && state.selectedMediaUris.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Write a post or attach an image first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            val quoteUrl = state.quoteTweetId?.let {
                "https://x.com/${state.quoteTweetAuthor?.removePrefix("@") ?: "i"}/status/$it"
            }
            repository.publishTweet(
                text = state.text.trim(),
                replyToTweetId = state.replyToTweetId,
                replyToHandle = state.replyToHandle,
                quoteTweetUrl = quoteUrl,
                mediaUris = state.selectedMediaUris,
            ).onSuccess { created ->
                _uiState.value = ComposerUiState()
                onPublished(created)
            }.onFailure { err ->
                _uiState.update { it.copy(isPosting = false, errorMessage = err.message ?: "Failed to publish post") }
            }
        }
    }

    companion object {
        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ComposerViewModel(repository) as T
        }
    }
}

// ===================== SETTINGS & DIAGNOSTICS =====================

class SettingsViewModel(private val repository: XRepository) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = repository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val accounts: StateFlow<List<AccountEntity>> = repository.allAccountsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mutedRules: StateFlow<List<MutedRuleEntity>> = repository.mutedRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val userModerations: StateFlow<List<UserModerationEntity>> = repository.userModerationsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val affinities: StateFlow<List<AuthorAffinityEntity>> = repository.affinitiesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val endpointDiagnostics: StateFlow<List<EndpointHashEntity>> = repository.endpointDiagnosticsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshingRegistry = MutableStateFlow(false)
    val isRefreshingRegistry: StateFlow<Boolean> = _isRefreshingRegistry.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repository.ensureEndpointRegistryReady() }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repository.preferencesRepository.setThemeMode(mode) }
    fun setAmoledDark(enabled: Boolean) = viewModelScope.launch { repository.preferencesRepository.setAmoledDark(enabled) }
    fun setUseDynamicColor(enabled: Boolean) = viewModelScope.launch { repository.preferencesRepository.setUseDynamicColor(enabled) }
    fun setAccentPreset(preset: AccentPreset) = viewModelScope.launch { repository.preferencesRepository.setAccentPreset(preset) }
    fun setFeedDensity(density: FeedDensity) = viewModelScope.launch { repository.preferencesRepository.setFeedDensity(density) }
    fun setImageQualityMode(mode: ImageQualityMode) = viewModelScope.launch { repository.preferencesRepository.setImageQualityMode(mode) }
    fun setSwipeRight(action: SwipeAction) = viewModelScope.launch { repository.preferencesRepository.setSwipeRightAction(action) }
    fun setSwipeLeft(action: SwipeAction) = viewModelScope.launch { repository.preferencesRepository.setSwipeLeftAction(action) }
    fun setRecencyWeight(value: Float) = viewModelScope.launch { repository.preferencesRepository.setRecencyWeight(value) }
    fun setAffinityWeight(value: Float) = viewModelScope.launch { repository.preferencesRepository.setAffinityWeight(value) }
    fun setBlurSensitiveMedia(enabled: Boolean) = viewModelScope.launch { repository.preferencesRepository.setBlurSensitiveMedia(enabled) }
    fun setExcludeRiskyAndNsfw(enabled: Boolean) = viewModelScope.launch { repository.preferencesRepository.setExcludeRiskyAndNsfw(enabled) }
    fun setShowLocalRankBadges(enabled: Boolean) = viewModelScope.launch { repository.preferencesRepository.setShowLocalRankBadges(enabled) }
    fun setCustomProxyBaseUrl(url: String) = viewModelScope.launch {
        val cleaned = url.trim()
        repository.preferencesRepository.setCustomProxyBaseUrl(cleaned)
        repository.clearPublicFallbackCaches()
        val activeHost = com.example.data.remote.NitterHtmlParser.displayHost(cleaned)
        val msg = if (cleaned.isBlank()) {
            "Fallback reset to default ($activeHost)"
        } else {
            "Fallback Nitter set to $activeHost"
        }
        _events.tryEmit(msg)
    }
    fun setPublicHandlesCsv(csv: String) = viewModelScope.launch {
        repository.preferencesRepository.setPublicPreviewHandlesCsv(csv)
        _events.tryEmit("Public preview handles updated")
    }

    fun addMutedRule(value: String, isHandle: Boolean) = viewModelScope.launch {
        repository.addMutedRule(value, isHandle)
        _events.tryEmit(if (isHandle) "Muted @${value.trim().removePrefix("@")}" else "Added muted keyword")
    }

    fun removeMutedRule(id: Long) = viewModelScope.launch { repository.removeMutedRule(id) }

    fun unmuteUser(entity: UserModerationEntity) = viewModelScope.launch {
        repository.unmuteUserModeration(entity)
        _events.tryEmit("Unmuted @${entity.handleLower}")
    }

    fun unblockUser(entity: UserModerationEntity) = viewModelScope.launch {
        repository.unblockUserModeration(entity)
        _events.tryEmit("Unblocked @${entity.handleLower}")
    }

    fun resetLocalForYou() = viewModelScope.launch {
        repository.resetLocalForYouModel()
        _events.tryEmit("On-device For You affinity model reset")
    }

    fun refreshEndpointRegistry() = viewModelScope.launch {
        _isRefreshingRegistry.value = true
        repository.refreshEndpointRegistry()
            .onSuccess { count -> _events.tryEmit("Synced $count X GraphQL query hashes") }
            .onFailure { err -> _events.tryEmit(err.message ?: "Manifest sync failed; using cached hashes") }
        _isRefreshingRegistry.value = false
    }

    fun switchAccount(userId: String) = viewModelScope.launch { repository.switchAccount(userId) }
    fun removeAccount(userId: String) = viewModelScope.launch { repository.removeAccount(userId) }

    companion object {
        fun factory(repository: XRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repository) as T
        }
    }
}
