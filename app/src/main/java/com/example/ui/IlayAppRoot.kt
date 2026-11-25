package com.example.ui

import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.AppContainer
import com.example.R
import com.example.data.local.DraftTweetEntity
import com.example.data.remote.AdaptiveImageQualitySelector
import com.example.data.remote.LocalImageQualityMode
import com.example.domain.model.MediaAttachment
import com.example.domain.model.QuotedTweet
import com.example.domain.model.Tweet
import com.example.domain.model.XArticle
import com.example.domain.model.XUser
import com.example.ui.components.FloatingNavItem
import com.example.ui.components.IlayFloatingBottomBar
import com.example.ui.components.IlayLinkRouter
import com.example.ui.components.InAppCustomTabsLauncher
import com.example.ui.components.InAppLinkDestination
import com.example.ui.components.LocalIlayChromeState
import com.example.ui.components.LocalTweetCardHost
import com.example.ui.components.MediaLightboxDialog
import com.example.ui.components.PendingDeepLink
import com.example.ui.components.QuotePostDialog
import com.example.ui.components.ScreenshotCaptureAction
import com.example.ui.components.ScreenshotCaptureRequest
import com.example.ui.components.TweetCardHost
import com.example.ui.components.TweetMediaSaver
import com.example.ui.components.TweetScreenshotCaptureHost
import com.example.ui.components.buildQuoteTweetUrl
import com.example.ui.components.rememberIlayChromeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.example.ui.screens.AccountSwitcherSheet
import com.example.ui.screens.ActivityScreen
import com.example.ui.screens.ArticleReaderScreen
import com.example.ui.screens.BookmarksScreen
import com.example.ui.screens.ComposerScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.InterestInspectorScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SettingsCategory
import com.example.ui.screens.SettingsCategoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ThreadDetailScreen
import com.example.ui.screens.XAuthDialog
import com.example.ui.theme.IlayForXTheme
import com.example.ui.theme.IlayMotion
import com.example.ui.viewmodel.ActivityViewModel
import com.example.ui.viewmodel.BookmarksViewModel
import com.example.ui.viewmodel.ComposerViewModel
import com.example.ui.viewmodel.ExploreViewModel
import com.example.ui.viewmodel.FeedViewModel
import com.example.ui.viewmodel.InterestInspectorViewModel
import com.example.ui.viewmodel.ProfileViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.ThreadViewModel

object NavRoutes {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val ACTIVITY = "activity"
    const val BOOKMARKS = "bookmarks"
    const val PROFILE_SELF = "profile_self"
    const val SETTINGS = "settings"
    const val SETTINGS_CATEGORY = "settings/{category}"
    const val INTEREST_INSPECTOR = "interest_inspector"
    const val THREAD = "thread/{tweetId}/{handle}"
    const val ARTICLE = "article/{tweetId}/{handle}?articleId={articleId}&url={url}"
    const val USER_PROFILE = "user/{handle}"
    const val COMPOSER = "composer?replyId={replyId}&replyHandle={replyHandle}&quoteId={quoteId}&quoteAuthor={quoteAuthor}&quoteSnippet={quoteSnippet}&prefillText={prefillText}"

    fun settingsCategory(category: SettingsCategory): String =
        "settings/${Uri.encode(category.routeArg)}"

    fun thread(tweetId: String, handle: String): String =
        "thread/${Uri.encode(tweetId)}/${Uri.encode(handle.ifBlank { "i" })}"

    fun article(
        tweetId: String,
        handle: String,
        articleId: String = "",
        rawUrl: String = "",
    ): String {
        val safeTweetId = tweetId.ifBlank { "article" }
        val safeHandle = handle.removePrefix("@").ifBlank { "i" }
        val params = buildList {
            if (articleId.isNotBlank()) add("articleId=${Uri.encode(articleId)}")
            if (rawUrl.isNotBlank()) add("url=${Uri.encode(rawUrl)}")
        }
        val base = "article/${Uri.encode(safeTweetId)}/${Uri.encode(safeHandle)}"
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    fun userProfile(handle: String): String =
        "user/${Uri.encode(handle.removePrefix("@"))}"

    fun composer(
        replyId: String? = null,
        replyHandle: String? = null,
        quoteId: String? = null,
        quoteAuthor: String? = null,
        quoteSnippet: String? = null,
        prefillText: String? = null,
    ): String {
        val params = buildList {
            replyId?.let { add("replyId=${Uri.encode(it)}") }
            replyHandle?.let { add("replyHandle=${Uri.encode(it)}") }
            quoteId?.let { add("quoteId=${Uri.encode(it)}") }
            quoteAuthor?.let { add("quoteAuthor=${Uri.encode(it)}") }
            quoteSnippet?.let { add("quoteSnippet=${Uri.encode(it.take(140))}") }
            prefillText?.let { add("prefillText=${Uri.encode(it.take(280))}") }
        }
        return if (params.isEmpty()) "composer" else "composer?${params.joinToString("&")}"
    }
}

private enum class TopDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(NavRoutes.HOME, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    ACTIVITY(NavRoutes.ACTIVITY, R.string.nav_activity, Icons.Filled.Notifications, Icons.Outlined.Notifications),
    BOOKMARKS(NavRoutes.BOOKMARKS, R.string.nav_bookmarks, Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
}

@Composable
fun IlayAppRoot(
    container: AppContainer,
    pendingDeepLinkFlow: StateFlow<PendingDeepLink?> = remember { MutableStateFlow(null) },
    onConsumeDeepLink: (Long) -> Unit = {},
) {
    val repo = container.repository
    val feedViewModel: FeedViewModel = viewModel(factory = FeedViewModel.factory(repo))
    val exploreViewModel: ExploreViewModel = viewModel(factory = ExploreViewModel.factory(repo))
    val activityViewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.factory(repo))
    val bookmarksViewModel: BookmarksViewModel = viewModel(factory = BookmarksViewModel.factory(repo))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(repo))

