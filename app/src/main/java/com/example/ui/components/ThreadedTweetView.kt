package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.AppPreferences
import com.example.data.remote.IlayImageCache
import com.example.data.remote.TranslationResult
import com.example.domain.model.FeedDensity
import com.example.domain.model.MediaAttachment
import com.example.domain.model.ThreadConversation
import com.example.domain.model.Tweet
import com.example.ui.theme.BookmarkAmber
import com.example.ui.theme.LikeRose
import com.example.ui.theme.RepostEmerald
import com.example.ui.theme.VerifiedBlue
import com.example.ui.viewmodel.ThreadTab
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Explicit content-type buckets so Compose's LazyColumn subcomposition pool reuses
 * matching slot structures across long threads without discarding & recreating nodes.
 */
enum class ThreadItemContentType {
    ANCESTOR,
    ANCESTOR_TOGGLE,
    FOCAL,
    TAB_SWITCHER,
    REPLY,
    QUOTE,
    LOAD_MORE,
    LOADING,
    EMPTY_MESSAGE,
}

/**
 * Flattened, immutable representation of every row rendered inside [ThreadedTweetView]'s
 * single [LazyColumn]. Deterministic [stableKey] + [contentType] guarantee minimal
 * recomposition when individual items update (likes, reposts, bookmarks, replies, tab toggles).
 */
@Immutable
sealed interface ThreadListItem {
    val stableKey: String
    val contentType: ThreadItemContentType

    @Immutable
    data class Ancestor(
        val tweet: Tweet,
        val connectFromAbove: Boolean,
        val connectToBelow: Boolean,
        val isFocalAuthor: Boolean,
    ) : ThreadListItem {
        override val stableKey: String = "anc_${tweet.id}"
        override val contentType: ThreadItemContentType = ThreadItemContentType.ANCESTOR
    }

    @Immutable
    data class CollapsedAncestorsToggle(
        val hiddenCount: Int,
    ) : ThreadListItem {
        override val stableKey: String = "anc_expand_toggle"
        override val contentType: ThreadItemContentType = ThreadItemContentType.ANCESTOR_TOGGLE
    }

    @Immutable
    data class Focal(
        val tweet: Tweet,
        val hasAncestorAbove: Boolean,
        val parentTweet: Tweet? = null,
    ) : ThreadListItem {
        override val stableKey: String = "focal_${tweet.id}"
        override val contentType: ThreadItemContentType = ThreadItemContentType.FOCAL
    }

    @Immutable
    data class TabSwitcher(
        val selectedTab: ThreadTab,
        val repliesCount: Int,
        val quotesCount: Long,
    ) : ThreadListItem {
        override val stableKey: String = "thread_tab_switcher"
        override val contentType: ThreadItemContentType = ThreadItemContentType.TAB_SWITCHER
    }

    @Immutable
    data class Reply(
        val tweet: Tweet,
        val connectFromAbove: Boolean,
        val connectToBelow: Boolean,
        val isFocalAuthor: Boolean,
    ) : ThreadListItem {
        override val stableKey: String = "rep_${tweet.id}"
        override val contentType: ThreadItemContentType = ThreadItemContentType.REPLY
    }

    @Immutable
    data class Quote(
        val tweet: Tweet,
    ) : ThreadListItem {
        override val stableKey: String = "qt_${tweet.id}"
        override val contentType: ThreadItemContentType = ThreadItemContentType.QUOTE
    }

    @Immutable
    data class LoadMoreQuotes(
        val isLoadingMore: Boolean,
    ) : ThreadListItem {
        override val stableKey: String = "load_more_quotes"
        override val contentType: ThreadItemContentType = ThreadItemContentType.LOAD_MORE
    }

    @Immutable
    data class Loading(
        val id: String,
    ) : ThreadListItem {
        override val stableKey: String = "loading_$id"
        override val contentType: ThreadItemContentType = ThreadItemContentType.LOADING
    }

