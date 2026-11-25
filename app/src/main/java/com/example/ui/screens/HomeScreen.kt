package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.R
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.remote.IlayImageCache
import com.example.data.remote.XApiException
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.domain.model.FeedType
import com.example.domain.model.MediaAttachment
import com.example.domain.model.Tweet
import com.example.domain.ranking.TweetImpressionEvent
import com.example.ui.components.IlayCollapsingTopBar
import com.example.ui.components.IlayExpressiveTabPill
import com.example.ui.components.IlayLoadingIndicator
import com.example.ui.components.IlayPullToRefreshIndicator
import com.example.ui.components.LocalIlayChromeState
import com.example.ui.components.ilayVerticalScrollIndicator
import com.example.ui.components.LowPolyEmptyStateArt
import com.example.ui.components.LowPolyScene
import com.example.ui.components.SwipeableTweetCard
import com.example.ui.components.UserAvatar
import com.example.ui.viewmodel.FeedUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    uiState: FeedUiState,
    activeAccount: AccountEntity?,
    preferences: AppPreferences,
    onSelectFeed: (FeedType) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    onOpenOwnProfile: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onVotePoll: (Tweet, Int) -> Unit,
    onDeleteTweet: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
    onNotInterested: (Tweet) -> Unit = {},
    onShowLessFromAuthor: (Tweet) -> Unit = {},
    onRecordImpressions: (List<TweetImpressionEvent>) -> Unit = {},
    onDismissOnboardingHints: () -> Unit = {},
    onDismissStatusBanner: () -> Unit = {},
    onApplyPendingBackgroundPosts: () -> Unit = {},
    scrollToTopTrigger: Int = 0,
) {
    val chromeState = LocalIlayChromeState.current
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var lastHandledScrollTrigger by rememberSaveable { mutableIntStateOf(scrollToTopTrigger) }

    val latestTweets by rememberUpdatedState(uiState.tweets)
    val latestRecordImpressions by rememberUpdatedState(onRecordImpressions)

    // Batched viewport impression, dwell-time & quick-skip tracker (zero per-frame SQLite writes)
    val visibleSinceById = remember { LinkedHashMap<String, Long>() }
    val pendingImpressions = remember { LinkedHashMap<String, TweetImpressionEvent>() }

    fun harvestAndFlushImpressions(includeStillVisible: Boolean) {
        val now = System.currentTimeMillis()
        val tweetsById = latestTweets.associateBy { it.id }
        if (includeStillVisible && visibleSinceById.isNotEmpty()) {
            val iter = visibleSinceById.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val dwell = now - entry.value
                val tweet = tweetsById[entry.key]
                if (tweet != null && dwell >= 900L) {
                    val prev = pendingImpressions[entry.key]
                    pendingImpressions[entry.key] = TweetImpressionEvent(
                        tweet = tweet,
                        dwellMs = (prev?.dwellMs ?: 0L) + dwell,
                        wasInteracted = prev?.wasInteracted ?: false,
                        wasSkipped = false,
                    )
                    entry.setValue(now)
                }
            }
        }
        if (pendingImpressions.isNotEmpty()) {
            val batch = pendingImpressions.values.toList()
            pendingImpressions.clear()
            latestRecordImpressions(batch)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val k = info.key as? String ?: return@mapNotNull null
                if (k == "guest_banner" || k == "first_install_hint" || k == "load_more") null else k
            }.toSet()
        }.distinctUntilChanged().collect { nowVisible ->
            val now = System.currentTimeMillis()
            val tweetsById = latestTweets.associateBy { it.id }

            // Record items that scrolled out of the viewport
            val exited = visibleSinceById.keys.filter { it !in nowVisible }
            for (id in exited) {
                val enteredAt = visibleSinceById.remove(id) ?: continue
                val dwellMs = now - enteredAt
                val tweet = tweetsById[id] ?: continue
                if (dwellMs < 300L) continue
                val isSkip = dwellMs in 300L..1200L
                val prev = pendingImpressions[id]
                pendingImpressions[id] = TweetImpressionEvent(
                    tweet = tweet,
                    dwellMs = (prev?.dwellMs ?: 0L) + dwellMs,
                    wasInteracted = prev?.wasInteracted ?: false,
                    wasSkipped = if (dwellMs >= 3000L) false else ((prev?.wasSkipped ?: false) || isSkip),
                )
            }

            // Mark newly visible tweets
            for (id in nowVisible) {
                if (id !in visibleSinceById && id in tweetsById) {
                    visibleSinceById[id] = now
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_500L)
            harvestAndFlushImpressions(includeStillVisible = true)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                harvestAndFlushImpressions(includeStillVisible = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            harvestAndFlushImpressions(includeStillVisible = true)
        }
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > lastHandledScrollTrigger) {
            lastHandledScrollTrigger = scrollToTopTrigger
            if (uiState.tweets.isNotEmpty()) {
                if (listState.firstVisibleItemIndex > 12) {
                    listState.scrollToItem(10)
                }
                listState.animateScrollToItem(0)
            }
        }
    }

    // Proactively warm Coil's disk & memory caches ahead of the visible timeline window
    LaunchedEffect(uiState.tweets, listState) {
        if (uiState.tweets.isEmpty()) return@LaunchedEffect
        IlayImageCache.prefetchTimelineMedia(context, uiState.tweets, startIndex = 0, windowSize = 10)
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { visibleIdx ->
                IlayImageCache.prefetchTimelineMedia(
                    context = context,
                    tweets = uiState.tweets,
                    startIndex = (visibleIdx - 1).coerceAtLeast(0),
                    windowSize = 10,
                )
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        IlayCollapsingTopBar(
            title = stringResource(R.string.app_name),
            subtitle = activeAccount?.let { "@${it.handle}" },
            collapseProgress = chromeState.collapseProgress,
            navigationIcon = {
                val openOwnProfileLabel = stringResource(R.string.action_open_own_profile)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = onOpenAccountSwitcher,
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenOwnProfile()
                            },
                            onLongClickLabel = openOwnProfileLabel,
                        )
                        .testTag("account_switcher_avatar")
                ) {
                    UserAvatar(
                        url = activeAccount?.avatarUrl.orEmpty(),
                        displayName = activeAccount?.displayName ?: "I",
                        size = 34.dp,
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier.testTag("home_search_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.cd_open_search),
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("home_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                    )
                }
            },
            bottomExpandedContent = {
                // MD3 Expressive shape-morphing pill tabs (For You | Following)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FeedType.entries.forEach { feed ->
                        val selected = uiState.selectedFeed == feed
                        IlayExpressiveTabPill(
                            label = feed.label,
                            selected = selected,
                            onClick = { onSelectFeed(feed) },
                            leadingIcon = if (feed == FeedType.FOR_YOU) {
                                {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            } else null,
                            testTag = "feed_tab_${feed.name.lowercase()}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
        )

        // Subtle non-intrusive bar during silent startup/tab background sync
        if (uiState.isRefreshing && !uiState.isUserRefreshing) {
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("timeline_background_sync_bar"),
            )
        }

        // M3E rate-limit countdown or API error notice while keeping cached personal posts intact
        val showStatusBanner = uiState.tweets.isNotEmpty() &&
            (uiState.statusBannerMessage != null || uiState.rateLimitResetAtEpochMs != null)
        AnimatedVisibility(
            visible = showStatusBanner,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            FeedStatusBanner(
                message = uiState.statusBannerMessage.orEmpty(),
                rateLimitResetAtEpochMs = uiState.rateLimitResetAtEpochMs,
                isAuthError = uiState.isAuthErrorBanner,
                onRetry = onRefresh,
                onSignIn = onOpenSignIn,
                onDismiss = onDismissStatusBanner,
            )
        }

        // Main Feed Content with Material 3 Pull-to-Refresh gesture
        PullToRefreshBox(
            isRefreshing = uiState.isUserRefreshing,
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRefresh()
            },
            state = pullToRefreshState,
            indicator = {
                IlayPullToRefreshIndicator(
                    state = pullToRefreshState,
                    isRefreshing = uiState.isUserRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("timeline_pull_to_refresh"),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    uiState.isLoading && uiState.tweets.isEmpty() -> {
                        FeedSkeletonList()
                    }
                    uiState.tweets.isEmpty() -> {
                        FeedEmptyState(
                            errorMessage = uiState.errorMessage,
                            isSignedIn = activeAccount != null,
                            onRetry = onRefresh,
                            onSignIn = onOpenSignIn,
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 640.dp)
                                .ilayVerticalScrollIndicator(state = listState),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 112.dp),
                        ) {
                            // Public preview banner when not signed in
                            if (activeAccount == null) {
                                item(key = "guest_banner") {
                                    GuestPreviewBanner(onSignIn = onOpenSignIn)
                                }
                            }

                            // Brief first-install onboarding hint (auto-hides after 24h or when dismissed)
                            if (preferences.showOnboardingHints) {
                                item(key = "first_install_hint") {
                                    FirstInstallHintPill(
                                        onDismiss = onDismissOnboardingHints,
                                    )
                                }
                            }

                            items(
                                items = uiState.tweets,
                                key = { it.id }
                            ) { tweet ->
                                val canDelete = activeAccount != null &&
                                    activeAccount.handle.equals(tweet.author.handle, ignoreCase = true)
                                SwipeableTweetCard(
                                    tweet = tweet,
                                    density = preferences.feedDensity,
                                    swipeRightAction = preferences.swipeRightAction,
                                    swipeLeftAction = preferences.swipeLeftAction,
                                    showRankBadge = preferences.showLocalRankBadges && uiState.selectedFeed == FeedType.FOR_YOU,
                                    blurSensitiveMedia = preferences.blurSensitiveMedia,
                                    onOpenThread = onOpenThread,
                                    onOpenProfile = onOpenProfile,
                                    onLike = onLike,
                                    onRepost = onRepost,
                                    onQuote = onQuote,
                                    onReply = onReply,
                                    onBookmark = onBookmark,
                                    onOpenMedia = onOpenMedia,
                                    modifier = Modifier.animateItem(),
                                    onVotePoll = onVotePoll,
                                    onDelete = if (canDelete) onDeleteTweet else null,
                                    onNotInterested = onNotInterested,
                                    onShowLessFromAuthor = if (!canDelete) onShowLessFromAuthor else null,
                                )
                            }

                            if (uiState.nextCursor != null) {
                                item(key = "load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (uiState.isLoadingMore) {
                                            IlayLoadingIndicator(size = 32.dp)
                                        } else {
                                            FilledTonalButton(
                                                onClick = onLoadMore,
                                                shape = CircleShape,
                                                modifier = Modifier.testTag("load_more_button")
                                            ) {
                                                Text("Load older posts")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Floating Material 3 pill when StartupPrefetchWorker delivers newer posts in the background
                androidx.compose.animation.AnimatedVisibility(
                    visible = uiState.pendingNewPostsCount > 0 && !uiState.isUserRefreshing,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp),
                ) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onApplyPendingBackgroundPosts()
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                        shadowElevation = 4.dp,
                        modifier = Modifier.testTag("new_posts_available_pill"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (uiState.pendingNewPostsCount == 1) {
                                    "1 new post"
                                } else {
                                    "${uiState.pendingNewPostsCount} new posts"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestPreviewBanner(onSignIn: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.guest_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.guest_banner_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onSignIn,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("banner_sign_in_button")
            ) {
                Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Sign In", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun FeedStatusBanner(
    message: String,
    rateLimitResetAtEpochMs: Long?,
    isAuthError: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    var nowMs by remember(rateLimitResetAtEpochMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rateLimitResetAtEpochMs) {
        val resetAt = rateLimitResetAtEpochMs ?: return@LaunchedEffect
        while (nowMs < resetAt) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val isRateLimited = rateLimitResetAtEpochMs != null
    val countdownActive = rateLimitResetAtEpochMs != null && nowMs < rateLimitResetAtEpochMs
    val displayTitle = when {
        countdownActive -> XApiException.formatRateLimitMessage(rateLimitResetAtEpochMs, nowMs)
        isRateLimited -> stringResource(R.string.home_rate_limit_ready)
        else -> message.ifBlank { stringResource(R.string.home_cached_feed_caption) }
    }

    val containerColor = if (isAuthError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isAuthError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val borderColor = if (isAuthError) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("home_status_banner"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isRateLimited) Icons.Outlined.HourglassTop else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = stringResource(R.string.home_cached_feed_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
            if (isAuthError) {
                TextButton(
                    onClick = onSignIn,
                    modifier = Modifier.testTag("home_status_banner_action"),
                ) {
                    Text(
                        text = stringResource(R.string.quote_post_sign_in_cta),
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else if (!countdownActive) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("home_status_banner_action"),
                ) {
                    Text(
                        text = stringResource(R.string.action_retry),
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("home_status_banner_dismiss"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_banner),
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FirstInstallHintPill(onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("first_install_hint_pill"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "For You ranks locally · Swipe cards firmly to like or bookmark",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("dismiss_onboarding_hint_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss tip",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FeedSkeletonList() {
    val pulseTransition = rememberInfiniteTransition(label = "skeletonPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp)
            .padding(12.dp)
            .alpha(pulseAlpha),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                            Box(
                                modifier = Modifier
                                    .width(85.dp)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedEmptyState(
    errorMessage: String?,
    isSignedIn: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LowPolyEmptyStateArt(
            scene = LowPolyScene.TIMELINE,
            contentDescriptionText = "Ilay for X low-poly geometric timeline artwork",
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (errorMessage != null) "Timeline Unavailable" else "Your Feed Awaits",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = errorMessage
                ?: "Pull down to refresh or sign in to connect directly to X's GraphQL timeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = onRetry,
                shape = CircleShape,
                modifier = Modifier.testTag("empty_retry_button")
            ) {
                Text("Refresh")
            }
            if (!isSignedIn) {
                Button(
                    onClick = onSignIn,
                    shape = CircleShape,
                    modifier = Modifier.testTag("empty_signin_button")
                ) {
                    Text("Sign in to X")
                }
            }
        }
    }
}