    val preferences by feedViewModel.preferences.collectAsStateWithLifecycle()
    val activeAccount by feedViewModel.activeAccount.collectAsStateWithLifecycle()
    val allAccounts by feedViewModel.allAccounts.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    var showAccountSwitcher by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var quoteTargetTweet by remember { mutableStateOf<Tweet?>(null) }
    var lightboxMedia by remember { mutableStateOf<Pair<List<MediaAttachment>, Int>?>(null) }
    var pendingScreenshotRequest by remember { mutableStateOf<ScreenshotCaptureRequest?>(null) }
    var screenshotPrepareJob by remember { mutableStateOf<Job?>(null) }
    var homeScrollToTopNonce by remember { mutableIntStateOf(0) }
    var customTabsToolbarArgb by remember { mutableIntStateOf(0xFF0E1117.toInt()) }
    var customTabsDarkTheme by remember { mutableStateOf(true) }

    val appContext = LocalContext.current
    val undoLabel = stringResource(R.string.snackbar_undo)
    val browserNoAppMsg = stringResource(R.string.browser_no_app_available)
    val screenshotPreparingMsg = stringResource(R.string.screenshot_preparing)
    val rootScope = rememberCoroutineScope()
    val chromeState = rememberIlayChromeState()

    LaunchedEffect(Unit) {
        feedViewModel.snackbarEvents.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }
    LaunchedEffect(undoLabel) {
        feedViewModel.feedbackSnackbars.collect { feedback ->
            val result = snackbarHostState.showSnackbar(
                message = feedback.message,
                actionLabel = if (feedback.undoToken != null) undoLabel else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && feedback.undoToken != null) {
                feedViewModel.undoFeedback(feedback.undoToken)
            }
        }
    }
    LaunchedEffect(Unit) {
        bookmarksViewModel.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }
    LaunchedEffect(Unit) {
        settingsViewModel.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    val isTopLevel = TopDestination.entries.any { it.route == currentRoute }

    // Freeze shared top-level chrome state whenever a detail screen (thread, profile, settings,
    // search, composer) is active so opening/returning never uncollapses or mutates the feed bars.
    SideEffect {
        chromeState.isScrollTrackingEnabled = isTopLevel
    }

    fun navigateTop(route: String) {
        val activeRoute = navController.currentBackStackEntry?.destination?.route
        if (route == NavRoutes.HOME && activeRoute == NavRoutes.HOME) {
            homeScrollToTopNonce++
            chromeState.expand()
            return
        }
        if (route == activeRoute) {
            chromeState.expand()
            return
        }
        chromeState.expand()
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openThread(tweet: Tweet) {
        repo.seedThreadFocalTweet(tweet)
        repo.recordThreadOpen(tweet)
        navController.navigate(NavRoutes.thread(tweet.id, tweet.author.handle))
    }

    fun openArticle(tweet: Tweet, article: XArticle) {
        repo.seedArticle(tweet, article)
        navController.navigate(
            NavRoutes.article(
                tweetId = tweet.id,
                handle = tweet.author.handle,
                articleId = article.id,
                rawUrl = article.articleUrl,
            )
        )
    }

    fun openProfile(handle: String) {
        val clean = handle.trim().removePrefix("@")
        if (clean.isNotBlank()) {
            navController.navigate(NavRoutes.userProfile(clean))
        }
    }

    fun openHashtag(hashtag: String) {
        val query = hashtag.trim()
        if (query.isBlank()) return
        exploreViewModel.submitSearch(query)
        if (navController.currentBackStackEntry?.destination?.route != NavRoutes.EXPLORE) {
            navController.navigate(NavRoutes.EXPLORE) {
                launchSingleTop = true
            }
        }
    }

    fun openInAppDestination(dest: InAppLinkDestination) {
        when (dest) {
            is InAppLinkDestination.Home -> navigateTop(NavRoutes.HOME)
            is InAppLinkDestination.Activity -> navigateTop(NavRoutes.ACTIVITY)
            is InAppLinkDestination.Bookmarks -> navigateTop(NavRoutes.BOOKMARKS)
            is InAppLinkDestination.Profile -> openProfile(dest.handle)
            is InAppLinkDestination.HashtagSearch -> openHashtag(dest.query)
            is InAppLinkDestination.Thread -> {
                navController.navigate(NavRoutes.thread(dest.tweetId, dest.authorHandle)) {
                    launchSingleTop = true
                }
            }
            is InAppLinkDestination.Article -> {
                navController.navigate(
                    NavRoutes.article(
                        tweetId = "",
                        handle = dest.authorHandle,
                        articleId = dest.articleId,
                        rawUrl = dest.rawUrl,
                    )
                ) {
                    launchSingleTop = true
                }
            }
            is InAppLinkDestination.Compose -> {
                navController.navigate(
                    NavRoutes.composer(
                        replyId = dest.replyToTweetId,
                        prefillText = dest.prefillText.takeIf { it.isNotBlank() },
                    )
                )
            }
            is InAppLinkDestination.WebPage -> {
                if (dest.url.isNotBlank()) {
                    val opened = InAppCustomTabsLauncher.openUrl(
                        context = appContext,
                        rawUrl = dest.url,
                        toolbarColorArgb = customTabsToolbarArgb,
                        isDarkTheme = customTabsDarkTheme,
                    )
                    if (!opened) {
                        rootScope.launch { snackbarHostState.showSnackbar(browserNoAppMsg) }
                    }
                }
            }
        }
    }

    fun openExternalLink(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return
        openInAppDestination(IlayLinkRouter.resolveUrl(trimmed))
    }

    val pendingDeepLink by pendingDeepLinkFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeepLink?.id) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        openInAppDestination(IlayLinkRouter.resolveUrl(link.url))
        onConsumeDeepLink(link.id)
    }

    fun startPostScreenshotCapture(
        tweet: Tweet,
        knownParent: Tweet?,
        action: ScreenshotCaptureAction = ScreenshotCaptureAction.OPEN_MARKUP_EDITOR,
    ) {
        if (pendingScreenshotRequest != null) return
        screenshotPrepareJob?.cancel()
        if (knownParent != null || tweet.replyToTweetId.isNullOrBlank()) {
            pendingScreenshotRequest = ScreenshotCaptureRequest(
                tweet = tweet,
                parentTweet = knownParent,
                parentMissingNotice = false,
                action = action,
            )
            return
        }
        val parentId = tweet.replyToTweetId
        screenshotPrepareJob = rootScope.launch {
            val noticeJob = launch { snackbarHostState.showSnackbar(screenshotPreparingMsg) }
            val resolvedParent = repo.resolveParentTweetForScreenshot(
                parentTweetId = parentId,
                parentHandle = tweet.replyToHandle,
            )
            noticeJob.cancel()
            pendingScreenshotRequest = ScreenshotCaptureRequest(
                tweet = tweet,
                parentTweet = resolvedParent,
                parentMissingNotice = resolvedParent == null,
                action = action,
            )
        }
    }