    @Immutable
    data class EmptyMessage(
        val id: String,
        val title: String,
        val body: String,
    ) : ThreadListItem {
        override val stableKey: String = "empty_$id"
        override val contentType: ThreadItemContentType = ThreadItemContentType.EMPTY_MESSAGE
    }
}

/**
 * Stable callback bundle for [ThreadedTweetView] to avoid per-item lambda churn.
 */
@Immutable
data class ThreadedTweetActions(
    val onSelectTab: (ThreadTab) -> Unit,
    val onLoadMoreQuotes: () -> Unit,
    val onOpenThread: (Tweet) -> Unit,
    val onOpenProfile: (String) -> Unit,
    val onLike: (Tweet) -> Unit,
    val onRepost: (Tweet) -> Unit,
    val onQuote: (Tweet) -> Unit,
    val onReply: (Tweet) -> Unit,
    val onBookmark: (Tweet) -> Unit,
    val onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
)

private const val MAX_UNCOLLAPSED_ANCESTORS = 4

/**
 * Pure, deterministic flattener that converts a [ThreadConversation] plus tab/quote state
 * into a flat list of uniquely keyed [ThreadListItem]s for a single [LazyColumn].
 */
internal fun buildThreadListItems(
    conversation: ThreadConversation,
    selectedTab: ThreadTab,
    quotes: List<Tweet>,
    quotesCursor: String?,
    isLoadingReplies: Boolean = false,
    isLoadingQuotes: Boolean,
    isLoadingMoreQuotes: Boolean,
    quotesError: String?,
    ancestorsExpanded: Boolean,
): List<ThreadListItem> {
    val items = ArrayList<ThreadListItem>(conversation.ancestors.size + conversation.replies.size + 6)
    val focal = conversation.focalTweet
    val focalHandle = focal.author.handle

    // Deduplicate ancestors and never duplicate focal tweet ID
    val uniqueAncestors = conversation.ancestors
        .distinctBy { it.id }
        .filterNot { it.id == focal.id }

    if (uniqueAncestors.size > MAX_UNCOLLAPSED_ANCESTORS && !ancestorsExpanded) {
        val root = uniqueAncestors.first()
        val tail = uniqueAncestors.takeLast(2)
        val hiddenCount = (uniqueAncestors.size - 1 - tail.size).coerceAtLeast(1)

        items.add(
            ThreadListItem.Ancestor(
                tweet = root,
                connectFromAbove = false,
                connectToBelow = true,
                isFocalAuthor = root.author.handle.equals(focalHandle, ignoreCase = true),
            )
        )
        items.add(ThreadListItem.CollapsedAncestorsToggle(hiddenCount = hiddenCount))
        tail.forEach { anc ->
            items.add(
                ThreadListItem.Ancestor(
                    tweet = anc,
                    connectFromAbove = true,
                    connectToBelow = true,
                    isFocalAuthor = anc.author.handle.equals(focalHandle, ignoreCase = true),
                )
            )
        }
    } else {
        uniqueAncestors.forEachIndexed { idx, anc ->
            items.add(
                ThreadListItem.Ancestor(
                    tweet = anc,
                    connectFromAbove = idx > 0,
                    connectToBelow = true,
                    isFocalAuthor = anc.author.handle.equals(focalHandle, ignoreCase = true),
                )
            )
        }
    }

    items.add(
        ThreadListItem.Focal(
            tweet = focal,
            hasAncestorAbove = uniqueAncestors.isNotEmpty(),
            parentTweet = uniqueAncestors.lastOrNull(),
        )
    )

    val uniqueReplies = conversation.replies
        .distinctBy { it.id }
        .filterNot { it.id == focal.id }

    val quotesCount = focal.metrics.quotes.coerceAtLeast(quotes.size.toLong())
    items.add(
        ThreadListItem.TabSwitcher(
            selectedTab = selectedTab,
            repliesCount = uniqueReplies.size,
            quotesCount = quotesCount,
        )
    )

    if (selectedTab == ThreadTab.REPLIES) {
        if (uniqueReplies.isEmpty() && isLoadingReplies) {
            items.add(ThreadListItem.Loading(id = "replies_loading"))
        } else if (uniqueReplies.isEmpty()) {
            items.add(
                ThreadListItem.EmptyMessage(
                    id = "no_replies",
                    title = "No replies yet",
                    body = "Be the first to reply below.",
                )
            )
        } else {
            for (i in uniqueReplies.indices) {
                val reply = uniqueReplies[i]
                val prev = uniqueReplies.getOrNull(i - 1)
                val next = uniqueReplies.getOrNull(i + 1)

                val connectedAbove = prev != null && (
                    reply.replyToTweetId == prev.id ||
                        (reply.author.handle.equals(prev.author.handle, ignoreCase = true) &&
                            reply.author.handle.equals(focalHandle, ignoreCase = true))
                    )
                val connectedBelow = next != null && (
                    next.replyToTweetId == reply.id ||
                        (next.author.handle.equals(reply.author.handle, ignoreCase = true) &&
                            reply.author.handle.equals(focalHandle, ignoreCase = true))
                    )

                items.add(
                    ThreadListItem.Reply(
                        tweet = reply,
                        connectFromAbove = connectedAbove,
                        connectToBelow = connectedBelow,
                        isFocalAuthor = reply.author.handle.equals(focalHandle, ignoreCase = true),
                    )
                )
            }
        }
    } else {
        val uniqueQuotes = quotes.distinctBy { it.id }
        when {
            isLoadingQuotes -> items.add(ThreadListItem.Loading(id = "quotes_loading"))
            uniqueQuotes.isEmpty() -> items.add(
                ThreadListItem.EmptyMessage(
                    id = "no_quotes",
                    title = "No quote posts found",
                    body = quotesError ?: "Nobody has quoted this post yet.",
                )
            )
            else -> {
                uniqueQuotes.forEach { quoteTweet ->
                    items.add(ThreadListItem.Quote(tweet = quoteTweet))
                }
                if (quotesCursor != null) {
                    items.add(ThreadListItem.LoadMoreQuotes(isLoadingMore = isLoadingMoreQuotes))
                }
            }
        }
    }

    return items
}

