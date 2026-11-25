package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.local.BookmarkEntity
import com.example.domain.model.MediaAttachment
import com.example.domain.model.Tweet
import com.example.ui.components.FormatUtils
import com.example.ui.components.IlayCollapsingTopBar
import com.example.ui.components.IlayContainedLoadingIndicator
import com.example.ui.components.IlayExpressiveTabPill
import com.example.ui.components.IlayLoadingIndicator
import com.example.ui.components.LocalIlayChromeState
import com.example.ui.components.LowPolyEmptyStateArt
import com.example.ui.components.LowPolyScene
import com.example.ui.components.SwipeableTweetCard
import com.example.ui.components.ilayVerticalScrollIndicator
import com.example.ui.theme.BookmarkAmber
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
    folders: List<Pair<String, Int>> = emptyList(),
    totalBookmarkCount: Int = bookmarks.size,
    selectedFolder: String? = null,
    onSelectFolder: (String?) -> Unit = {},
    onMoveToFolder: (tweetId: String, folderTag: String) -> Unit = { _, _ -> },
) {
    val chromeState = LocalIlayChromeState.current
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var organizingEntity by remember { mutableStateOf<BookmarkEntity?>(null) }

    LaunchedEffect(isSignedIn) {
        if (isSignedIn) onEnsureSync()
    }

    // Automatic infinite pagination as user scrolls near the bottom of newest-first bookmarks
    LaunchedEffect(listState, bookmarks.size, isSignedIn, hasMoreRemote, isLoadingMore, searchQuery, selectedFolder) {
        val allowsPaging = selectedFolder == null || selectedFolder.equals("Synced from X", ignoreCase = true)
        if (!isSignedIn || !hasMoreRemote || isLoadingMore || searchQuery.isNotBlank() || !allowsPaging || bookmarks.isEmpty()) {
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
            subtitle = if (selectedFolder.isNullOrBlank()) {
                "$totalBookmarkCount saved · Newest bookmarked first"
            } else {
                "$selectedFolder (${bookmarks.size}) · Newest first"
            },
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
                Column(modifier = Modifier.fillMaxWidth()) {
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

                    if (totalBookmarkCount > 0) {
                        BookmarkFolderTabsRow(
                            folders = folders,
                            totalCount = totalBookmarkCount,
                            selectedFolder = selectedFolder,
                            onSelectFolder = onSelectFolder,
                        )
                    }
                }
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
                        text = when {
                            searchQuery.isNotBlank() -> "No Matching Bookmarks"
                            !selectedFolder.isNullOrBlank() -> "No Posts in $selectedFolder"
                            else -> "No Bookmarks Yet"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (!selectedFolder.isNullOrBlank()) {
                            "Switch to All or move a saved post into this folder."
                        } else {
                            "Saved posts appear here from newest bookmarked to oldest for offline reading."
                        },
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
                    items(bookmarks, key = { it.first.tweetId }) { (entity, tweet) ->
                        val savedTime = remember(entity.savedAtEpochMs, context) {
                            FormatUtils.formatDeviceTime(context, entity.savedAtEpochMs)
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { organizingEntity = entity }
                                        .testTag("bookmark_folder_chip_${entity.tweetId}"),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Folder,
                                            contentDescription = "Change bookmark folder",
                                            tint = BookmarkAmber,
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            text = entity.folderTag.ifBlank { "Saved" },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                if (savedTime.isNotBlank()) {
                                    Text(
                                        text = "Saved $savedTime",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                                onBookmark = onRemoveBookmark,
                                onOpenMedia = onOpenMedia,
                            )
                        }
                    }

                    val allowsPaging = selectedFolder == null || selectedFolder.equals("Synced from X", ignoreCase = true)
                    if (isSignedIn && searchQuery.isBlank() && allowsPaging && (isLoadingMore || hasMoreRemote)) {
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

    organizingEntity?.let { entity ->
        BookmarkFolderPickerDialog(
            currentFolder = entity.folderTag.ifBlank { "Saved" },
            existingFolders = folders.map { it.first },
            onSelectFolder = { newFolder ->
                onMoveToFolder(entity.tweetId, newFolder)
                organizingEntity = null
            },
            onDismiss = { organizingEntity = null },
        )
    }
}

@Composable
internal fun BookmarkFolderTabsRow(
    folders: List<Pair<String, Int>>,
    totalCount: Int,
    selectedFolder: String?,
    onSelectFolder: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "folder_tab_all") {
            IlayExpressiveTabPill(
                label = "All ($totalCount)",
                selected = selectedFolder == null,
                onClick = { onSelectFolder(null) },
                testTag = "bookmark_folder_tab_all",
            )
        }
        items(folders, key = { "folder_tab_${it.first}" }) { (folderName, count) ->
            val selected = selectedFolder.equals(folderName, ignoreCase = true)
            IlayExpressiveTabPill(
                label = "$folderName ($count)",
                selected = selected,
                onClick = { onSelectFolder(if (selected) null else folderName) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else BookmarkAmber,
                        modifier = Modifier.size(15.dp),
                    )
                },
                testTag = "bookmark_folder_tab_${folderName.lowercase().replace(' ', '_')}",
            )
        }
    }
}

@Composable
internal fun BookmarkFolderPickerDialog(
    currentFolder: String,
    existingFolders: List<String>,
    onSelectFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val presetSuggestions = remember(existingFolders) {
        (existingFolders + listOf("Saved", "Read Later", "Tech", "Inspiration"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }
    var customName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = BookmarkAmber,
            )
        },
        title = { Text("Organize Bookmark") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Choose a folder or create a new one:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presetSuggestions, key = { it }) { folder ->
                        IlayExpressiveTabPill(
                            label = folder,
                            selected = folder.equals(currentFolder, ignoreCase = true),
                            onClick = { onSelectFolder(folder) },
                            testTag = "bookmark_picker_folder_${folder.lowercase().replace(' ', '_')}",
                        )
                    }
                }
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    placeholder = { Text("New folder name…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bookmark_custom_folder_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = customName.trim().ifBlank { currentFolder }
                    onSelectFolder(target)
                },
                enabled = customName.isNotBlank(),
                shape = CircleShape,
                modifier = Modifier.testTag("bookmark_picker_save_button"),
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
