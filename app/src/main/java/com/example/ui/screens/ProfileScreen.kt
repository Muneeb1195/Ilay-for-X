package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.local.AccountEntity
import com.example.data.local.AppPreferences
import com.example.data.local.BookmarkEntity
import com.example.data.remote.AdaptiveImageQualitySelector
import com.example.data.remote.IlayImageCache
import com.example.data.remote.ImageUsage
import com.example.data.remote.LocalImageQualityMode
import com.example.data.remote.NetworkTier
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.ProfileTab
import com.example.domain.model.SearchTab
import com.example.domain.model.Tweet
import com.example.ui.components.FormatUtils
import com.example.ui.components.IlayLinkMovementText
import com.example.ui.components.LocalTweetCardHost
import com.example.ui.components.IlayContainedLoadingIndicator
import com.example.ui.components.IlayExpressiveTabPill
import com.example.ui.components.IlayLoadingIndicator
import com.example.ui.components.IlayPullToRefreshIndicator
import com.example.ui.components.LowPolyBannerArt
import com.example.ui.components.LowPolyEmptyStateArt
import com.example.ui.components.LowPolyScene
import com.example.ui.components.ProfileArticleCard
import com.example.ui.components.SwipeableTweetCard
import com.example.ui.components.UserAvatar
import com.example.ui.components.ilayVerticalScrollIndicator
import com.example.ui.theme.BookmarkAmber
import com.example.ui.theme.VerifiedBlue
import com.example.ui.viewmodel.ProfileUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    activeAccount: AccountEntity?,
    preferences: AppPreferences,
    isSelfTab: Boolean,
    onBack: (() -> Unit)?,
    onSelectTab: (ProfileTab) -> Unit,
    onRefresh: () -> Unit = {},
    onToggleFollow: () -> Unit,
    onTogglePinBoost: () -> Unit,
    onToggleMute: () -> Unit,
    onSetBlock: (Boolean) -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
    onRetry: () -> Unit = {},
    savedBookmarks: List<Pair<BookmarkEntity, Tweet>> = emptyList(),
    bookmarkFolders: List<Pair<String, Int>> = emptyList(),
    totalBookmarksCount: Int = savedBookmarks.size,
    selectedBookmarkFolder: String? = null,
    onSelectBookmarkFolder: (String?) -> Unit = {},
    onMoveBookmarkToFolder: (tweetId: String, folderTag: String) -> Unit = { _, _ -> },
    bookmarkFilter: String = "",
    onBookmarkFilterChange: (String) -> Unit = {},
    isSyncingBookmarks: Boolean = false,
    isLoadingMoreBookmarks: Boolean = false,
    hasMoreBookmarks: Boolean = false,
    onSyncBookmarks: () -> Unit = {},
    onLoadMoreBookmarks: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onCloseSearch: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onSelectSearchTab: (SearchTab) -> Unit = {},
    onSubmitSearch: () -> Unit = {},
    onLoadMoreSearch: () -> Unit = {},
) {
    var showOverflow by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var revealBlockedPosts by remember(uiState.user?.handle, uiState.isBlocked) { mutableStateOf(false) }
    var organizingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    val host = LocalTweetCardHost.current
    val haptic = LocalHapticFeedback.current
    val pullRefreshState = rememberPullToRefreshState()

    BackHandler(enabled = uiState.isSearchOpen) {
        onCloseSearch()
    }

    // Guest / Offline state on self Profile tab — still gives instant offline reading of Room Bookmarks!
    if (isSelfTab && activeAccount == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .widthIn(max = 640.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
            ) {
                if (onBack != null) {
                    item(key = "guest_profile_back_row") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("profile_guest_back_button"),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back),
                                )
                            }
                            Text("Profile", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                item(key = "guest_profile_card") {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LowPolyBannerArt(
                                contentDescriptionText = "Ilay for X low-poly geometric profile backdrop",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(18.dp)),
                            )
                            Spacer(Modifier.height(14.dp))
                            Text("Your X Profile & Offline Archive", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Sign in to manage multiple X accounts and sync your timeline, or browse your saved offline bookmarks below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onOpenSignIn,
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("profile_signin_button")
                                ) {
                                    Text("Sign in to X")
                                }
                                FilledTonalButton(
                                    onClick = onOpenSettings,
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("profile_settings_button")
                                ) {
                                    Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Settings")
                                }
                            }
                        }
                    }
                }

                item(key = "guest_bookmarks_header") {
                    OfflineBookmarksHeader(
                        count = totalBookmarksCount,
                        filterQuery = bookmarkFilter,
                        onFilterChange = onBookmarkFilterChange,
                        folders = bookmarkFolders,
                        selectedFolder = selectedBookmarkFolder,
                        onSelectFolder = onSelectBookmarkFolder,
                    )
                }

                if (savedBookmarks.isEmpty()) {
                    item(key = "guest_bookmarks_empty") {
                        OfflineBookmarksEmptyCard(isFiltered = bookmarkFilter.isNotBlank() || !selectedBookmarkFolder.isNullOrBlank())
                    }
                } else {
                    items(savedBookmarks, key = { "guest_bm_${it.first.tweetId}" }) { (entity, tweet) ->
                        OfflineBookmarkItem(
                            entity = entity,
                            tweet = tweet,
                            preferences = preferences,
                            onOrganizeFolder = { organizingBookmark = entity },
                            onOpenThread = onOpenThread,
                            onOpenProfile = onOpenProfile,
                            onLike = onLike,
                            onRepost = onRepost,
                            onQuote = onQuote,
                            onReply = onReply,
                            onBookmark = onBookmark,
                            onOpenMedia = onOpenMedia,
                        )
                    }
                }
            }
        }
        return
    }

    if (uiState.isSearchOpen && uiState.user != null) {
        ProfileSearchView(
            userHandle = uiState.user.handle,
            userDisplayName = uiState.user.displayName,
            uiState = uiState,
            preferences = preferences,
            onClose = onCloseSearch,
            onQueryChange = onSearchQueryChange,
            onSelectTab = onSelectSearchTab,
            onSubmit = onSubmitSearch,
            onLoadMore = onLoadMoreSearch,
            onOpenThread = onOpenThread,
            onOpenProfile = onOpenProfile,
            onLike = onLike,
            onRepost = onRepost,
            onQuote = onQuote,
            onReply = onReply,
            onBookmark = onBookmark,
            onOpenMedia = onOpenMedia,
        )
        return
    }

    PullToRefreshBox(
        isRefreshing = uiState.isUserRefreshing,
        onRefresh = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onRefresh()
        },
        state = pullRefreshState,
        indicator = {
            IlayPullToRefreshIndicator(
                state = pullRefreshState,
                isRefreshing = uiState.isUserRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )
        },
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .testTag("profile_pull_to_refresh"),
    ) {
        if (uiState.isLoading && uiState.user == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                IlayContainedLoadingIndicator()
            }
        } else if (uiState.user == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = uiState.errorMessage ?: "Profile unavailable",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onRetry, shape = CircleShape) {
                        Text("Retry")
                    }
                    if (isSelfTab) {
                        FilledTonalButton(onClick = onOpenAccountSwitcher, shape = CircleShape) {
                            Text("Accounts")
                        }
                        FilledTonalButton(onClick = onOpenSettings, shape = CircleShape) {
                            Text("Settings")
                        }
                    } else if (onBack != null) {
                        FilledTonalButton(onClick = onBack, shape = CircleShape) { Text("Go back") }
                    }
                }
            }
        } else {
            val user = uiState.user
            val isOwnProfile = activeAccount != null && activeAccount.handle.equals(user.handle, ignoreCase = true)
            // X hides Likes & Bookmarks on other users' profiles — show them only on own profile
            val visibleTabs = remember(isSelfTab, isOwnProfile) {
                if (isSelfTab || isOwnProfile) {
                    ProfileTab.entries
                } else {
                    ProfileTab.entries.filterNot { it == ProfileTab.BOOKMARKS || it == ProfileTab.LIKES }
                }
            }
            val listState = rememberLazyListState()
            val showStickyHeader by remember(listState) {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 140
                }
            }
            val previewAvatarCd = stringResource(R.string.profile_avatar_preview_cd)
            val previewBannerCd = stringResource(R.string.profile_banner_preview_cd)
            val openAvatarPreview: () -> Unit = {
                if (user.avatarUrl.isNotBlank()) {
                    onOpenMedia(
                        listOf(
                            MediaAttachment(
                                id = "avatar_${user.id}",
                                type = MediaType.PHOTO,
                                url = fullResProfileImageUrl(user.avatarUrl),
                                thumbnailUrl = user.avatarUrl,
                                altText = "${user.displayName.ifBlank { "@${user.handle}" }} profile picture",
                            )
                        ),
                        0,
                    )
                }
            }
            val openBannerPreview: () -> Unit = {
                if (user.bannerUrl.isNotBlank()) {
                    val resolvedBannerUrl = AdaptiveImageQualitySelector.selectVariant(
                        rawUrl = user.bannerUrl,
                        usage = ImageUsage.PROFILE_BANNER,
                        mode = ImageQualityMode.HIGH,
                        networkTier = NetworkTier.UNMETERED_FAST,
                    ).url
                    onOpenMedia(
                        listOf(
                            MediaAttachment(
                                id = "banner_${user.id}",
                                type = MediaType.PHOTO,
                                url = resolvedBannerUrl,
                                thumbnailUrl = resolvedBannerUrl,
                                altText = "${user.displayName.ifBlank { "@${user.handle}" }} banner",
                            )
                        ),
                        0,
                    )
                }
            }

            LaunchedEffect(
                listState,
                uiState.selectedTab,
                savedBookmarks.size,
                hasMoreBookmarks,
                isLoadingMoreBookmarks,
                bookmarkFilter,
            ) {
                if (uiState.selectedTab != ProfileTab.BOOKMARKS ||
                    !hasMoreBookmarks ||
                    isLoadingMoreBookmarks ||
                    bookmarkFilter.isNotBlank() ||
                    savedBookmarks.isEmpty()
                ) {
                    return@LaunchedEffect
                }
                snapshotFlow {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    val total = listState.layoutInfo.totalItemsCount
                    total > 0 && last >= total - 4
                }
                    .distinctUntilChanged()
                    .collect { nearEnd ->
                        if (nearEnd) onLoadMoreBookmarks()
                    }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .ilayVerticalScrollIndicator(state = listState),
                contentPadding = PaddingValues(bottom = 36.dp),
            ) {
                // Header Banner + Overlapping Avatar (non-clipped Box layering)
                item(key = "profile_header") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(210.dp),
                        ) {
                            // Top Banner Surface (tappable for full-res preview)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(162.dp)
                                    .align(Alignment.TopCenter)
                                    .then(
                                        if (user.bannerUrl.isNotBlank()) {
                                            Modifier.clickable(
                                                onClickLabel = previewBannerCd,
                                                onClick = openBannerPreview,
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .testTag("profile_banner_image"),
                            ) {
                                LowPolyBannerArt(
                                    contentDescriptionText = "Default low-poly geometric profile banner",
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (user.bannerUrl.isNotBlank()) {
                                    val bannerContext = LocalContext.current
                                    val imageQualityMode = LocalImageQualityMode.current
                                    var useRawBannerFallback by remember(user.id, user.bannerUrl) {
                                        mutableStateOf(false)
                                    }
                                    val bannerRequest = remember(
                                        user.id,
                                        user.bannerUrl,
                                        bannerContext,
                                        imageQualityMode,
                                        useRawBannerFallback,
                                    ) {
                                        IlayImageCache.bannerRequest(
                                            context = bannerContext,
                                            rawUrl = user.bannerUrl,
                                            mode = imageQualityMode,
                                            useRawUrl = useRawBannerFallback,
                                        )
                                    }
                                    key(user.id, user.bannerUrl, useRawBannerFallback) {
                                        AsyncImage(
                                            model = bannerRequest,
                                            contentDescription = "${user.displayName} banner",
                                            contentScale = ContentScale.Crop,
                                            onError = {
                                                if (!useRawBannerFallback) {
                                                    useRawBannerFallback = true
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }

                                // Top Floating Action Overlay
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (onBack != null) {
                                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
                                            IconButton(
                                                onClick = onBack,
                                                modifier = Modifier.testTag("profile_back_button"),
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                                            }
                                        }
                                    } else {
                                        Spacer(Modifier.width(1.dp))
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
                                            IconButton(
                                                onClick = onOpenSearch,
                                                modifier = Modifier.testTag("profile_search_button"),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Search,
                                                    contentDescription = stringResource(R.string.cd_profile_search),
                                                    tint = Color.White,
                                                )
                                            }
                                        }
                                        if (isSelfTab) {
                                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
                                                IconButton(
                                                    onClick = onOpenAccountSwitcher,
                                                    modifier = Modifier.testTag("profile_switch_account_btn")
                                                ) {
                                                    Icon(Icons.Outlined.SwitchAccount, contentDescription = "Switch account", tint = Color.White)
                                                }
                                            }
                                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
                                                IconButton(
                                                    onClick = onOpenSettings,
                                                    modifier = Modifier.testTag("profile_open_settings_btn")
                                                ) {
                                                    Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Color.White)
                                                }
                                            }
                                        } else if (!isOwnProfile) {
                                            Box {
                                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
                                                    IconButton(
                                                        onClick = { showOverflow = true },
                                                        modifier = Modifier.testTag("profile_more_options_btn"),
                                                    ) {
                                                        Icon(Icons.Outlined.MoreVert, contentDescription = "More user options", tint = Color.White)
                                                    }
                                                }
                                                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = if (uiState.isMuted) {
                                                                    Icons.AutoMirrored.Outlined.VolumeUp
                                                                } else {
                                                                    Icons.AutoMirrored.Outlined.VolumeOff
                                                                },
                                                                contentDescription = null,
                                                            )
                                                        },
                                                        text = {
                                                            Text(
                                                                if (uiState.isMuted) "Unmute @${user.handle}"
                                                                else "Mute @${user.handle}"
                                                            )
                                                        },
                                                        onClick = {
                                                            showOverflow = false
                                                            onToggleMute()
                                                        },
                                                        modifier = Modifier.testTag("profile_mute_menu_item"),
                                                    )
                                                    DropdownMenuItem(
                                                        leadingIcon = {
                                                            Icon(
                                                                imageVector = if (uiState.isBlocked) {
                                                                    Icons.Outlined.CheckCircle
                                                                } else {
                                                                    Icons.Outlined.Block
                                                                },
                                                                contentDescription = null,
                                                                tint = if (uiState.isBlocked) {
                                                                    MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    MaterialTheme.colorScheme.error
                                                                },
                                                            )
                                                        },
                                                        text = {
                                                            Text(
                                                                text = if (uiState.isBlocked) "Unblock @${user.handle}" else "Block @${user.handle}",
                                                                color = if (uiState.isBlocked) {
                                                                    MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    MaterialTheme.colorScheme.error
                                                                },
                                                            )
                                                        },
                                                        onClick = {
                                                            showOverflow = false
                                                            if (uiState.isBlocked) {
                                                                onSetBlock(false)
                                                            } else {
                                                                showBlockConfirmDialog = true
                                                            }
                                                        },
                                                        modifier = Modifier.testTag("profile_block_menu_item"),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Hero Profile Picture cleanly overlapping banner bottom edge (tap to preview full-res)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 16.dp, bottom = 4.dp)
                                    .zIndex(2f)
                                    .border(3.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                                    .clip(CircleShape)
                                    .clickable(
                                        enabled = user.avatarUrl.isNotBlank(),
                                        onClickLabel = previewAvatarCd,
                                        onClick = openAvatarPreview,
                                    )
                                    .testTag("profile_hero_avatar"),
                            ) {
                                UserAvatar(user.avatarUrl, user.displayName, size = 84.dp)
                            }

                            // Action Buttons aligned along the bottom-end below the banner
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!isOwnProfile) {
                                    if (uiState.isBlocked) {
                                        Button(
                                            onClick = { onSetBlock(false) },
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                            ),
                                            modifier = Modifier.testTag("profile_blocked_button"),
                                        ) {
                                            Icon(
                                                Icons.Outlined.Block,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("Blocked")
                                        }
                                    } else {
                                        val actionPillModifier = Modifier
                                            .width(112.dp)
                                            .height(40.dp)
                                        val actionPillPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)

                                        FilledTonalButton(
                                            onClick = onTogglePinBoost,
                                            shape = CircleShape,
                                            colors = if (uiState.isPinnedBoost) {
                                                ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                                )
                                            } else {
                                                ButtonDefaults.filledTonalButtonColors()
                                            },
                                            contentPadding = actionPillPadding,
                                            modifier = actionPillModifier.testTag("boost_creator_button"),
                                        ) {
                                            Icon(
                                                if (uiState.isPinnedBoost) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                                                contentDescription = "Boost in For You",
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = if (uiState.isPinnedBoost) "Boosted" else "Boost",
                                                style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        if (user.isFollowing) {
                                            OutlinedButton(
                                                onClick = onToggleFollow,
                                                shape = CircleShape,
                                                contentPadding = actionPillPadding,
                                                modifier = actionPillModifier.testTag("follow_button"),
                                            ) {
                                                Text(
                                                    text = "Following",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        } else {
                                            Button(
                                                onClick = onToggleFollow,
                                                shape = CircleShape,
                                                contentPadding = actionPillPadding,
                                                modifier = actionPillModifier.testTag("follow_button"),
                                            ) {
                                                Text(
                                                    text = "Follow",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Name, Handle, Bio, Stats (clean spacing with prominent Followers & Following)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.displayName.ifBlank { "@${user.handle}" },
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (user.isVerified) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = VerifiedBlue, modifier = Modifier.size(20.dp))
                                }
                            }
                            Text(
                                text = "@${user.handle}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!isOwnProfile && uiState.isMuted && !uiState.isBlocked) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .testTag("profile_muted_banner"),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Outlined.VolumeOff,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Muted · Excluded from For You & Following",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        TextButton(
                                            onClick = onToggleMute,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("profile_unmute_banner_button"),
                                        ) {
                                            Text("Unmute")
                                        }
                                    }
                                }
                            }
                            val cardHost = LocalTweetCardHost.current
                            val bioAccent = MaterialTheme.colorScheme.primary
                            if (user.bio.isNotBlank()) {
                                IlayLinkMovementText(
                                    text = user.bio,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    accentColor = bioAccent,
                                    onMentionClick = onOpenProfile,
                                    onHashtagClick = cardHost.openHashtag,
                                    onUrlClick = cardHost.openUrl,
                                    modifier = Modifier.testTag("profile_bio_text"),
                                )
                            }
                            if (user.websiteUrl.isNotBlank()) {
                                val displayWebsite = remember(user.websiteUrl) {
                                    FormatUtils.formatDisplayUrl(user.websiteUrl)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { cardHost.openUrl(user.websiteUrl) }
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                        .testTag("profile_website_link"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Link,
                                        contentDescription = stringResource(R.string.cd_profile_website_link),
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = displayWebsite,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            val formattedJoined = remember(user.joinedAtEpochMs, user.joinedLabel) {
                                FormatUtils.formatJoinedDate(user.joinedAtEpochMs, user.joinedLabel)
                            }
                            if (user.location.isNotBlank() || formattedJoined.isNotBlank()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    if (user.location.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(3.dp))
                                            Text(user.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (formattedJoined.isNotBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(3.dp))
                                            Text(formattedJoined, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .testTag("profile_follow_stats_row"),
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = FormatUtils.compactCount(user.followingCount).ifBlank { "0" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "Following",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = FormatUtils.compactCount(user.followersCount).ifBlank { "0" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "Followers",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        // MD3 Expressive Shape-Morphing Profile Tabs
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visibleTabs) { tab ->
                                val selected = uiState.selectedTab == tab
                                val labelText = if (tab == ProfileTab.BOOKMARKS && totalBookmarksCount > 0) {
                                    "${tab.label} ($totalBookmarksCount)"
                                } else {
                                    tab.label
                                }
                                IlayExpressiveTabPill(
                                    label = labelText,
                                    selected = selected,
                                    onClick = { onSelectTab(tab) },
                                    leadingIcon = when (tab) {
                                        ProfileTab.BOOKMARKS -> {
                                            {
                                                Icon(
                                                    Icons.Outlined.BookmarkBorder,
                                                    contentDescription = null,
                                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else BookmarkAmber,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }
                                        }
                                        ProfileTab.ARTICLES -> {
                                            {
                                                Icon(
                                                    Icons.AutoMirrored.Outlined.Article,
                                                    contentDescription = null,
                                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }
                                        }
                                        else -> null
                                    },
                                    testTag = "profile_tab_${tab.name.lowercase()}",
                                )
                            }
                        }
                    }
                }

                if (!isOwnProfile && uiState.isBlocked && !revealBlockedPosts) {
                    item(key = "profile_blocked_notice") {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                                .testTag("profile_blocked_card"),
                        ) {
                            Column(
                                modifier = Modifier.padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Block,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = "@${user.handle} is blocked",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Their posts, reposts, quoted posts, and replies are hidden from your For You and Following feeds.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    Button(
                                        onClick = { onSetBlock(false) },
                                        shape = CircleShape,
                                        modifier = Modifier.testTag("profile_unblock_button"),
                                    ) {
                                        Text("Unblock")
                                    }
                                    OutlinedButton(
                                        onClick = { revealBlockedPosts = true },
                                        shape = CircleShape,
                                        modifier = Modifier.testTag("profile_view_blocked_posts_button"),
                                    ) {
                                        Text("View posts")
                                    }
                                }
                            }
                        }
                    }
                } else when (uiState.selectedTab) {
                    ProfileTab.BOOKMARKS -> {
                        item(key = "profile_offline_bookmarks_header") {
                            OfflineBookmarksHeader(
                                count = totalBookmarksCount,
                                filterQuery = bookmarkFilter,
                                onFilterChange = onBookmarkFilterChange,
                                folders = bookmarkFolders,
                                selectedFolder = selectedBookmarkFolder,
                                onSelectFolder = onSelectBookmarkFolder,
                                isSyncing = isSyncingBookmarks,
                                onSync = if (activeAccount != null) onSyncBookmarks else null,
                            )
                        }
                        if (savedBookmarks.isEmpty()) {
                            item(key = "profile_offline_bookmarks_empty") {
                                OfflineBookmarksEmptyCard(
                                    isFiltered = bookmarkFilter.isNotBlank() || !selectedBookmarkFolder.isNullOrBlank()
                                )
                            }
                        } else {
                            items(savedBookmarks, key = { "profile_bm_${it.first.tweetId}" }) { (entity, tweet) ->
                                OfflineBookmarkItem(
                                    entity = entity,
                                    tweet = tweet,
                                    preferences = preferences,
                                    onOrganizeFolder = { organizingBookmark = entity },
                                    onOpenThread = onOpenThread,
                                    onOpenProfile = onOpenProfile,
                                    onLike = onLike,
                                    onRepost = onRepost,
                                    onQuote = onQuote,
                                    onReply = onReply,
                                    onBookmark = onBookmark,
                                    onOpenMedia = onOpenMedia,
                                )
                            }
                            val allowsPaging = selectedBookmarkFolder == null ||
                                selectedBookmarkFolder.equals("Synced from X", ignoreCase = true)
                            if (activeAccount != null && bookmarkFilter.isBlank() && allowsPaging && (isLoadingMoreBookmarks || hasMoreBookmarks)) {
                                item(key = "profile_bookmarks_load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isLoadingMoreBookmarks) {
                                            IlayLoadingIndicator(size = 24.dp)
                                        } else {
                                            FilledTonalButton(
                                                onClick = onLoadMoreBookmarks,
                                                shape = CircleShape,
                                            ) {
                                                Text("Load older bookmarks")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ProfileTab.ARTICLES -> {
                        if (uiState.isTabLoading && uiState.articles.isEmpty()) {
                            item(key = "articles_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    IlayLoadingIndicator(size = 34.dp)
                                }
                            }
                        } else if (uiState.articles.isEmpty()) {
                            item(key = "articles_empty") {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        LowPolyEmptyStateArt(
                                            scene = LowPolyScene.ARCHIVE,
                                            contentDescriptionText = "No articles published low-poly artwork",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(110.dp)
                                                .clip(RoundedCornerShape(16.dp)),
                                        )
                                        Text(
                                            text = "No Articles Yet",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "Long-form X Articles published by @${user.handle} will appear here for native in-app reading.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        } else {
                            items(uiState.articles, key = { "art_${it.id}" }) { articleTweet ->
                                ProfileArticleCard(
                                    tweet = articleTweet,
                                    onOpenArticle = { t, a -> host.openArticle(t, a) },
                                    onOpenThread = onOpenThread,
                                    onOpenProfile = onOpenProfile,
                                    onLike = onLike,
                                    onRepost = onRepost,
                                    onBookmark = onBookmark,
                                )
                            }
                        }
                    }

                    else -> {
                        if (uiState.isTabLoading && uiState.tweets.isEmpty()) {
                            item(key = "profile_tab_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    IlayLoadingIndicator(size = 34.dp)
                                }
                            }
                        } else if (uiState.tweets.isEmpty()) {
                            item(key = "profile_tab_empty_${uiState.selectedTab.name}") {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 14.dp)
                                        .testTag("profile_tab_empty_card"),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(22.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        val emptyTitle = when (uiState.selectedTab) {
                                            ProfileTab.POSTS -> "No posts yet"
                                            ProfileTab.REPLIES -> "No replies yet"
                                            ProfileTab.MEDIA -> "No media yet"
                                            ProfileTab.LIKES -> "No likes yet"
                                        }
                                        Text(
                                            text = emptyTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = uiState.errorMessage ?: "Pull down to refresh @${user.handle}'s ${uiState.selectedTab.label.lowercase()}.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        } else {
                            items(uiState.tweets, key = { it.id }) { tweet ->
                                SwipeableTweetCard(
                                    tweet = tweet,
                                    density = preferences.feedDensity,
                                    swipeRightAction = preferences.swipeRightAction,
                                    swipeLeftAction = preferences.swipeLeftAction,
                                    showRankBadge = false,
                                    blurSensitiveMedia = preferences.blurSensitiveMedia,
                                    onOpenThread = onOpenThread,
                                    onOpenProfile = onOpenProfile,
                                    onLike = onLike,
                                    onRepost = onRepost,
                                    onQuote = onQuote,
                                    onReply = onReply,
                                    onBookmark = onBookmark,
                                    onOpenMedia = onOpenMedia,
                                )
                            }
                        }
                    }
                }
            }

            // Sticky collapsing top bar displaying profile picture + name at the top while scrolled
            AnimatedVisibility(
                visible = showStickyHeader,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onBack != null) {
                                IconButton(
                                    onClick = onBack,
                                    modifier = Modifier.testTag("profile_sticky_back_button"),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back),
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(
                                        enabled = user.avatarUrl.isNotBlank(),
                                        onClickLabel = previewAvatarCd,
                                        onClick = openAvatarPreview,
                                    )
                                    .testTag("profile_sticky_avatar"),
                            ) {
                                UserAvatar(user.avatarUrl, user.displayName, size = 34.dp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = user.displayName.ifBlank { "@${user.handle}" },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (user.isVerified) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            Icons.Filled.Verified,
                                            contentDescription = null,
                                            tint = VerifiedBlue,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = "@${user.handle}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = onOpenSearch,
                                modifier = Modifier.testTag("profile_sticky_search_button"),
                            ) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.cd_profile_search),
                                )
                            }
                            if (isSelfTab) {
                                IconButton(onClick = onOpenAccountSwitcher) {
                                    Icon(Icons.Outlined.SwitchAccount, contentDescription = "Switch account")
                                }
                                IconButton(onClick = onOpenSettings) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                                }
                            } else if (!isOwnProfile) {
                                IconButton(onClick = { showOverflow = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More user options")
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
            }
        }
    }

    organizingBookmark?.let { entity ->
        BookmarkFolderPickerDialog(
            currentFolder = entity.folderTag.ifBlank { "Saved" },
            existingFolders = bookmarkFolders.map { it.first },
            onSelectFolder = { newFolder ->
                onMoveBookmarkToFolder(entity.tweetId, newFolder)
                organizingBookmark = null
            },
            onDismiss = { organizingBookmark = null },
        )
    }

    if (showBlockConfirmDialog) {
        val targetHandle = uiState.user?.handle.orEmpty()
        if (targetHandle.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { showBlockConfirmDialog = false },
                icon = {
                    Icon(
                        Icons.Outlined.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                title = { Text("Block @$targetHandle?") },
                text = {
                    Text(
                        "Posts, reposts, quotes, and replies from @$targetHandle will be immediately excluded from your For You and Following feeds, and synced with X when signed in."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBlockConfirmDialog = false
                            onSetBlock(true)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.testTag("profile_confirm_block_btn"),
                    ) {
                        Text("Block")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBlockConfirmDialog = false }) {
                        Text("Cancel")
                    }
                },
                modifier = Modifier.testTag("profile_confirm_block_dialog"),
            )
        }
    }
}

@Composable
private fun OfflineBookmarksHeader(
    count: Int,
    filterQuery: String,
    onFilterChange: (String) -> Unit,
    folders: List<Pair<String, Int>> = emptyList(),
    selectedFolder: String? = null,
    onSelectFolder: (String?) -> Unit = {},
    isSyncing: Boolean = false,
    onSync: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = BookmarkAmber,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Bookmarks ($count) · Newest first",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (onSync != null) {
                FilledTonalButton(
                    onClick = onSync,
                    enabled = !isSyncing,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    if (isSyncing) {
                        IlayLoadingIndicator(size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Sync X", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Text(
                    text = "Saved locally",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (count > 0 || filterQuery.isNotBlank()) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = onFilterChange,
                placeholder = { Text("Filter saved posts offline…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filterQuery.isNotEmpty()) {
                        IconButton(onClick = { onFilterChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear filter")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .testTag("profile_bookmarks_filter_input"),
            )
        }
        if (count > 0) {
            BookmarkFolderTabsRow(
                folders = folders,
                totalCount = count,
                selectedFolder = selectedFolder,
                onSelectFolder = onSelectFolder,
            )
        }
    }
}

@Composable
private fun OfflineBookmarksEmptyCard(isFiltered: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LowPolyEmptyStateArt(
                scene = LowPolyScene.ARCHIVE,
                contentDescriptionText = "Offline bookmarks low-poly geometric archive artwork",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Text(
                text = if (isFiltered) "No Matching Bookmarks" else "No Offline Bookmarks Yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (isFiltered) {
                    "Try a different keyword to search your saved tweets."
                } else {
                    "Tap the bookmark icon on any post to save it locally in Room for instant offline reading right here in your profile."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OfflineBookmarkItem(
    entity: BookmarkEntity,
    tweet: Tweet,
    preferences: AppPreferences,
    onOrganizeFolder: () -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
) {
    val context = LocalContext.current
    val savedTime = remember(entity.savedAtEpochMs, context) {
        FormatUtils.formatDeviceTime(context, entity.savedAtEpochMs)
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entity.folderTag.ifBlank { "Saved" },
                style = MaterialTheme.typography.labelSmall,
                color = BookmarkAmber,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOrganizeFolder)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Text(
                text = if (savedTime.isNotBlank()) "Saved $savedTime" else "Available offline",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwipeableTweetCard(
            tweet = tweet,
            density = preferences.feedDensity,
            swipeRightAction = preferences.swipeRightAction,
            swipeLeftAction = preferences.swipeLeftAction,
            showRankBadge = false,
            blurSensitiveMedia = preferences.blurSensitiveMedia,
            onOpenThread = onOpenThread,
            onOpenProfile = onOpenProfile,
            onLike = onLike,
            onRepost = onRepost,
            onQuote = onQuote,
            onReply = onReply,
            onBookmark = onBookmark,
            onOpenMedia = onOpenMedia,
        )
    }
}

internal fun fullResProfileImageUrl(url: String): String =
    url.replace(Regex("_(normal|bigger|mini|200x200|400x400)(?=\\.[A-Za-z0-9]+($|\\?))"), "")

@Composable
private fun ProfileSearchView(
    userHandle: String,
    userDisplayName: String,
    uiState: ProfileUiState,
    preferences: AppPreferences,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    onSubmit: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val profileSearchTabs = remember { listOf(SearchTab.LATEST, SearchTab.TOP, SearchTab.MEDIA) }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(listState, uiState.searchNextCursor, uiState.isLoadingMoreSearch, uiState.searchResults.size) {
        if (uiState.searchNextCursor == null || uiState.isLoadingMoreSearch || uiState.searchResults.isEmpty()) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 4
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd) onLoadMore()
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .testTag("profile_search_view"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = {
                        keyboardController?.hide()
                        onClose()
                    },
                    modifier = Modifier.testTag("profile_search_back_button"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                    )
                }
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.profile_search_placeholder, userHandle.ifBlank { "user" }),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.testTag("profile_search_clear_button"),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboardController?.hide()
                            onSubmit()
                        },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag("profile_search_input"),
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profileSearchTabs) { tab ->
                    IlayExpressiveTabPill(
                        label = tab.label,
                        selected = uiState.searchTab == tab,
                        onClick = { onSelectTab(tab) },
                        testTag = "profile_search_tab_${tab.name.lowercase()}",
                    )
                }
            }

            if (uiState.isSearching && uiState.searchResults.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            } else {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .ilayVerticalScrollIndicator(state = listState),
                contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
            ) {
                when {
                    uiState.searchQuery.isBlank() -> {
                        item(key = "profile_search_prompt") {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    Text(
                                        text = "Search ${userDisplayName.ifBlank { "@$userHandle" }}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Type keywords, #hashtags, or phrases to find posts from @$userHandle.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    uiState.isSearching && uiState.searchResults.isEmpty() -> {
                        item(key = "profile_search_loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                IlayLoadingIndicator(size = 34.dp)
                            }
                        }
                    }

                    uiState.hasSearchedProfile && uiState.searchResults.isEmpty() -> {
                        item(key = "profile_search_empty") {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("profile_search_empty_card"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "No matching posts",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = uiState.searchError
                                            ?: "No posts from @$userHandle matched \"${uiState.searchQuery.trim()}\".",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        items(uiState.searchResults, key = { "psearch_${it.id}" }) { tweet ->
                            SwipeableTweetCard(
                                tweet = tweet,
                                density = preferences.feedDensity,
                                swipeRightAction = preferences.swipeRightAction,
                                swipeLeftAction = preferences.swipeLeftAction,
                                showRankBadge = false,
                                blurSensitiveMedia = preferences.blurSensitiveMedia,
                                onOpenThread = onOpenThread,
                                onOpenProfile = onOpenProfile,
                                onLike = onLike,
                                onRepost = onRepost,
                                onQuote = onQuote,
                                onReply = onReply,
                                onBookmark = onBookmark,
                                onOpenMedia = onOpenMedia,
                            )
                        }
                        if (uiState.isLoadingMoreSearch || uiState.searchNextCursor != null) {
                            item(key = "profile_search_load_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (uiState.isLoadingMoreSearch) {
                                        IlayLoadingIndicator(size = 24.dp)
                                    } else {
                                        FilledTonalButton(
                                            onClick = onLoadMore,
                                            shape = CircleShape,
                                        ) {
                                            Text("Load more results")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