/**
 * Specialized single-LazyColumn thread renderer with:
 * - Deterministic key-based item identity + explicit [ThreadItemContentType] slot reuse
 * - Flattened @Immutable item list & @Immutable action bundle to eliminate unnecessary recompositions
 * - Continuous Canvas-drawn ancestor & reply-chain connector rails (no nested scrollable containers)
 * - Automatic ancestor folding for deep chains (> 4 ancestors) and viewport focus on the focal tweet
 * - Proactive Coil media prefetching as the user scrolls through long threads
 * - Integrated Material 3 dynamically fading scroll indicator
 */
@Composable
fun ThreadedTweetView(
    conversation: ThreadConversation,
    selectedTab: ThreadTab,
    quotes: List<Tweet>,
    quotesCursor: String?,
    isLoadingQuotes: Boolean,
    isLoadingMoreQuotes: Boolean,
    quotesError: String?,
    preferences: AppPreferences,
    actions: ThreadedTweetActions,
    modifier: Modifier = Modifier,
    isLoadingReplies: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    var ancestorsExpanded by rememberSaveable(conversation.focalTweet.id) { mutableStateOf(false) }

    val threadItems = remember(
        conversation,
        selectedTab,
        quotes,
        quotesCursor,
        isLoadingReplies,
        isLoadingQuotes,
        isLoadingMoreQuotes,
        quotesError,
        ancestorsExpanded,
    ) {
        buildThreadListItems(
            conversation = conversation,
            selectedTab = selectedTab,
            quotes = quotes,
            quotesCursor = quotesCursor,
            isLoadingReplies = isLoadingReplies,
            isLoadingQuotes = isLoadingQuotes,
            isLoadingMoreQuotes = isLoadingMoreQuotes,
            quotesError = quotesError,
            ancestorsExpanded = ancestorsExpanded,
        )
    }

    // Position the viewport on the immediate parent + focal tweet when opening a deep ancestor chain
    LaunchedEffect(conversation.focalTweet.id) {
        val focalIndex = threadItems.indexOfFirst { it is ThreadListItem.Focal }
        if (focalIndex >= 2) {
            listState.scrollToItem(index = (focalIndex - 1).coerceAtLeast(0))
        }
    }

    // Extract flat tweet sequence for proactive Coil disk/memory prefetching while scrolling long threads
    val prefetchTweets = remember(threadItems) {
        threadItems.mapNotNull { item ->
            when (item) {
                is ThreadListItem.Ancestor -> item.tweet
                is ThreadListItem.Focal -> item.tweet
                is ThreadListItem.Reply -> item.tweet
                is ThreadListItem.Quote -> item.tweet
                else -> null
            }
        }
    }

    LaunchedEffect(listState, prefetchTweets) {
        if (prefetchTweets.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { visibleIdx ->
                IlayImageCache.prefetchTimelineMedia(
                    context = context,
                    tweets = prefetchTweets,
                    startIndex = visibleIdx.coerceIn(0, prefetchTweets.lastIndex),
                    windowSize = 8,
                )
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 640.dp)
            .ilayVerticalScrollIndicator(
                state = listState,
                bottomPadding = 20.dp,
            )
            .testTag("threaded_tweet_view"),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(
            items = threadItems,
            key = { it.stableKey },
            contentType = { it.contentType },
        ) { item ->
            Box(modifier = Modifier.animateItem()) {
            when (item) {
                is ThreadListItem.Ancestor -> {
                    AncestorThreadRow(
                        tweet = item.tweet,
                        connectFromAbove = item.connectFromAbove,
                        connectToBelow = item.connectToBelow,
                        isFocalAuthor = item.isFocalAuthor,
                        blurSensitiveMedia = preferences.blurSensitiveMedia,
                        onOpenThread = actions.onOpenThread,
                        onOpenProfile = actions.onOpenProfile,
                        onOpenMedia = actions.onOpenMedia,
                    )
                }

                is ThreadListItem.CollapsedAncestorsToggle -> {
                    CollapsedAncestorsRow(
                        hiddenCount = item.hiddenCount,
                        onExpand = { ancestorsExpanded = true },
                    )
                }

                is ThreadListItem.Focal -> {
                    FocalTweetCard(
                        tweet = item.tweet,
                        hasAncestorAbove = item.hasAncestorAbove,
                        parentTweet = item.parentTweet,
                        actions = actions,
                    )
                }

                is ThreadListItem.TabSwitcher -> {
                    ThreadTabSwitcherRow(
                        selectedTab = item.selectedTab,
                        repliesCount = item.repliesCount,
                        quotesCount = item.quotesCount,
                        onSelectTab = actions.onSelectTab,
                    )
                }

                is ThreadListItem.Reply -> {
                    ThreadedReplyRow(
                        item = item,
                        preferences = preferences,
                        actions = actions,
                    )
                }

                is ThreadListItem.Quote -> {
                    SwipeableTweetCard(
                        tweet = item.tweet,
                        density = FeedDensity.CARD,
                        swipeRightAction = preferences.swipeRightAction,
                        swipeLeftAction = preferences.swipeLeftAction,
                        showRankBadge = false,
                        blurSensitiveMedia = preferences.blurSensitiveMedia,
                        onOpenThread = actions.onOpenThread,
                        onOpenProfile = actions.onOpenProfile,
                        onLike = actions.onLike,
                        onRepost = actions.onRepost,
                        onQuote = actions.onQuote,
                        onReply = actions.onReply,
                        onBookmark = actions.onBookmark,
                        onOpenMedia = actions.onOpenMedia,
                    )
                }

                is ThreadListItem.LoadMoreQuotes -> {
                    LoadMoreQuotesRow(
                        isLoadingMore = item.isLoadingMore,
                        onLoadMore = actions.onLoadMoreQuotes,
                    )
                }

                is ThreadListItem.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IlayLoadingIndicator(size = 34.dp)
                        if (item.id == "replies_loading") {
                            Text(
                                text = stringResource(R.string.thread_loading_replies),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                is ThreadListItem.EmptyMessage -> {
                    ThreadEmptyStateRow(
                        title = item.title,
                        body = item.body,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun AncestorThreadRow(
    tweet: Tweet,
    connectFromAbove: Boolean,
    connectToBelow: Boolean,
    isFocalAuthor: Boolean,
    blurSensitiveMedia: Boolean,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
) {
    val context = LocalContext.current
    val host = LocalTweetCardHost.current
    val entityAccent = MaterialTheme.colorScheme.primary
    val spineColor = MaterialTheme.colorScheme.outlineVariant
    val ancestorTime = remember(tweet.createdAtEpochMs, context) {
        FormatUtils.formatDeviceTime(context, tweet.createdAtEpochMs)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenThread(tweet) }
            .drawBehind {
                // Continuous vertical spine aligned to the exact center of the 38.dp avatar
                val spineX = 16.dp.toPx() + 19.dp.toPx()
                val strokePx = 2.dp.toPx()
                val avatarTopPx = 8.dp.toPx()
                val avatarBottomPx = avatarTopPx + 38.dp.toPx()
                if (connectFromAbove) {
                    drawLine(
                        color = spineColor,
                        start = Offset(spineX, 0f),
                        end = Offset(spineX, (avatarTopPx - 4.dp.toPx()).coerceAtLeast(0f)),
                        strokeWidth = strokePx,
                        cap = StrokeCap.Round,
                    )
                }
                if (connectToBelow) {
                    val startY = (avatarBottomPx + 4.dp.toPx()).coerceAtMost(size.height)
                    if (startY < size.height) {
                        drawLine(
                            color = spineColor,
                            start = Offset(spineX, startY),
                            end = Offset(spineX, size.height),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
            .testTag("thread_ancestor_${tweet.id}"),
    ) {
        UserAvatar(
            url = tweet.author.avatarUrl,
            displayName = tweet.author.displayName,
            size = 38.dp,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onOpenProfile(tweet.author.handle) },
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = tweet.author.displayName.ifBlank { "@${tweet.author.handle}" },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tweet.author.isVerified) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = "Verified account",
                        tint = VerifiedBlue,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (isFocalAuthor) {
                    AuthorBadgePill()
                }
                Text(
                    text = "@${tweet.author.handle}" + if (ancestorTime.isNotBlank()) " · $ancestorTime" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tweet.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                IlayLinkMovementText(
                    text = tweet.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    accentColor = entityAccent,
                    onMentionClick = onOpenProfile,
                    onHashtagClick = host.openHashtag,
                    onUrlClick = host.openUrl,
                    modifier = Modifier.testTag("thread_ancestor_body_${tweet.id}"),
                )
            }
            if (tweet.media.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TweetMediaGrid(
                    media = tweet.media,
                    blurred = blurSensitiveMedia && tweet.isSensitive,
                    onMediaClick = { idx -> onOpenMedia(tweet.media, idx) },
                )
            }
        }
    }
}

@Composable
private fun CollapsedAncestorsRow(
    hiddenCount: Int,
    onExpand: () -> Unit,
) {
    val spineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onExpand)
            .drawBehind {
                val spineX = 16.dp.toPx() + 19.dp.toPx()
                drawLine(
                    color = spineColor,
                    start = Offset(spineX, 0f),
                    end = Offset(spineX, size.height),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                    cap = StrokeCap.Round,
                )
            }
            .padding(start = 66.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
            .testTag("thread_expand_ancestors_button"),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.UnfoldMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (hiddenCount == 1) "Show 1 earlier post" else "Show $hiddenCount earlier posts",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ThreadedReplyRow(
    item: ThreadListItem.Reply,
    preferences: AppPreferences,
    actions: ThreadedTweetActions,
) {
    val connectorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.connectFromAbove || item.connectToBelow) {
                    Modifier.drawBehind {
                        val railX = 22.dp.toPx()
                        val strokePx = 2.5.dp.toPx()
                        val startY = if (item.connectFromAbove) 0f else size.height * 0.5f
                        val endY = if (item.connectToBelow) size.height else size.height * 0.2f
                        drawLine(
                            color = connectorColor,
                            start = Offset(railX, startY),
                            end = Offset(railX, endY),
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round,
                        )
                    }
                } else {
                    Modifier
                }
            )
            .testTag("thread_reply_${item.tweet.id}"),
    ) {
        if (item.isFocalAuthor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 18.dp, top = 4.dp),
            ) {
                AuthorBadgePill(label = if (item.connectFromAbove) "Thread continuation" else "Author reply")
            }
        }
        SwipeableTweetCard(
            tweet = item.tweet,
            density = FeedDensity.CARD,
            swipeRightAction = preferences.swipeRightAction,
            swipeLeftAction = preferences.swipeLeftAction,
            showRankBadge = false,
            blurSensitiveMedia = preferences.blurSensitiveMedia,
            onOpenThread = actions.onOpenThread,
            onOpenProfile = actions.onOpenProfile,
            onLike = actions.onLike,
            onRepost = actions.onRepost,
            onQuote = actions.onQuote,
            onReply = actions.onReply,
            onBookmark = actions.onBookmark,
            onOpenMedia = actions.onOpenMedia,
        )
    }
}

@Composable
private fun AuthorBadgePill(label: String = "OP") {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ThreadTabSwitcherRow(
    selectedTab: ThreadTab,
    repliesCount: Int,
    quotesCount: Long,
    onSelectTab: (ThreadTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThreadTab.entries.forEach { tab ->
            val selected = selectedTab == tab
            val countLabel = when (tab) {
                ThreadTab.REPLIES -> "${tab.label} ($repliesCount)"
                ThreadTab.QUOTES -> {
                    if (quotesCount > 0L) "${tab.label} (${FormatUtils.compactCount(quotesCount)})" else tab.label
                }
            }
            IlayExpressiveTabPill(
                label = countLabel,
                selected = selected,
                onClick = { onSelectTab(tab) },
                leadingIcon = if (tab == ThreadTab.QUOTES) {
                    {
                        Icon(
                            Icons.Filled.FormatQuote,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else null,
                testTag = "thread_tab_${tab.name.lowercase()}",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LoadMoreQuotesRow(
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoadingMore) {
            IlayLoadingIndicator(size = 26.dp)
        } else {
            FilledTonalButton(
                onClick = onLoadMore,
                shape = CircleShape,
                modifier = Modifier.testTag("load_more_quotes_button"),
            ) {
                Text("Load more quotes")
            }
        }
    }
}

@Composable
private fun ThreadEmptyStateRow(
    title: String,
    body: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Forum,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FocalTweetCard(
    tweet: Tweet,
    hasAncestorAbove: Boolean,
    parentTweet: Tweet?,
    actions: ThreadedTweetActions,
) {
    val context = LocalContext.current
    val host = LocalTweetCardHost.current
    val scope = rememberCoroutineScope()

    var showShareSheet by remember { mutableStateOf(false) }
    var showRepostMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var isTranslating by remember(tweet.id) { mutableStateOf(false) }
    var translationResult by remember(tweet.id) { mutableStateOf<TranslationResult?>(null) }
    val canTranslate = remember(tweet.id, tweet.lang, tweet.text) { shouldOfferTranslation(tweet) }
    val entityAccent = MaterialTheme.colorScheme.primary
    val spineColor = MaterialTheme.colorScheme.outlineVariant
    val focalTimestamp = remember(tweet.createdAtEpochMs, context) {
        FormatUtils.formatFullDeviceTimestamp(context, tweet.createdAtEpochMs)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasAncestorAbove) {
                    Modifier.drawBehind {
                        val spineX = 16.dp.toPx() + 19.dp.toPx()
                        drawLine(
                            color = spineColor,
                            start = Offset(spineX, 0f),
                            end = Offset(spineX, 10.dp.toPx()),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("focal_tweet_card_${tweet.id}"),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { actions.onOpenProfile(tweet.author.handle) }
                            .padding(vertical = 2.dp),
                    ) {
                        UserAvatar(tweet.author.avatarUrl, tweet.author.displayName, size = 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = tweet.author.displayName.ifBlank { "@${tweet.author.handle}" },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (tweet.author.isVerified) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Filled.Verified,
                                        contentDescription = "Verified account",
                                        tint = VerifiedBlue,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Text(
                                text = "@${tweet.author.handle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.testTag("focal_more_btn_${tweet.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreHoriz,
                                contentDescription = "Post options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.quote_post_action)) },
                                leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    actions.onQuote(tweet)
                                },
                                modifier = Modifier.testTag("focal_more_quote_btn_${tweet.id}"),
                            )
                            DropdownMenuItem(
                                text = { Text("Share & Save options") },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    showShareSheet = true
                                },
                            )
                        }
                    }
                }

                if (!hasAncestorAbove && !tweet.replyToHandle.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    val cleanReplyHandle = remember(tweet.replyToHandle) { tweet.replyToHandle.removePrefix("@") }
                    IlayLinkMovementText(
                        text = "Replying to @$cleanReplyHandle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        accentColor = entityAccent,
                        onMentionClick = actions.onOpenProfile,
                        onHashtagClick = host.openHashtag,
                        onUrlClick = host.openUrl,
                    )
                }

                Spacer(Modifier.height(14.dp))
                IlayLinkMovementText(
                    text = tweet.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    accentColor = entityAccent,
                    onMentionClick = actions.onOpenProfile,
                    onHashtagClick = host.openHashtag,
                    onUrlClick = host.openUrl,
                    modifier = Modifier.testTag("focal_tweet_body_${tweet.id}"),
                )

                if (canTranslate) {
                    TweetTranslationBlock(
                        isTranslating = isTranslating,
                        result = translationResult,
                        accentColor = entityAccent,
                        onRequestTranslate = {
                            if (!isTranslating) {
                                scope.launch {
                                    isTranslating = true
                                    host.translateTweet(tweet)
                                        .onSuccess { res -> translationResult = res }
                                        .onFailure { err -> host.showMessage(err.message ?: "Translation unavailable") }
                                    isTranslating = false
                                }
                            }
                        },
                        onDismissTranslation = { translationResult = null },
                        testTag = "focal_translate_btn_${tweet.id}",
                    )
                }

                if (tweet.media.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    TweetMediaGrid(
                        media = tweet.media,
                        blurred = false,
                        onMediaClick = { idx -> actions.onOpenMedia(tweet.media, idx) },
                    )
                }

                tweet.poll?.let { poll ->
                    Spacer(Modifier.height(12.dp))
                    PollCard(tweet = tweet, poll = poll)
                }

                val hasFocalArticle = remember(tweet.id, tweet.article, tweet.linkPreview) { tweet.hasReadableArticle() }
                if (hasFocalArticle) {
                    val resolvedArticle = remember(tweet.id, tweet.article, tweet.linkPreview) {
                        tweet.resolveArticleOrFallback()
                    }
                    Spacer(Modifier.height(12.dp))
                    EmbeddedArticlePreviewCard(
                        article = resolvedArticle,
                        compact = false,
                        onClick = { host.openArticle(tweet, resolvedArticle) },
                    )
                }

                tweet.linkPreview?.takeUnless {
                    hasFocalArticle && it.url.contains("/article/", ignoreCase = true)
                }?.let { linkPreview ->
                    Spacer(Modifier.height(12.dp))
                    LinkPreviewCard(
                        preview = linkPreview,
                        compact = false,
                        showImage = tweet.media.isEmpty(),
                    )
                }

                tweet.quotedTweet?.let { qt ->
                    Spacer(Modifier.height(12.dp))
                    EmbeddedQuotePostCard(
                        quoted = qt,
                        compact = false,
                        blurSensitiveMedia = false,
                        onOpenParent = actions.onOpenThread,
                        onOpenProfile = actions.onOpenProfile,
                        onOpenMedia = actions.onOpenMedia,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = buildString {
                        append(focalTimestamp)
                        if (tweet.sourceLabel.isNotBlank()) append(" · ${tweet.sourceLabel}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThreadStatChip(FormatUtils.compactCount(tweet.metrics.reposts).ifBlank { "0" }, "Reposts")
                    ThreadStatChip(
                        value = FormatUtils.compactCount(tweet.metrics.quotes).ifBlank { "0" },
                        label = "Quotes",
                        onClick = { actions.onSelectTab(ThreadTab.QUOTES) },
                        modifier = Modifier.testTag("focal_quotes_chip"),
                    )
                    ThreadStatChip(FormatUtils.compactCount(tweet.metrics.likes).ifBlank { "0" }, "Likes")
                    if (tweet.metrics.bookmarks > 0) {
                        ThreadStatChip(FormatUtils.compactCount(tweet.metrics.bookmarks), "Saves")
                    } else if (tweet.metrics.views > 0) {
                        ThreadStatChip(FormatUtils.compactCount(tweet.metrics.views), "Views")
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    IconButton(
                        onClick = { actions.onReply(tweet) },
                        modifier = Modifier.testTag("focal_reply_btn_${tweet.id}"),
                    ) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            contentDescription = "Reply",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showRepostMenu = true },
                            modifier = Modifier.testTag("focal_repost_btn_${tweet.id}"),
                        ) {
                            Icon(
                                if (tweet.viewerState.isReposted) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                                contentDescription = "Repost or Quote",
                                tint = if (tweet.viewerState.isReposted) RepostEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showRepostMenu,
                            onDismissRequest = { showRepostMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (tweet.viewerState.isReposted) "Undo Repost" else "Repost") },
                                leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null, tint = RepostEmerald) },
                                onClick = {
                                    showRepostMenu = false
                                    actions.onRepost(tweet)
                                },
                                modifier = Modifier.testTag("focal_repost_menu_repost_btn_${tweet.id}"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.quote_post_action)) },
                                leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                                onClick = {
                                    showRepostMenu = false
                                    actions.onQuote(tweet)
                                },
                                modifier = Modifier.testTag("focal_repost_menu_quote_btn_${tweet.id}"),
                            )
                        }
                    }
                    IconButton(
                        onClick = { actions.onLike(tweet) },
                        modifier = Modifier.testTag("focal_like_btn_${tweet.id}"),
                    ) {
                        Icon(
                            if (tweet.viewerState.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (tweet.viewerState.isLiked) LikeRose else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { actions.onBookmark(tweet) },
                        modifier = Modifier.testTag("focal_bookmark_btn_${tweet.id}"),
                    ) {
                        Icon(
                            if (tweet.viewerState.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Bookmark",
                            tint = if (tweet.viewerState.isBookmarked) BookmarkAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { showShareSheet = true },
                        modifier = Modifier.testTag("focal_share_btn_${tweet.id}"),
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = "Share or save post",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showShareSheet) {
        TweetShareActionSheet(
            tweet = tweet,
            onCaptureScreenshot = {
                showShareSheet = false
                host.capturePostScreenshot(tweet, parentTweet, ScreenshotCaptureAction.OPEN_MARKUP_EDITOR)
            },
            onShareScreenshotImage = {
                showShareSheet = false
                host.capturePostScreenshot(tweet, parentTweet, ScreenshotCaptureAction.SHARE_IMAGE)
            },
            onDownloadMedia = {
                showShareSheet = false
                scope.launch {
                    host.showMessage("Downloading media…")
                    host.downloadMedia(tweet)
                        .onSuccess { count ->
                            host.showMessage(if (count == 1) "Media saved to Gallery" else "$count media items saved to Gallery")
                        }
                        .onFailure { err -> host.showMessage(err.message ?: "Failed to download media") }
                }
            },
            onShareLink = {
                showShareSheet = false
                shareTweetLink(context, tweet)
            },
            onDismiss = { showShareSheet = false },
        )
    }
}

@Composable
private fun ThreadStatChip(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    } else {
        modifier
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier,
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