    fun openReply(tweet: Tweet) {
        navController.navigate(NavRoutes.composer(replyId = tweet.id, replyHandle = tweet.author.handle))
    }

    fun openQuote(tweet: Tweet) {
        quoteTargetTweet = tweet
    }

    val tweetCardHost = remember(repo, container.okHttpClient, container.linkPreviewResolver, appContext) {
        TweetCardHost(
            translateTweet = { tweet -> repo.translateTweet(tweet) },
            downloadMedia = { tweet -> TweetMediaSaver.downloadTweetMedia(appContext, container.okHttpClient, tweet) },
            resolveLinkPreview = { lp -> container.linkPreviewResolver.enrich(lp) },
            votePoll = { tweet, idx -> repo.votePoll(tweet, idx) },
            openProfile = ::openProfile,
            openHashtag = ::openHashtag,
            openUrl = ::openExternalLink,
            openArticle = ::openArticle,
            capturePostScreenshot = ::startPostScreenshotCapture,
            showMessage = { msg -> rootScope.launch { snackbarHostState.showSnackbar(msg) } },
        )
    }

    val floatingNavItems = TopDestination.entries.map { dest ->
        FloatingNavItem(
            route = dest.route,
            label = stringResource(dest.labelRes),
            selectedIcon = dest.selectedIcon,
            unselectedIcon = dest.unselectedIcon,
            testTag = "nav_${dest.name.lowercase()}",
        )
    }

    SideEffect {
        AdaptiveImageQualitySelector.currentMode = preferences.imageQualityMode
    }

