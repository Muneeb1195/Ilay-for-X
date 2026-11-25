package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.local.BookmarkEntity
import com.example.domain.model.MediaAttachment
import com.example.domain.model.Tweet
import com.example.ui.components.IlayCollapsingTopBar
import com.example.ui.components.IlayContainedLoadingIndicator
import com.example.ui.components.IlayLoadingIndicator
import com.example.ui.components.LocalIlayChromeState
import com.example.ui.components.ilayVerticalScrollIndicator
import com.example.ui.components.LowPolyEmptyStateArt
import com.example.ui.components.LowPolyScene
import com.example.ui.components.SwipeableTweetCard
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun BookmarksScreen(
    bookmarks: List<Pair<BookmarkEntity, Tweet>>,
    searchQuery: String,
    isSignedIn: Boolean,
    isSyncing: Boolean,
    isLoadingMore: Boolean,
    hasMoreRemote: Boolean,
    preferences: AppPreferences,
    onSearchQueryChange: (String) -> Unit,
    onSyncFromX: () -> Unit,
    onLoadMore: () -> Unit,
    onEnsureSync: () -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onRemoveBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
) {
    val chromeState = LocalIlayChromeState.current
    val listState = rememberLazyListState()

    LaunchedEffect(isSignedIn) {
        if (isSignedIn) onEnsureSync()
    }

    // Automatic infinite pagination as user scrolls near the bottom of newest-first bookmarks
    LaunchedEffect(listState, bookmarks.size, isSignedIn, hasMoreRemote, isLoadingMore, searchQuery) {
        if (!isSignedIn || !hasMoreRemote || isLoadingMore || searchQuery.isNotBlank() || bookmarks.isEmpty()) {
            return@LaunchedEffect
        }
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 4
        }
            .distinctUntilChanged()
            .collect { nearEnd ->
                if (nearEnd) onLoadMore()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        IlayCollapsingTopBar(
            title = "Bookmarks",
            subtitle = "${bookmarks.size} saved · Newest first · Offline archive",
            collapseProgress = chromeState.collapseProgress,
            actions = {
                FilledTonalButton(
                    onClick = onSyncFromX,
                    enabled = !isSyncing,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("sync_bookmarks_button"),
                ) {
                    if (isSyncing) {
                        IlayLoadingIndicator(size = 18.dp)
                    } else {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Sync X", style = MaterialTheme.typography.labelLarge)
                }
            },
            bottomExpandedContent = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text(stringResource(R.string.bookmarks_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
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
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("bookmarks_search_input"),
                )
            },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (bookmarks.isEmpty() && !isSyncing) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LowPolyEmptyStateArt(
                        scene = LowPolyScene.ARCHIVE,
                        contentDescriptionText = "Bookmarks low-poly geometric archive artwork",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = if (searchQuery.isBlank()) "No Bookmarks Yet" else "No Matching Bookmarks",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Saved posts appear here for offline reading.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (bookmarks.isEmpty() && isSyncing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IlayContainedLoadingIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .ilayVerticalScrollIndicator(state = listState),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 112.dp),
                ) {
                    items(bookmarks, key = { it.first.tweetId }) { (_, tweet) ->
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
                            onBookmark = onRemoveBookmark,
                            onOpenMedia = onOpenMedia,
                        )
                    }

                    if (isSignedIn && searchQuery.isBlank() && (isLoadingMore || hasMoreRemote)) {
                        item(key = "bookmarks_load_more_footer") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isLoadingMore) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IlayLoadingIndicator(size = 22.dp)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "Loading older bookmarks…",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = onLoadMore,
                                        shape = CircleShape,
                                        modifier = Modifier.testTag("bookmarks_load_more_button"),
                                    ) {
                                        Text("Load older bookmarks")
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
