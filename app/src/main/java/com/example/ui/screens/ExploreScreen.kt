package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.local.RecentSearchEntity
import com.example.domain.model.MediaAttachment
import com.example.domain.model.SearchTab
import com.example.domain.model.Tweet
import com.example.domain.model.XUser
import com.example.ui.components.FormatUtils
import com.example.ui.components.IlayCollapsingTopBar
import com.example.ui.components.IlayContainedLoadingIndicator
import com.example.ui.components.IlayExpressiveTabPill
import com.example.ui.components.IlayLinkMovementText
import com.example.ui.components.IlayLoadingIndicator
import com.example.ui.components.IlayPullToRefreshIndicator
import com.example.ui.components.ilayVerticalScrollIndicator
import com.example.ui.components.rememberIlayChromeState
import com.example.ui.components.SwipeableTweetCard
import com.example.ui.components.UserAvatar
import com.example.ui.theme.VerifiedBlue
import com.example.ui.viewmodel.ExploreUiState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    uiState: ExploreUiState,
    recentSearches: List<RecentSearchEntity>,
    preferences: AppPreferences,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String?) -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    onRefresh: () -> Unit = {},
    onDeleteRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
    onLoadMore: () -> Unit = {},
    onBack: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val chromeState = rememberIlayChromeState()
    val pullRefreshState = rememberPullToRefreshState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 640.dp)
            .nestedScroll(chromeState.nestedScrollConnection),
    ) {
        IlayCollapsingTopBar(
            title = if (uiState.hasSearched && uiState.query.isNotBlank()) {
                uiState.query.trim()
            } else {
                "Explore"
            },
            subtitle = if (uiState.hasSearched) {
                "Global X search · ${uiState.activeTab.label}"
            } else {
                "Search posts, #hashtags, and accounts"
            },
            collapseProgress = chromeState.collapseProgress,
            navigationIcon = if (onBack != null) {
                {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("explore_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                }
            } else {
                null
            },
            bottomExpandedContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Search Input Bar
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                onSubmitSearch(null)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("explore_search_input"),
                    )

                    if (uiState.hasSearched) {
                        // MD3 Expressive shape-morphing Search Tabs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchTab.entries.forEach { tab ->
                                IlayExpressiveTabPill(
                                    label = tab.label,
                                    selected = uiState.activeTab == tab,
                                    onClick = { onSelectTab(tab) },
                                    testTag = "search_tab_${tab.name.lowercase()}",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            },
        )

        if (uiState.isDebouncing || (uiState.isLoading && uiState.tweetResults.isNotEmpty())) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(2.dp)
                    .testTag("explore_debounce_indicator"),
            )
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
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("explore_pull_to_refresh"),
        ) {
        if (uiState.hasSearched) {
            if (uiState.isLoading && uiState.tweetResults.isEmpty() && uiState.peopleResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IlayContainedLoadingIndicator()
                }
            } else if (uiState.activeTab == SearchTab.PEOPLE) {
                val peopleListState = rememberLazyListState()
                LazyColumn(
                    state = peopleListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .ilayVerticalScrollIndicator(state = peopleListState),
                    contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(uiState.peopleResults, key = { it.id }) { user ->
                        UserSearchResultCard(user = user, onClick = { onOpenProfile(user.handle) })
                    }
                    if (uiState.peopleResults.isEmpty()) {
                        item {
                            Text(
                                text = uiState.errorMessage ?: "No matching accounts found for \"${uiState.query}\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            } else {
                val tweetResultsState = rememberLazyListState()
                LazyColumn(
                    state = tweetResultsState,
                    modifier = Modifier
                        .fillMaxSize()
                        .ilayVerticalScrollIndicator(state = tweetResultsState),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp),
                ) {
                    if (uiState.relatedHashtags.isNotEmpty() || uiState.relatedTopics.isNotEmpty()) {
                        item(key = "related_hashtags_topics_row") {
                            val combinedSuggestions = (uiState.relatedHashtags + uiState.relatedTopics)
                                .distinctBy { it.lowercase() }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("search_related_hashtags_row"),
                            ) {
                                items(combinedSuggestions, key = { it.lowercase() }) { suggestion ->
                                    val isHashtag = suggestion.startsWith("#")
                                    AssistChip(
                                        onClick = {
                                            focusManager.clearFocus()
                                            onSubmitSearch(suggestion)
                                        },
                                        label = {
                                            Text(
                                                text = suggestion,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingIcon = if (isHashtag) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Filled.Tag,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        } else {
                                            {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        },
                                        shape = CircleShape,
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (isHashtag) {
                                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            },
                                            labelColor = if (isHashtag) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            leadingIconContentColor = if (isHashtag) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        ),
                                        modifier = Modifier.testTag(
                                            "suggestion_chip_${suggestion.removePrefix("#").lowercase().replace(" ", "_")}"
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    items(uiState.tweetResults, key = { it.id }) { tweet ->
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

                    if (uiState.nextCursor != null) {
                        item(key = "search_load_more") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiState.isLoadingMore) {
                                    IlayLoadingIndicator(size = 30.dp)
                                } else {
                                    FilledTonalButton(
                                        onClick = onLoadMore,
                                        shape = CircleShape,
                                        modifier = Modifier.testTag("search_load_more_button"),
                                    ) {
                                        Text("Load more results")
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.tweetResults.isEmpty()) {
                        item {
                            Text(
                                text = uiState.errorMessage ?: "No global posts matched \"${uiState.query}\". Try another keyword or switch tabs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                }
            }
        } else {
            // Trending & Recent Searches View
            val trendsListState = rememberLazyListState()
            LazyColumn(
                state = trendsListState,
                modifier = Modifier
                    .fillMaxSize()
                    .ilayVerticalScrollIndicator(state = trendsListState),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recentSearches.isNotEmpty()) {
                    item(key = "recent_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Recent Searches", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = onClearRecent) { Text("Clear all") }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            recentSearches.forEach { recent ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onSubmitSearch(recent.query)
                                    },
                                    label = { Text(recent.query) },
                                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove",
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { onDeleteRecent(recent.query) }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                item(key = "trends_title") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.TrendingUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Trending & Discover", style = MaterialTheme.typography.titleLarge)
                        }
                        val statusText = when {
                            uiState.trendsSource.isNotBlank() && uiState.trendsUpdatedAtEpochMs > 0L -> {
                                val updatedRel = FormatUtils.relativeTime(uiState.trendsUpdatedAtEpochMs)
                                val timePart = if (updatedRel == "now") "just now" else "$updatedRel ago"
                                "${uiState.trendsSource} · Updated $timePart · Pull to refresh"
                            }
                            uiState.trendsSource.isNotBlank() -> "${uiState.trendsSource} · Pull to refresh"
                            else -> "Pull down to refresh live X trends"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (uiState.trends.isEmpty() && uiState.isLoading) {
                    item(key = "trends_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            IlayLoadingIndicator(size = 34.dp)
                        }
                    }
                } else if (uiState.trends.isEmpty()) {
                    item(key = "trends_empty") {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = uiState.trendsError ?: "Pull down to load live trends from X.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(
                                    onClick = onRefresh,
                                    shape = CircleShape,
                                    modifier = Modifier.testTag("trends_retry_button"),
                                ) {
                                    Text("Refresh trends")
                                }
                            }
                        }
                    }
                }

                items(uiState.trends, key = { it.id }) { trend ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                focusManager.clearFocus()
                                onSubmitSearch(trend.query)
                            }
                            .testTag("trend_card_${trend.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = trend.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = trend.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (trend.tweetVolumeLabel.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = trend.tweetVolumeLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(28.dp)) }
            }
        }
        }
    }
}

@Composable
private fun UserSearchResultCard(
    user: XUser,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("user_result_${user.handle}")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            UserAvatar(user.avatarUrl, user.displayName, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (user.isVerified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = VerifiedBlue, modifier = Modifier.size(15.dp))
                    }
                }
                val subtitleText = when {
                    user.followersCount > 0L ->
                        "@${user.handle} · ${FormatUtils.compactCount(user.followersCount)} followers"
                    user.followingCount > 0L || user.tweetsCount > 0L ->
                        "@${user.handle} · 0 followers"
                    else -> "@${user.handle}"
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (user.bio.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    IlayLinkMovementText(
                        text = user.bio,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("user_result_bio_${user.handle}"),
                    )
                }
            }
        }
    }
}