    IlayForXTheme(
        themeMode = preferences.themeMode,
        amoledDark = preferences.amoledDark,
        useDynamicColor = preferences.useDynamicColor,
        accentPreset = preferences.accentPreset,
    ) {
        val resolvedToolbarArgb = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
        val resolvedIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        SideEffect {
            customTabsToolbarArgb = resolvedToolbarArgb
            customTabsDarkTheme = resolvedIsDark
        }
        CompositionLocalProvider(
            LocalTweetCardHost provides tweetCardHost,
            LocalIlayChromeState provides chromeState,
            LocalImageQualityMode provides preferences.imageQualityMode,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 600.dp

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        if (isTopLevel && useNavigationRail) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                TopDestination.entries.forEach { dest ->
                                    val selected = currentRoute == dest.route
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { navigateTop(dest.route) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = stringResource(dest.labelRes),
                                            )
                                        },
                                        label = { Text(stringResource(dest.labelRes)) },
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .nestedScroll(chromeState.nestedScrollConnection)
                        ) {
                            NavHost(
                                navController = navController,
                                startDestination = NavRoutes.HOME,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                            composable(NavRoutes.HOME) {
                                val feedState by feedViewModel.uiState.collectAsStateWithLifecycle()
                                HomeScreen(
                                    uiState = feedState,
                                    activeAccount = activeAccount,
                                    preferences = preferences,
                                    onSelectFeed = feedViewModel::selectFeed,
                                    onRefresh = feedViewModel::refresh,
                                    onLoadMore = feedViewModel::loadMore,
                                    onOpenSearch = { navController.navigate(NavRoutes.EXPLORE) },
                                    onOpenAccountSwitcher = { showAccountSwitcher = true },
                                    onOpenOwnProfile = { navController.navigate(NavRoutes.PROFILE_SELF) },
                                    onOpenSignIn = { showAuthDialog = true },
                                    onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) },
                                    onOpenThread = ::openThread,
                                    onOpenProfile = ::openProfile,
                                    onLike = feedViewModel::toggleLike,
                                    onRepost = feedViewModel::toggleRepost,
                                    onQuote = ::openQuote,
                                    onReply = ::openReply,
                                    onBookmark = feedViewModel::toggleBookmark,
                                    onVotePoll = { updatedTweet, _ -> feedViewModel.applyPollVotedTweet(updatedTweet) },
                                    onDeleteTweet = feedViewModel::deleteTweet,
                                    onOpenMedia = { list, idx -> lightboxMedia = list to idx },
                                    onNotInterested = feedViewModel::markNotInterested,
                                    onShowLessFromAuthor = feedViewModel::markShowLessFromAuthor,
                                    onRecordImpressions = feedViewModel::recordViewportImpressions,
                                    onDismissOnboardingHints = feedViewModel::dismissOnboardingHints,
                                    onDismissStatusBanner = feedViewModel::dismissStatusBanner,
                                    onApplyPendingBackgroundPosts = feedViewModel::applyPendingBackgroundPosts,
                                    scrollToTopTrigger = homeScrollToTopNonce,
                                )
                            }

                            composable(
                                route = NavRoutes.EXPLORE,
                                enterTransition = {
                                    fadeIn(tween(200)) + slideInVertically(tween(240)) { -it / 16 }
                                },
                                popExitTransition = {
                                    fadeOut(tween(180)) + slideOutVertically(tween(200)) { -it / 16 }
                                },
                            ) {
                                val exploreState by exploreViewModel.uiState.collectAsStateWithLifecycle()
                                val recents by exploreViewModel.recentSearches.collectAsStateWithLifecycle()
                                LaunchedEffect(Unit) {
                                    exploreViewModel.ensureLoaded()
                                }
                                LaunchedEffect(exploreViewModel) {
                                    exploreViewModel.events.collect { snackbarHostState.showSnackbar(it) }
                                }
                                ExploreScreen(
                                    uiState = exploreState,
                                    recentSearches = recents,
                                    preferences = preferences,
                                    onBack = { navController.popBackStack() },
                                    onQueryChange = exploreViewModel::updateQuery,
                                    onSubmitSearch = { q -> exploreViewModel.submitSearch(q) },
                                    onSelectTab = exploreViewModel::selectTab,
                                    onRefresh = exploreViewModel::refresh,
                                    onDeleteRecent = exploreViewModel::removeRecentSearch,
                                    onClearRecent = exploreViewModel::clearRecentSearches,
                                    onOpenThread = ::openThread,
                                    onOpenProfile = ::openProfile,
                                    onLike = exploreViewModel::toggleLike,
                                    onRepost = exploreViewModel::toggleRepost,
                                    onQuote = ::openQuote,
                                    onReply = ::openReply,
                                    onBookmark = exploreViewModel::toggleBookmark,
                                    onOpenMedia = { list, idx -> lightboxMedia = list to idx },
                                    onLoadMore = exploreViewModel::loadMore,
                                )
                            }

                            composable(NavRoutes.ACTIVITY) {
                                val actState by activityViewModel.uiState.collectAsStateWithLifecycle()
                                val actAccount by activityViewModel.activeAccount.collectAsStateWithLifecycle()
                                LaunchedEffect(actAccount?.userId) {
                                    activityViewModel.ensureLoaded()
                                }
                                ActivityScreen(
                                    uiState = actState,
                                    activeAccount = actAccount,
                                    onSetMentionsOnly = activityViewModel::setFilter,
                                    onRefresh = { activityViewModel.refresh(forceRefresh = true) },
                                    onSignIn = { showAuthDialog = true },
                                    onOpenProfile = ::openProfile,
                                    onOpenThread = ::openThread,
                                )
                            }

                            composable(NavRoutes.BOOKMARKS) {
                                val bookmarks by bookmarksViewModel.bookmarks.collectAsStateWithLifecycle()
                                val folders by bookmarksViewModel.folders.collectAsStateWithLifecycle()
                                val totalBookmarks by bookmarksViewModel.totalBookmarkCount.collectAsStateWithLifecycle()
                                val selectedFolder by bookmarksViewModel.selectedFolder.collectAsStateWithLifecycle()
                                val query by bookmarksViewModel.searchQuery.collectAsStateWithLifecycle()
                                val isSyncing by bookmarksViewModel.isSyncing.collectAsStateWithLifecycle()
                                val isLoadingMore by bookmarksViewModel.isLoadingMore.collectAsStateWithLifecycle()
                                val hasMoreRemote by bookmarksViewModel.hasMoreRemote.collectAsStateWithLifecycle()
                                BookmarksScreen(
                                    bookmarks = bookmarks,
                                    searchQuery = query,
                                    isSignedIn = activeAccount != null,
                                    isSyncing = isSyncing,
                                    isLoadingMore = isLoadingMore,
                                    hasMoreRemote = hasMoreRemote,
                                    preferences = preferences,
                                    onSearchQueryChange = bookmarksViewModel::updateSearch,
                                    onSyncFromX = bookmarksViewModel::syncFromX,
                                    onLoadMore = bookmarksViewModel::loadMoreBookmarks,
                                    onEnsureSync = { bookmarksViewModel.ensureInitialSync(activeAccount != null) },
                                    onOpenThread = ::openThread,
                                    onOpenProfile = ::openProfile,
                                    onLike = bookmarksViewModel::toggleLike,
                                    onRepost = bookmarksViewModel::toggleRepost,
                                    onQuote = ::openQuote,
                                    onReply = ::openReply,
                                    onRemoveBookmark = bookmarksViewModel::removeBookmark,
                                    onOpenMedia = { list, idx -> lightboxMedia = list to idx },
                                    folders = folders,
                                    totalBookmarkCount = totalBookmarks,
                                    selectedFolder = selectedFolder,
                                    onSelectFolder = bookmarksViewModel::selectFolder,
                                    onMoveToFolder = bookmarksViewModel::moveToFolder,
                                )
                            }

                            composable(
                                route = NavRoutes.PROFILE_SELF,
                                enterTransition = {
                                    fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }
                                },
                                popExitTransition = {
                                    fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 12 }
                                },
                            ) {
                                val profileVm: ProfileViewModel = viewModel(
                                    key = "self_${activeAccount?.userId ?: "guest"}",
                                    factory = ProfileViewModel.factory(repo)
                                )
                                val profileState by profileVm.uiState.collectAsStateWithLifecycle()
                                val savedBookmarks by profileVm.savedBookmarks.collectAsStateWithLifecycle()
                                val bookmarkFolders by profileVm.bookmarkFolders.collectAsStateWithLifecycle()
                                val totalBookmarksCount by profileVm.totalBookmarksCount.collectAsStateWithLifecycle()
                                val selectedBookmarkFolder by profileVm.selectedBookmarkFolder.collectAsStateWithLifecycle()
                                val bookmarkFilter by profileVm.bookmarkFilter.collectAsStateWithLifecycle()
                                val isSyncingBookmarks by profileVm.isSyncingBookmarks.collectAsStateWithLifecycle()
                                val isLoadingMoreBookmarks by profileVm.isLoadingMoreBookmarks.collectAsStateWithLifecycle()
                                val hasMoreBookmarks by profileVm.hasMoreRemoteBookmarks.collectAsStateWithLifecycle()
                                LaunchedEffect(activeAccount?.userId, activeAccount?.handle) {
                                    activeAccount?.let { profileVm.loadSelfProfile(it) }
                                }
                                LaunchedEffect(profileVm) {
                                    profileVm.events.collect { snackbarHostState.showSnackbar(it) }
                                }
                                ProfileScreen(
                                    uiState = profileState,
                                    activeAccount = activeAccount,
                                    preferences = preferences,
                                    isSelfTab = true,
                                    onBack = { navController.popBackStack() },
                                    onSelectTab = profileVm::selectTab,
                                    onRefresh = profileVm::refresh,
                                    onToggleFollow = profileVm::toggleFollow,
                                    onTogglePinBoost = profileVm::togglePinBoost,
                                    onToggleMute = profileVm::toggleMuteUser,
                                    onSetBlock = profileVm::setBlockUser,
                                    onOpenAccountSwitcher = { showAccountSwitcher = true },
                                    onOpenSignIn = { showAuthDialog = true },
                                    onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) },
                                    onOpenThread = ::openThread,
                                    onOpenProfile = ::openProfile,
                                    onLike = profileVm::toggleLike,
                                    onRepost = profileVm::toggleRepost,
                                    onQuote = ::openQuote,
                                    onReply = ::openReply,
                                    onBookmark = profileVm::toggleBookmark,
                                    onOpenMedia = { list, idx -> lightboxMedia = list to idx },
                                    onRetry = { activeAccount?.let { profileVm.loadSelfProfile(it) } },
                                    savedBookmarks = savedBookmarks,
                                    bookmarkFolders = bookmarkFolders,
                                    totalBookmarksCount = totalBookmarksCount,
                                    selectedBookmarkFolder = selectedBookmarkFolder,
                                    onSelectBookmarkFolder = profileVm::selectBookmarkFolder,
                                    onMoveBookmarkToFolder = profileVm::moveBookmarkToFolder,
                                    bookmarkFilter = bookmarkFilter,
                                    onBookmarkFilterChange = profileVm::updateBookmarkFilter,
                                    isSyncingBookmarks = isSyncingBookmarks,
                                    isLoadingMoreBookmarks = isLoadingMoreBookmarks,
                                    hasMoreBookmarks = hasMoreBookmarks,
                                    onSyncBookmarks = profileVm::syncBookmarksFromX,
                                    onLoadMoreBookmarks = profileVm::loadMoreBookmarks,
                                    onOpenSearch = profileVm::openSearch,
                                    onCloseSearch = profileVm::closeSearch,
                                    onSearchQueryChange = profileVm::updateSearchQuery,
                                    onSelectSearchTab = profileVm::selectSearchTab,
                                    onSubmitSearch = profileVm::submitProfileSearch,
                                    onLoadMoreSearch = profileVm::loadMoreProfileSearch,
                                )
                            }

                            composable(
                                route = NavRoutes.USER_PROFILE,
                                arguments = listOf(navArgument("handle") { type = NavType.StringType })
                            ) { entry ->
                                val handle = entry.arguments?.getString("handle").orEmpty()
                                val profileVm: ProfileViewModel = viewModel(
                                    key = "user_$handle",
                                    factory = ProfileViewModel.factory(repo)
                                )
                                val profileState by profileVm.uiState.collectAsStateWithLifecycle()
                                val savedBookmarks by profileVm.savedBookmarks.collectAsStateWithLifecycle()
                                val bookmarkFolders by profileVm.bookmarkFolders.collectAsStateWithLifecycle()
                                val totalBookmarksCount by profileVm.totalBookmarksCount.collectAsStateWithLifecycle()
                                val selectedBookmarkFolder by profileVm.selectedBookmarkFolder.collectAsStateWithLifecycle()
                                val bookmarkFilter by profileVm.bookmarkFilter.collectAsStateWithLifecycle()
                                val isSyncingBookmarks by profileVm.isSyncingBookmarks.collectAsStateWithLifecycle()
                                val isLoadingMoreBookmarks by profileVm.isLoadingMoreBookmarks.collectAsStateWithLifecycle()
                                val hasMoreBookmarks by profileVm.hasMoreRemoteBookmarks.collectAsStateWithLifecycle()
                                LaunchedEffect(handle, activeAccount?.handle) {
                                    if (handle.isNotBlank()) profileVm.loadProfile(handle, activeAccount?.handle)
                                }
                                LaunchedEffect(profileVm) {
                                    profileVm.events.collect { snackbarHostState.showSnackbar(it) }
                                }
                                ProfileScreen(
                                    uiState = profileState,
                                    activeAccount = activeAccount,
                                    preferences = preferences,
                                    isSelfTab = false,
                                    onBack = { navController.popBackStack() },
                                    onSelectTab = profileVm::selectTab,
                                    onRefresh = profileVm::refresh,
                                    onToggleFollow = profileVm::toggleFollow,
                                    onTogglePinBoost = profileVm::togglePinBoost,
                                    onToggleMute = profileVm::toggleMuteUser,
                                    onSetBlock = profileVm::setBlockUser,
                                    onOpenAccountSwitcher = { showAccountSwitcher = true },
                                    onOpenSignIn = { showAuthDialog = true },
                                    onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) },
                                    onOpenThread = ::openThread,
                                    onOpenProfile = ::openProfile,
                                    onLike = profileVm::toggleLike,
                                    onRepost = profileVm::toggleRepost,
                                    onQuote = ::openQuote,
                                    onReply = ::openReply,
                                    onBookmark = profileVm::toggleBookmark,
                                    onOpenMedia = { list, idx -> lightboxMedia = list to idx },
                                    onRetry = { if (handle.isNotBlank()) profileVm.loadProfile(handle, activeAccount?.handle) },
                                    savedBookmarks = savedBookmarks,
                                    bookmarkFolders = bookmarkFolders,
                                    totalBookmarksCount = totalBookmarksCount,
                                    selectedBookmarkFolder = selectedBookmarkFolder,
                                    onSelectBookmarkFolder = profileVm::selectBookmarkFolder,
                                    onMoveBookmarkToFolder = profileVm::moveBookmarkToFolder,
                                    bookmarkFilter = bookmarkFilter,
                                    onBookmarkFilterChange = profileVm::updateBookmarkFilter,
                                    isSyncingBookmarks = isSyncingBookmarks,
                                    isLoadingMoreBookmarks = isLoadingMoreBookmarks,
                                    hasMoreBookmarks = hasMoreBookmarks,
                                    onSyncBookmarks = profileVm::syncBookmarksFromX,
                                    onLoadMoreBookmarks = profileVm::loadMoreBookmarks,
                                    onOpenSearch = profileVm::openSearch,
                                    onCloseSearch = profileVm::closeSearch,
                                    onSearchQueryChange = profileVm::updateSearchQuery,
                                    onSelectSearchTab = profileVm::selectSearchTab,
                                    onSubmitSearch = profileVm::submitProfileSearch,
                                    onLoadMoreSearch = profileVm::loadMoreProfileSearch,
                                )
                            }

                            composable(
                                route = NavRoutes.THREAD,
                                arguments = listOf(
                                    navArgument("tweetId") { type = NavType.StringType },
                                    navArgument("handle") { type = NavType.StringType },
                                ),
                                enterTransition = {
                                    fadeIn(tween(220)) +
                                        scaleIn(initialScale = 0.96f, animationSpec = tween(260)) +
                                        slideInVertically(tween(260)) { it / 16 }
                                },
                                exitTransition = {
                                    fadeOut(tween(180))
                                },
                                popEnterTransition = {
                                    fadeIn(tween(200))
                                },
                                popExitTransition = {
                                    fadeOut(tween(180)) +
                                        scaleOut(targetScale = 0.96f, animationSpec = tween(220)) +
                                        slideOutVertically(tween(220)) { it / 16 }
                                },
                            ) { entry ->
                                val tweetId = entry.arguments?.getString("tweetId").orEmpty()
                                val handle = entry.arguments?.getString("handle").orEmpty()
                                val threadVm: ThreadViewModel = viewModel(
                                    key = "thread_$tweetId",
                                    factory = ThreadViewModel.factory(repo)
                                )
                                val threadState by threadVm.uiState.collectAsStateWithLifecycle()
                                LaunchedEffect(tweetId) {
                                    if (tweetId.isNotBlank()) threadVm.load(tweetId, handle)
                                }
                                LaunchedEffect(threadVm) {
                                    threadVm.events.collect { snackbarHostState.showSnackbar(it) }
                                }
                                ThreadDetailScreen(
                                    uiState = threadState,
                                    preferences = preferences,
                                    onBack = { navController.popBackStack() },
                                    onSelectTab = threadVm::selectTab,
                                    onLoadMoreQuotes = threadVm::loadMoreQuotes,
                                    onOpenThread = ::openThread,
                                    onOpenProfile = ::openProfile,
                                    onLike = threadVm::toggleLike,
                                    onRepost = threadVm::toggleRepost,
                                    onQuote = ::openQuote,
                                    onReplyFull = ::openReply,
                                    onBookmark = threadVm::toggleBookmark,
                                    onOpenMedia = { list, idx -> lightboxMedia = list to idx },
                                )
                            }

                            composable(
                                route = NavRoutes.ARTICLE,
                                arguments = listOf(
                                    navArgument("tweetId") { type = NavType.StringType },
                                    navArgument("handle") { type = NavType.StringType },
                                    navArgument("articleId") { type = NavType.StringType; nullable = true; defaultValue = "" },
                                    navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = "" },
                                ),
                                enterTransition = {
                                    fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 14 }
                                },
                                popExitTransition = {
                                    fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 14 }
                                },
                            ) { entry ->
                                val rawTweetId = entry.arguments?.getString("tweetId").orEmpty()
                                val tweetId = if (rawTweetId == "article") "" else rawTweetId
                                val handle = entry.arguments?.getString("handle").orEmpty().let { if (it == "i") "" else it }
                                val articleId = entry.arguments?.getString("articleId").orEmpty()
                                val rawUrl = entry.arguments?.getString("url").orEmpty()

                                val initialPair = remember(tweetId, articleId) {
                                    repo.peekSeededArticle(tweetId)
                                        ?: repo.peekSeededArticle(articleId)
                                }
                                var readerTweet by remember(tweetId, articleId) {
                                    mutableStateOf(
                                        initialPair?.first ?: Tweet(
                                            id = tweetId,
                                            author = XUser(
                                                id = handle.ifBlank { "x" },
                                                handle = handle,
                                                displayName = handle.ifBlank { "X Article" },
                                            ),
                                            text = "",
                                            createdAtEpochMs = System.currentTimeMillis(),
                                        )
                                    )
                                }
                                var readerArticle by remember(tweetId, articleId) {
                                    mutableStateOf(
                                        initialPair?.second ?: XArticle(
                                            id = articleId.ifBlank { tweetId },
                                            title = "Loading Article…",
                                            articleUrl = rawUrl,
                                        )
                                    )
                                }
                                var isHydrating by remember(tweetId, articleId) {
                                    mutableStateOf(readerArticle.bodyBlocks.size < 2)
                                }

                                LaunchedEffect(tweetId, handle, articleId, rawUrl) {
                                    isHydrating = true
                                    repo.loadFullArticle(
                                        tweetId = tweetId,
                                        authorHandle = handle,
                                        articleId = articleId,
                                        rawUrl = rawUrl,
                                        fallbackTweet = initialPair?.first,
                                    ).onSuccess { (hydratedTweet, hydratedArticle) ->
                                        readerTweet = hydratedTweet
                                        readerArticle = hydratedArticle
                                    }
                                    isHydrating = false
                                }

                                ArticleReaderScreen(
                                    tweet = readerTweet,
                                    article = readerArticle,
                                    isHydrating = isHydrating,
                                    onBack = { navController.popBackStack() },
                                    onOpenProfile = ::openProfile,
                                    onOpenThread = ::openThread,
                                    onLike = { target ->
                                        rootScope.launch {
                                            repo.toggleLike(target)
                                                .onSuccess { readerTweet = it }
                                                .onFailure { err -> snackbarHostState.showSnackbar(err.message ?: "Sign in to like") }
                                        }
                                    },
                                    onRepost = { target ->
                                        rootScope.launch {
                                            repo.toggleRepost(target)
                                                .onSuccess { readerTweet = it }
                                                .onFailure { err -> snackbarHostState.showSnackbar(err.message ?: "Sign in to repost") }
                                        }
                                    },
                                    onBookmark = { target ->
                                        rootScope.launch {
                                            repo.toggleBookmark(target).onSuccess { updated ->
                                                readerTweet = updated
                                                snackbarHostState.showSnackbar(
                                                    if (updated.viewerState.isBookmarked) "Bookmarked" else "Bookmark removed"
                                                )
                                            }
                                        }
                                    },
                                    onOpenMedia = { list, idx -> lightboxMedia = list to idx },
                                    onOpenUrl = ::openExternalLink,
                                )
                            }

                            composable(
                                route = NavRoutes.COMPOSER,
                                arguments = listOf(
                                    navArgument("replyId") { type = NavType.StringType; nullable = true; defaultValue = null },
                                    navArgument("replyHandle") { type = NavType.StringType; nullable = true; defaultValue = null },
                                    navArgument("quoteId") { type = NavType.StringType; nullable = true; defaultValue = null },
                                    navArgument("quoteAuthor") { type = NavType.StringType; nullable = true; defaultValue = null },
                                    navArgument("quoteSnippet") { type = NavType.StringType; nullable = true; defaultValue = null },
                                    navArgument("prefillText") { type = NavType.StringType; nullable = true; defaultValue = null },
                                )
                            ) { entry ->
                                val composerVm: ComposerViewModel = viewModel(factory = ComposerViewModel.factory(repo))
                                val compState by composerVm.uiState.collectAsStateWithLifecycle()
                                val drafts by composerVm.drafts.collectAsStateWithLifecycle()
                                LaunchedEffect(entry.id) {
                                    composerVm.configure(
                                        replyToId = entry.arguments?.getString("replyId"),
                                        replyToHandle = entry.arguments?.getString("replyHandle"),
                                        quoteId = entry.arguments?.getString("quoteId"),
                                        quoteAuthor = entry.arguments?.getString("quoteAuthor"),
                                        quoteSnippet = entry.arguments?.getString("quoteSnippet"),
                                        prefillText = entry.arguments?.getString("prefillText"),
                                    )
                                }
                                ComposerScreen(
                                    uiState = compState,
                                    activeAccount = activeAccount,
                                    drafts = drafts,
                                    onTextChange = composerVm::updateText,
                                    onAddMedia = composerVm::addMediaUris,
                                    onRemoveMedia = composerVm::removeMediaUri,
                                    onSaveDraft = {
                                        composerVm.saveDraft {
                                            navController.popBackStack()
                                        }
                                    },
                                    onLoadDraft = composerVm::loadDraft,
                                    onDeleteDraft = composerVm::deleteDraft,
                                    onPublish = {
                                        composerVm.publish { created ->
                                            created?.let { feedViewModel.prependCreatedTweet(it) }
                                            navController.popBackStack()
                                        }
                                    },
                                    onClose = { navController.popBackStack() },
                                )
                            }

                            composable(NavRoutes.SETTINGS) {
                                val muted by settingsViewModel.mutedRules.collectAsStateWithLifecycle()
                                val userModerations by settingsViewModel.userModerations.collectAsStateWithLifecycle()
                                val affinities by settingsViewModel.affinities.collectAsStateWithLifecycle()
                                val diagnostics by settingsViewModel.endpointDiagnostics.collectAsStateWithLifecycle()
                                SettingsScreen(
                                    preferences = preferences,
                                    activeAccount = activeAccount,
                                    accountCount = allAccounts.size,
                                    mutedRules = muted,
                                    userModerations = userModerations,
                                    affinities = affinities,
                                    endpointDiagnostics = diagnostics,
                                    onBack = { navController.popBackStack() },
                                    onOpenCategory = { category ->
                                        navController.navigate(NavRoutes.settingsCategory(category)) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onOpenAccountSwitcher = { showAccountSwitcher = true },
                                )
                            }

                            composable(
                                route = NavRoutes.SETTINGS_CATEGORY,
                                arguments = listOf(
                                    navArgument("category") { type = NavType.StringType },
                                ),
                                enterTransition = {
                                    fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 8 }
                                },
                                popExitTransition = {
                                    fadeOut(tween(150)) + slideOutHorizontally(tween(190)) { it / 8 }
                                },
                            ) { entry ->
                                val category = SettingsCategory.fromRouteArg(entry.arguments?.getString("category"))
                                val muted by settingsViewModel.mutedRules.collectAsStateWithLifecycle()
                                val userModerations by settingsViewModel.userModerations.collectAsStateWithLifecycle()
                                val affinities by settingsViewModel.affinities.collectAsStateWithLifecycle()
                                val diagnostics by settingsViewModel.endpointDiagnostics.collectAsStateWithLifecycle()
                                val refreshing by settingsViewModel.isRefreshingRegistry.collectAsStateWithLifecycle()
                                SettingsCategoryScreen(
                                    category = category,
                                    preferences = preferences,
                                    activeAccount = activeAccount,
                                    accountCount = allAccounts.size,
                                    mutedRules = muted,
                                    userModerations = userModerations,
                                    affinities = affinities,
                                    endpointDiagnostics = diagnostics,
                                    isRefreshingRegistry = refreshing,
                                    onBack = { navController.popBackStack() },
                                    onSetThemeMode = settingsViewModel::setThemeMode,
                                    onSetAmoledDark = settingsViewModel::setAmoledDark,
                                    onSetDynamicColor = settingsViewModel::setUseDynamicColor,
                                    onSetAccentPreset = settingsViewModel::setAccentPreset,
                                    onSetFeedDensity = settingsViewModel::setFeedDensity,
                                    onSetImageQualityMode = settingsViewModel::setImageQualityMode,
                                    onSetSwipeRight = settingsViewModel::setSwipeRight,
                                    onSetSwipeLeft = settingsViewModel::setSwipeLeft,
                                    onSetRecencyWeight = settingsViewModel::setRecencyWeight,
                                    onSetAffinityWeight = settingsViewModel::setAffinityWeight,
                                    onSetBlurSensitive = settingsViewModel::setBlurSensitiveMedia,
                                    onSetExcludeRiskyAndNsfw = settingsViewModel::setExcludeRiskyAndNsfw,
                                    onSetShowRankBadges = settingsViewModel::setShowLocalRankBadges,
                                    onAddMutedRule = settingsViewModel::addMutedRule,
                                    onRemoveMutedRule = settingsViewModel::removeMutedRule,
                                    onUnmuteUser = settingsViewModel::unmuteUser,
                                    onUnblockUser = settingsViewModel::unblockUser,
                                    onResetForYou = settingsViewModel::resetLocalForYou,
                                    onSaveProxyUrl = settingsViewModel::setCustomProxyBaseUrl,
                                    onSavePublicHandles = settingsViewModel::setPublicHandlesCsv,
                                    onRefreshRegistry = settingsViewModel::refreshEndpointRegistry,
                                    onOpenAccountSwitcher = { showAccountSwitcher = true },
                                    onOpenInterestInspector = { navController.navigate(NavRoutes.INTEREST_INSPECTOR) },
                                )
                            }

                            composable(NavRoutes.INTEREST_INSPECTOR) {
                                val inspectorVm: InterestInspectorViewModel = viewModel(
                                    factory = InterestInspectorViewModel.factory(repo)
                                )
                                val inspectorPrefs by inspectorVm.preferences.collectAsStateWithLifecycle()
                                val topics by inspectorVm.topics.collectAsStateWithLifecycle()
                                val creators by inspectorVm.creators.collectAsStateWithLifecycle()
                                val seenCount by inspectorVm.seenCount.collectAsStateWithLifecycle()
                                val dismissedCount by inspectorVm.dismissedCount.collectAsStateWithLifecycle()
                                val riskySuppressedCount by inspectorVm.riskySuppressedCount.collectAsStateWithLifecycle()
                                LaunchedEffect(inspectorVm) {
                                    inspectorVm.events.collect { msg -> snackbarHostState.showSnackbar(msg) }
                                }
                                InterestInspectorScreen(
                                    preferences = inspectorPrefs,
                                    topics = topics,
                                    creators = creators,
                                    seenCount = seenCount,
                                    dismissedCount = dismissedCount,
                                    riskySuppressedCount = riskySuppressedCount,
                                    onBack = { navController.popBackStack() },
                                    onAddTopic = inspectorVm::addTopic,
                                    onToggleBoostTopic = inspectorVm::toggleBoostTopic,
                                    onToggleDemoteTopic = inspectorVm::toggleDemoteTopic,
                                    onRemoveTopic = inspectorVm::removeTopic,
                                    onTogglePinCreator = inspectorVm::togglePinCreator,
                                    onToggleShowLessCreator = inspectorVm::toggleShowLessCreator,
                                    onResetCreator = inspectorVm::resetCreator,
                                    onSetDemoteSeenPosts = inspectorVm::setDemoteSeenPosts,
                                    onSetBlendMultiSource = inspectorVm::setBlendMultiSourceForYou,
                                    onSetExcludeRiskyAndNsfw = inspectorVm::setExcludeRiskyAndNsfw,
                                    onClearSeenHistory = inspectorVm::clearSeenHistory,
                                    onResetAllSignals = inspectorVm::resetAllSafeSignals,
                                )
                            }
                            }

                            // Overlaid Floating Compose FAB — glides down smoothly as floating bottom bar collapses
                            if (currentRoute == NavRoutes.HOME || currentRoute == NavRoutes.PROFILE_SELF) {
                                val fabBottomPadding by animateDpAsState(
                                    targetValue = if (isTopLevel && !useNavigationRail) {
                                        lerp(90.dp, 20.dp, chromeState.collapseProgress)
                                    } else {
                                        20.dp
                                    },
                                    animationSpec = IlayMotion.spatialDefault(),
                                    label = "fabBottomPad",
                                )
                                FloatingActionButton(
                                    onClick = { navController.navigate(NavRoutes.composer()) },
                                    shape = RoundedCornerShape(20.dp),
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .navigationBarsPadding()
                                        .padding(end = 18.dp, bottom = fabBottomPadding)
                                        .testTag("compose_fab"),
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.cd_compose_post),
                                    )
                                }
                            }

                            // Snackbar above floating bottom nav bar
                            SnackbarHost(
                                hostState = snackbarHostState,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = if (isTopLevel && !useNavigationRail) 84.dp else 16.dp),
                            )

                            // Ilay signature floating pill bottom bar (collapses on scroll down)
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isTopLevel && !useNavigationRail,
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(140)),
                                modifier = Modifier.align(Alignment.BottomCenter),
                            ) {
                                IlayFloatingBottomBar(
                                    items = floatingNavItems,
                                    currentRoute = currentRoute,
                                    collapseProgress = chromeState.collapseProgress,
                                    onSelect = { route -> navigateTop(route) },
                                )
                            }
                        }
                    }
                }

                if (showAccountSwitcher) {
                    AccountSwitcherSheet(
                        accounts = allAccounts,
                        onSwitchAccount = feedViewModel::switchAccount,
                        onRemoveAccount = feedViewModel::removeAccount,
                        onAddAccountClick = { showAuthDialog = true },
                        onDismiss = { showAccountSwitcher = false },
                    )
                }

                quoteTargetTweet?.let { targetTweet ->
                    val quotePublishedText = stringResource(R.string.quote_post_published)
                    val quoteDraftSavedText = stringResource(R.string.quote_post_draft_saved)
                    QuotePostDialog(
                        parentTweet = targetTweet,
                        activeAccount = activeAccount,
                        blurSensitiveMedia = preferences.blurSensitiveMedia,
                        onDismiss = { quoteTargetTweet = null },
                        onRequestSignIn = { showAuthDialog = true },
                        onPublish = { comment, mediaUris ->
                            val quoteUrl = buildQuoteTweetUrl(targetTweet)
                            repo.publishTweet(
                                text = comment,
                                quoteTweetUrl = quoteUrl,
                                quotedTweetFallback = QuotedTweet.fromTweet(targetTweet),
                                mediaUris = mediaUris,
                            ).onSuccess { created ->
                                created?.let { feedViewModel.prependCreatedTweet(it) }
                                quoteTargetTweet = null
                                rootScope.launch { snackbarHostState.showSnackbar(quotePublishedText) }
                            }
                        },
                        onSaveDraft = { comment, mediaUris ->
                            repo.saveDraft(
                                DraftTweetEntity(
                                    text = comment,
                                    quoteTweetId = targetTweet.id,
                                    quoteTweetUrl = buildQuoteTweetUrl(targetTweet),
                                    mediaUrisCsv = mediaUris.joinToString(",") { it.toString() },
                                )
                            )
                            quoteTargetTweet = null
                            rootScope.launch { snackbarHostState.showSnackbar(quoteDraftSavedText) }
                        },
                    )
                }

                if (showAuthDialog) {
                    XAuthDialog(
                        onCookiesCaptured = { auth, ct0, twidUserId ->
                            feedViewModel.signInWithCookies(auth, ct0, twidUserId) {
                                showAuthDialog = false
                                activityViewModel.refresh()
                                exploreViewModel.loadTrends(forceRefresh = true)
                            }
                        },
                        onDismiss = { showAuthDialog = false },
                    )
                }

                lightboxMedia?.let { (mediaList, initialIdx) ->
                    MediaLightboxDialog(
                        mediaList = mediaList,
                        initialIndex = initialIdx,
                        onDismiss = { lightboxMedia = null },
                        onSaveMedia = { item ->
                            TweetMediaSaver.downloadSingleMediaAttachment(appContext, container.okHttpClient, item)
                        },
                        onShowMessage = { msg ->
                            rootScope.launch { snackbarHostState.showSnackbar(msg) }
                        },
                    )
                }

                TweetScreenshotCaptureHost(
                    request = pendingScreenshotRequest,
                    onFinished = { statusMessage ->
                        pendingScreenshotRequest = null
                        rootScope.launch {
                            snackbarHostState.showSnackbar(statusMessage)
                        }
                    },
                )
            }
        }
    }
}
