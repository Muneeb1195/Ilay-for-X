package com.example.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.remote.IlayImageCache
import com.example.data.remote.LocalImageQualityMode
import com.example.data.remote.TranslationResult
import com.example.domain.model.FeedDensity
import com.example.domain.model.LinkPreview
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.SwipeAction
import com.example.domain.model.Tweet
import com.example.ui.theme.BookmarkAmber
import com.example.ui.theme.IlayMotion
import com.example.ui.theme.LikeRose
import com.example.ui.theme.RepostEmerald
import com.example.ui.theme.VerifiedBlue
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared host callbacks provided at app root so every TweetCard, EmbeddedQuotePostCard,
 * FocalTweetCard, and ProfileScreen supports clickable entities, native translation,
 * full-card reply-aware screenshot capture, gallery downloads, link previews, and snackbars.
 */
data class TweetCardHost(
    val translateTweet: suspend (Tweet) -> Result<TranslationResult> = {
        Result.failure(IllegalStateException("Translation unavailable"))
    },
    val downloadMedia: suspend (Tweet) -> Result<Int> = {
        Result.failure(IllegalStateException("Media download unavailable"))
    },
    val resolveLinkPreview: suspend (LinkPreview) -> LinkPreview = { it },
    val votePoll: suspend (Tweet, Int) -> Result<Tweet> = { _, _ ->
        Result.failure(IllegalStateException("Poll voting unavailable"))
    },
    val openProfile: (String) -> Unit = {},
    val openHashtag: (String) -> Unit = {},
    val openUrl: (String) -> Unit = {},
    val capturePostScreenshot: (tweet: Tweet, knownParent: Tweet?, action: ScreenshotCaptureAction) -> Unit = { _, _, _ -> },
    val showMessage: (String) -> Unit = {},
)

val LocalTweetCardHost = compositionLocalOf { TweetCardHost() }

private const val COLLAPSE_CHAR_THRESHOLD = 280
private val NON_TRANSLATABLE_LANGS = setOf("und", "zxx", "qme", "qht", "qam", "art")

/**
 * Determines whether X's "Translate post" action should be displayed for a tweet
 * based on the device's current system language.
 */
fun shouldOfferTranslation(tweet: Tweet): Boolean {
    val cleanText = tweet.text.trim()
    if (cleanText.length < 3) return false
    val systemLang = normalizeLangCode(Locale.getDefault().language)
    val tweetLang = normalizeLangCode(tweet.lang)

    if (tweetLang.isNotBlank()) {
        if (tweetLang in NON_TRANSLATABLE_LANGS) return false
        return tweetLang != systemLang
    }

    // Heuristic fallback when public syndication omits lang metadata: detect non-Latin scripts on English devices
    if (systemLang == "en") {
        val nonLatinCount = cleanText.count { ch ->
            ch.code > 0x024F && Character.isLetter(ch)
        }
        return nonLatinCount >= 3
    }
    return false
}

private fun normalizeLangCode(raw: String): String {
    val base = raw.trim().lowercase().substringBefore("-").substringBefore("_")
    return when (base) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> base
    }
}

/**
 * Deliberate horizontal swipe wrapper for tweet cards.
 * - Enforces a strict horizontal intent lock (26dp dead-zone and >= 2.4x horizontal-to-vertical ratio)
 *   so vertical feed scrolling never accidentally triggers swipe actions.
 * - Applies damped pull resistance (0.58x) and requires dragging past a firm distance threshold
 *   (>= 116dp or 34% of card width) with no accidental velocity-flick triggers.
 * - Respects inner horizontal gestures (such as multi-photo carousels) by ignoring consumed events.
 */
@Composable
fun SwipeableTweetCard(
    tweet: Tweet,
    density: FeedDensity,
    swipeRightAction: SwipeAction,
    swipeLeftAction: SwipeAction,
    showRankBadge: Boolean,
    blurSensitiveMedia: Boolean,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
    modifier: Modifier = Modifier,
    onVotePoll: (Tweet, Int) -> Unit = { _, _ -> },
    onDelete: ((Tweet) -> Unit)? = null,
    onNotInterested: ((Tweet) -> Unit)? = null,
    onShowLessFromAuthor: ((Tweet) -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val densityLocal = LocalDensity.current
    val scope = rememberCoroutineScope()

    val canSwipeRight = swipeRightAction != SwipeAction.NONE
    val canSwipeLeft = swipeLeftAction != SwipeAction.NONE

    if (!canSwipeRight && !canSwipeLeft) {
        TweetCardContent(
            tweet = tweet,
            density = density,
            showRankBadge = showRankBadge,
            blurSensitiveMedia = blurSensitiveMedia,
            onOpenThread = onOpenThread,
            onOpenProfile = onOpenProfile,
            onLike = onLike,
            onRepost = onRepost,
            onQuote = onQuote,
            onReply = onReply,
            onBookmark = onBookmark,
            onOpenMedia = onOpenMedia,
            modifier = modifier,
            onVotePoll = onVotePoll,
            onDelete = onDelete,
            onNotInterested = onNotInterested,
            onShowLessFromAuthor = onShowLessFromAuthor,
        )
        return
    }

    val lockHorizontalSlopPx = with(densityLocal) { 26.dp.toPx() }
    val verticalBailSlopPx = with(densityLocal) { 10.dp.toPx() }
    val minTriggerPx = with(densityLocal) { 116.dp.toPx() }
    val maxDragPx = with(densityLocal) { 156.dp.toPx() }

    val offsetX = remember(tweet.id) { Animatable(0f) }
    var cardWidthPx by remember { mutableFloatStateOf(0f) }

    val triggerDistancePx = max(minTriggerPx, cardWidthPx * 0.34f).coerceAtMost(maxDragPx - 8f)
    val currentOffset = offsetX.value
    val activeAction = when {
        currentOffset > 4f && canSwipeRight -> swipeRightAction
        currentOffset < -4f && canSwipeLeft -> swipeLeftAction
        else -> SwipeAction.NONE
    }
    val pullFraction = (abs(currentOffset) / triggerDistancePx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val isArmed = abs(currentOffset) >= triggerDistancePx && activeAction != SwipeAction.NONE

    val actionTint = when (activeAction) {
        SwipeAction.LIKE -> LikeRose
        SwipeAction.REPOST -> RepostEmerald
        SwipeAction.BOOKMARK -> BookmarkAmber
        SwipeAction.REPLY -> MaterialTheme.colorScheme.primary
        SwipeAction.NONE -> Color.Transparent
    }
    val actionIcon = when (activeAction) {
        SwipeAction.LIKE -> Icons.Filled.Favorite
        SwipeAction.REPOST -> Icons.Filled.Repeat
        SwipeAction.BOOKMARK -> Icons.Filled.Bookmark
        SwipeAction.REPLY -> Icons.AutoMirrored.Outlined.Reply
        SwipeAction.NONE -> Icons.Outlined.FavoriteBorder
    }
    val bgColor by animateColorAsState(
        targetValue = when {
            activeAction == SwipeAction.NONE -> Color.Transparent
            isArmed -> actionTint.copy(alpha = 0.30f)
            else -> actionTint.copy(alpha = 0.08f + pullFraction * 0.12f)
        },
        animationSpec = IlayMotion.effectsDefault(),
        label = "swipeBg",
    )
    val badgeScale by animateFloatAsState(
        targetValue = if (isArmed) 1.12f else 0.88f + (pullFraction * 0.14f),
        animationSpec = IlayMotion.spatialBouncy(),
        label = "swipeBadgeScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { cardWidthPx = it.width.toFloat() }
            .pointerInput(tweet.id, canSwipeRight, canSwipeLeft, triggerDistancePx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDx = 0f
                    var totalDy = 0f
                    var lockedHorizontal = false
                    var lastArmed: Int? = null
                    val pointerId = down.id

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) {
                            // Pointer lifted: trigger only if firmly held past triggerDistancePx
                            val finalOffset = offsetX.value
                            val firedDir = when {
                                lockedHorizontal && finalOffset >= triggerDistancePx && canSwipeRight -> 1
                                lockedHorizontal && finalOffset <= -triggerDistancePx && canSwipeLeft -> -1
                                else -> 0
                            }
                            if (firedDir == 1) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dispatchSwipeAction(swipeRightAction, tweet, onLike, onRepost, onBookmark, onReply)
                            } else if (firedDir == -1) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dispatchSwipeAction(swipeLeftAction, tweet, onLike, onRepost, onBookmark, onReply)
                            }
                            if (offsetX.value != 0f) {
                                scope.launch {
                                    offsetX.animateTo(0f, animationSpec = IlayMotion.spatialDefault())
                                }
                            }
                            break
                        }

                        // Respect child horizontal gestures (e.g., inline multi-image carousel)
                        if (change.isConsumed && !lockedHorizontal) {
                            break
                        }

                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y

                        if (!lockedHorizontal) {
                            // Yield immediately to vertical scrolling
                            if (abs(totalDy) > verticalBailSlopPx && abs(totalDy) > abs(totalDx) * 0.55f) {
                                break
                            }
                            // Require firm, strongly horizontal drag past 26dp dead-zone before engaging
                            val wantsRight = totalDx > 0f && canSwipeRight
                            val wantsLeft = totalDx < 0f && canSwipeLeft
                            if ((wantsRight || wantsLeft) &&
                                abs(totalDx) >= lockHorizontalSlopPx &&
                                abs(totalDx) >= abs(totalDy) * 2.4f
                            ) {
                                lockedHorizontal = true
                                change.consume()
                            }
                        } else {
                            change.consume()
                            val rawTravel = if (totalDx > 0f) {
                                (totalDx - lockHorizontalSlopPx).coerceAtLeast(0f)
                            } else {
                                (totalDx + lockHorizontalSlopPx).coerceAtMost(0f)
                            }
                            val damped = (rawTravel * 0.58f).coerceIn(
                                if (canSwipeLeft) -maxDragPx else 0f,
                                if (canSwipeRight) maxDragPx else 0f,
                            )
                            scope.launch { offsetX.snapTo(damped) }

                            val nowArmed = when {
                                damped >= triggerDistancePx && canSwipeRight -> 1
                                damped <= -triggerDistancePx && canSwipeLeft -> -1
                                else -> null
                            }
                            if (nowArmed != lastArmed) {
                                if (nowArmed != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                lastArmed = nowArmed
                            }
                        }
                    }

                    // Safety reset if gesture was cancelled mid-drag
                    if (offsetX.value != 0f && !offsetX.isRunning) {
                        scope.launch {
                            offsetX.animateTo(0f, animationSpec = IlayMotion.spatialDefault())
                        }
                    }
                }
            },
    ) {
        if (abs(currentOffset) > 2f && activeAction != SwipeAction.NONE) {
            val alignment = if (currentOffset < 0f) Alignment.CenterEnd else Alignment.CenterStart
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = if (density == FeedDensity.COMPACT) 10.dp else 12.dp, vertical = if (density == FeedDensity.COMPACT) 3.dp else 6.dp)
                    .clip(RoundedCornerShape(if (density == FeedDensity.COMPACT) 16.dp else 22.dp))
                    .background(bgColor)
                    .padding(horizontal = 22.dp),
                contentAlignment = alignment,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.scale(badgeScale),
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = activeAction.label,
                        tint = if (isArmed) actionTint else actionTint.copy(alpha = 0.72f),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isArmed) activeAction.label else "Pull · ${activeAction.label}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isArmed) FontWeight.Bold else FontWeight.Medium,
                        color = if (isArmed) actionTint else actionTint.copy(alpha = 0.78f),
                    )
                }
            }
        }

        TweetCardContent(
            tweet = tweet,
            density = density,
            showRankBadge = showRankBadge,
            blurSensitiveMedia = blurSensitiveMedia,
            onOpenThread = onOpenThread,
            onOpenProfile = onOpenProfile,
            onLike = onLike,
            onRepost = onRepost,
            onQuote = onQuote,
            onReply = onReply,
            onBookmark = onBookmark,
            onOpenMedia = onOpenMedia,
            modifier = Modifier.offset { IntOffset(x = offsetX.value.roundToInt(), y = 0) },
            onVotePoll = onVotePoll,
            onDelete = onDelete,
            onNotInterested = onNotInterested,
            onShowLessFromAuthor = onShowLessFromAuthor,
        )
    }
}

private fun dispatchSwipeAction(
    action: SwipeAction,
    tweet: Tweet,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
) {
    when (action) {
        SwipeAction.LIKE -> onLike(tweet)
        SwipeAction.REPOST -> onRepost(tweet)
        SwipeAction.BOOKMARK -> onBookmark(tweet)
        SwipeAction.REPLY -> onReply(tweet)
        SwipeAction.NONE -> Unit
    }
}

@Composable
fun TweetCardContent(
    tweet: Tweet,
    density: FeedDensity,
    showRankBadge: Boolean,
    blurSensitiveMedia: Boolean,
    onOpenThread: (Tweet) -> Unit,
    onOpenProfile: (String) -> Unit,
    onLike: (Tweet) -> Unit,
    onRepost: (Tweet) -> Unit,
    onQuote: (Tweet) -> Unit,
    onReply: (Tweet) -> Unit,
    onBookmark: (Tweet) -> Unit,
    onOpenMedia: (List<MediaAttachment>, Int) -> Unit,
    modifier: Modifier = Modifier,
    onVotePoll: (Tweet, Int) -> Unit = { _, _ -> },
    onDelete: ((Tweet) -> Unit)? = null,
    onNotInterested: ((Tweet) -> Unit)? = null,
    onShowLessFromAuthor: ((Tweet) -> Unit)? = null,
) {
    val context = LocalContext.current
    val host = LocalTweetCardHost.current
    val scope = rememberCoroutineScope()

    val isCompact = density == FeedDensity.COMPACT
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRepostMenu by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showArticleReader by remember(tweet.id) { mutableStateOf(false) }
    var revealSensitive by remember(tweet.id) { mutableStateOf(false) }
    var isTextExpanded by remember(tweet.id) { mutableStateOf(false) }

    // Inline translation state
    var isTranslating by remember(tweet.id) { mutableStateOf(false) }
    var translationResult by remember(tweet.id) { mutableStateOf<TranslationResult?>(null) }

    val cardHorizontalPadding = if (isCompact) 10.dp else 12.dp
    val cardVerticalPadding = if (isCompact) 3.dp else 6.dp
    val contentHorizontalPadding = if (isCompact) 12.dp else 16.dp
    val cardCorner = if (isCompact) 16.dp else 22.dp

    val displayHandle = tweet.author.handle.ifBlank { "x_user" }
    val displayName = tweet.author.displayName.ifBlank { "@$displayHandle" }

    val isLongTweet = remember(tweet.id, tweet.text) { tweet.text.length > COLLAPSE_CHAR_THRESHOLD }
    val canTranslate = remember(tweet.id, tweet.lang, tweet.text) { shouldOfferTranslation(tweet) }
    val formattedTimestamp = remember(tweet.id, tweet.createdAtEpochMs, context) {
        val t = FormatUtils.formatDeviceTime(context, tweet.createdAtEpochMs)
        if (t.isNotBlank()) "· $t" else ""
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = cardHorizontalPadding, vertical = cardVerticalPadding)
            .clip(RoundedCornerShape(cardCorner))
            .clickable { onOpenThread(tweet) }
            .testTag("tweet_card_${tweet.id}"),
        shape = RoundedCornerShape(cardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            // Top padded block: header + body text + translation
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = contentHorizontalPadding,
                        end = contentHorizontalPadding,
                        top = if (isCompact) 12.dp else 15.dp,
                    )
            ) {
                // Optional Reposted / Optional Rank Badge header row
                if (tweet.repostedBy != null || (showRankBadge && tweet.localRankReason.isNotBlank())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (tweet.repostedBy != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Repeat,
                                    contentDescription = null,
                                    tint = RepostEmerald,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Reposted by ${tweet.repostedBy}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        if (showRankBadge && tweet.localRankReason.isNotBlank()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.80f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(11.dp),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = tweet.localRankReason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                // Author row (clear displayName + @username always visible)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(
                        url = tweet.author.avatarUrl,
                        displayName = displayName,
                        size = if (isCompact) 36.dp else 44.dp,
                        modifier = Modifier
                            .clickable { onOpenProfile(displayHandle) }
                            .testTag("tweet_avatar_$displayHandle")
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clickable { onOpenProfile(displayHandle) }
                            )
                            if (tweet.author.isVerified) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Filled.Verified,
                                    contentDescription = "Verified account",
                                    tint = VerifiedBlue,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            if (formattedTimestamp.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = formattedTimestamp,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        Text(
                            text = "@$displayHandle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onOpenProfile(displayHandle) },
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("tweet_more_btn_${tweet.id}"),
                        ) {
                            Icon(
                                Icons.Outlined.MoreHoriz,
                                contentDescription = "Post options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Open @$displayHandle") },
                                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onOpenProfile(displayHandle)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.quote_post_action)) },
                                leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    onQuote(tweet)
                                },
                                modifier = Modifier.testTag("more_quote_btn_${tweet.id}"),
                            )
                            DropdownMenuItem(
                                text = { Text("Share & Save options") },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    showMoreMenu = false
                                    showShareSheet = true
                                }
                            )
                            if (onNotInterested != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tweet_not_interested_action)) },
                                    leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        onNotInterested(tweet)
                                    },
                                    modifier = Modifier.testTag("more_not_interested_btn_${tweet.id}"),
                                )
                            }
                            if (onShowLessFromAuthor != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tweet_show_less_author_action, displayHandle)) },
                                    leadingIcon = { Icon(Icons.Outlined.ThumbDownOffAlt, contentDescription = null) },
                                    onClick = {
                                        showMoreMenu = false
                                        onShowLessFromAuthor(tweet)
                                    },
                                    modifier = Modifier.testTag("more_show_less_btn_${tweet.id}"),
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Delete post", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        onDelete(tweet)
                                    }
                                )
                            }
                        }
                    }
                }

                val entityAccent = MaterialTheme.colorScheme.primary
                if (!tweet.replyToHandle.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    val cleanHandle = remember(tweet.replyToHandle) { tweet.replyToHandle.removePrefix("@") }
                    IlayLinkMovementText(
                        text = "Replying to @$cleanHandle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        accentColor = entityAccent,
                        onMentionClick = onOpenProfile,
                        onHashtagClick = host.openHashtag,
                        onUrlClick = host.openUrl,
                        modifier = Modifier.testTag("tweet_reply_to_${tweet.id}"),
                    )
                }

                // Post Body + Long Tweet Collapse (>280 chars) + Compact inline thumbnail
                Spacer(Modifier.height(if (isCompact) 6.dp else 10.dp))
                val displayBodyText = remember(tweet.text, isLongTweet, isTextExpanded) {
                    if (isLongTweet && !isTextExpanded) {
                        tweet.text.take(COLLAPSE_CHAR_THRESHOLD).trimEnd() + "…"
                    } else {
                        tweet.text
                    }
                }

                if (isCompact && tweet.media.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            if (tweet.text.isNotBlank()) {
                                IlayLinkMovementText(
                                    text = displayBodyText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    accentColor = entityAccent,
                                    maxLines = if (isTextExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                    onMentionClick = onOpenProfile,
                                    onHashtagClick = host.openHashtag,
                                    onUrlClick = host.openUrl,
                                    modifier = Modifier.testTag("tweet_body_${tweet.id}"),
                                )
                            }
                            if (isLongTweet) {
                                SeeMoreToggleRow(
                                    expanded = isTextExpanded,
                                    onToggle = { isTextExpanded = !isTextExpanded },
                                    testTag = "see_more_btn_${tweet.id}",
                                )
                            }
                        }
                        val hideThumb = blurSensitiveMedia && tweet.isSensitive && !revealSensitive
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .clickable {
                                    if (hideThumb) revealSensitive = true else onOpenMedia(tweet.media, 0)
                                }
                        ) {
                            if (!hideThumb) {
                                val compactThumbUrl = tweet.media.first().thumbnailUrl.ifBlank { tweet.media.first().url }
                                val imageQualityMode = LocalImageQualityMode.current
                                val compactRequest = remember(compactThumbUrl, context, imageQualityMode) {
                                    IlayImageCache.cardMediaRequest(
                                        context = context,
                                        rawUrl = compactThumbUrl,
                                        compact = true,
                                        mode = imageQualityMode,
                                    )
                                }
                                AsyncImage(
                                    model = compactRequest,
                                    contentDescription = "Post media thumbnail",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.85f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.VisibilityOff,
                                        contentDescription = "Sensitive media hidden",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (!hideThumb && tweet.media.first().type != MediaType.PHOTO) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.65f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            } else if (!hideThumb && tweet.media.size > 1) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.72f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(5.dp),
                                ) {
                                    Text(
                                        text = "1/${tweet.media.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    if (tweet.text.isNotBlank()) {
                        IlayLinkMovementText(
                            text = displayBodyText,
                            style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            accentColor = entityAccent,
                            onMentionClick = onOpenProfile,
                            onHashtagClick = host.openHashtag,
                            onUrlClick = host.openUrl,
                            modifier = Modifier.testTag("tweet_body_${tweet.id}"),
                        )
                        if (isLongTweet) {
                            SeeMoreToggleRow(
                                expanded = isTextExpanded,
                                onToggle = { isTextExpanded = !isTextExpanded },
                                testTag = "see_more_btn_${tweet.id}",
                            )
                        }
                    }
                }

                // Translate Post button & inline translation card for posts not in system language
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
                        testTag = "translate_btn_${tweet.id}",
                    )
                }
            }

            // Framed 16:9 Media Showcase aligned cleanly with tweet text and link preview bounds
            if (!isCompact && tweet.media.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val shouldHide = blurSensitiveMedia && tweet.isSensitive && !revealSensitive
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = contentHorizontalPadding)
                ) {
                    TweetMediaGrid(
                        media = tweet.media,
                        blurred = shouldHide,
                        onMediaClick = { index ->
                            if (shouldHide) revealSensitive = true else onOpenMedia(tweet.media, index)
                        }
                    )
                    if (shouldHide) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.Black.copy(alpha = 0.88f))
                                .clickable { revealSensitive = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Sensitive media · Tap to view", style = MaterialTheme.typography.labelLarge, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Bottom padded block: poll + link preview + quote post + action bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = contentHorizontalPadding,
                        end = contentHorizontalPadding,
                        bottom = if (isCompact) 8.dp else 10.dp,
                    )
            ) {
                // Poll widget
                tweet.poll?.let { poll ->
                    Spacer(Modifier.height(10.dp))
                    PollCard(
                        tweet = tweet,
                        poll = poll,
                        onVoteSubmitted = { updated ->
                            onVotePoll(updated, updated.poll?.votedChoiceIndex ?: -1)
                        },
                    )
                }

                // Native X Article Preview Card
                tweet.article?.let { article ->
                    Spacer(Modifier.height(10.dp))
                    EmbeddedArticlePreviewCard(
                        article = article,
                        compact = isCompact,
                        onClick = { showArticleReader = true },
                    )
                }

                // Link Preview Card (replaces raw link text with rich preview; skips preview image when tweet has media)
                tweet.linkPreview?.let { linkPreview ->
                    Spacer(Modifier.height(10.dp))
                    LinkPreviewCard(
                        preview = linkPreview,
                        compact = isCompact,
                        showImage = tweet.media.isEmpty(),
                    )
                }

                // Embedded Quoted Parent Post (custom constrained MD3E component)
                tweet.quotedTweet?.let { qt ->
                    Spacer(Modifier.height(10.dp))
                    EmbeddedQuotePostCard(
                        quoted = qt,
                        compact = isCompact,
                        blurSensitiveMedia = blurSensitiveMedia,
                        onOpenParent = onOpenThread,
                        onOpenProfile = onOpenProfile,
                        onOpenMedia = onOpenMedia,
                    )
                }

                // Action Bar
                Spacer(Modifier.height(if (isCompact) 4.dp else 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricActionButton(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        count = tweet.metrics.replies,
                        active = false,
                        activeColor = MaterialTheme.colorScheme.primary,
                        contentDescription = "Reply",
                        testTag = "reply_btn_${tweet.id}",
                        onClick = { onReply(tweet) },
                    )

                    Box {
                        MetricActionButton(
                            icon = if (tweet.viewerState.isReposted) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                            count = tweet.metrics.reposts,
                            active = tweet.viewerState.isReposted,
                            activeColor = RepostEmerald,
                            contentDescription = "Repost or Quote",
                            testTag = "repost_btn_${tweet.id}",
                            onClick = { showRepostMenu = true },
                        )
                        DropdownMenu(
                            expanded = showRepostMenu,
                            onDismissRequest = { showRepostMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (tweet.viewerState.isReposted) "Undo Repost" else "Repost") },
                                leadingIcon = { Icon(Icons.Filled.Repeat, contentDescription = null, tint = RepostEmerald) },
                                onClick = {
                                    showRepostMenu = false
                                    onRepost(tweet)
                                },
                                modifier = Modifier.testTag("repost_menu_repost_btn_${tweet.id}"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.quote_post_action)) },
                                leadingIcon = { Icon(Icons.Filled.FormatQuote, contentDescription = null) },
                                onClick = {
                                    showRepostMenu = false
                                    onQuote(tweet)
                                },
                                modifier = Modifier.testTag("repost_menu_quote_btn_${tweet.id}"),
                            )
                        }
                    }

                    MetricActionButton(
                        icon = if (tweet.viewerState.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        count = tweet.metrics.likes,
                        active = tweet.viewerState.isLiked,
                        activeColor = LikeRose,
                        contentDescription = "Like",
                        testTag = "like_btn_${tweet.id}",
                        onClick = { onLike(tweet) },
                    )

                    MetricActionButton(
                        icon = if (tweet.viewerState.isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        count = tweet.metrics.bookmarks,
                        active = tweet.viewerState.isBookmarked,
                        activeColor = BookmarkAmber,
                        contentDescription = "Bookmark",
                        testTag = "bookmark_btn_${tweet.id}",
                        onClick = { onBookmark(tweet) },
                    )

                    IconButton(
                        onClick = { showShareSheet = true },
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("share_btn_${tweet.id}")
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = "Share or save post",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
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
                host.capturePostScreenshot(tweet, null, ScreenshotCaptureAction.OPEN_MARKUP_EDITOR)
            },
            onShareScreenshotImage = {
                showShareSheet = false
                host.capturePostScreenshot(tweet, null, ScreenshotCaptureAction.SHARE_IMAGE)
            },
            onDownloadMedia = {
                showShareSheet = false
                scope.launch {
                    host.showMessage("Downloading media…")
                    host.downloadMedia(tweet)
                        .onSuccess { count ->
                            host.showMessage(
                                if (count == 1) "Media saved to Gallery" else "$count media items saved to Gallery"
                            )
                        }
                        .onFailure { err ->
                            host.showMessage(err.message ?: "Failed to download media")
                        }
                }
            },
            onShareLink = {
                showShareSheet = false
                shareTweetLink(context, tweet)
            },
            onDismiss = { showShareSheet = false },
        )
    }

    if (showArticleReader) {
        tweet.article?.let { article ->
            ArticleReaderSheet(
                tweet = tweet,
                article = article,
                onOpenThread = onOpenThread,
                onOpenProfile = onOpenProfile,
                onDismiss = { showArticleReader = false },
            )
        }
    }
}

@Composable
private fun SeeMoreToggleRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    testTag: String,
) {
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "Show less" else "See more",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Collapse post" else "Expand full post",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun TweetTranslationBlock(
    isTranslating: Boolean,
    result: TranslationResult?,
    accentColor: Color,
    onRequestTranslate: () -> Unit,
    onDismissTranslation: () -> Unit,
    testTag: String,
) {
    Spacer(Modifier.height(6.dp))
    when {
        isTranslating -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                IlayLoadingIndicator(size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Translating post…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        result != null -> {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Translate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Translated from ${result.sourceLanguage} · ${result.provider}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "Hide",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(onClick = onDismissTranslation)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val translationHost = LocalTweetCardHost.current
                    IlayLinkMovementText(
                        text = result.translatedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        accentColor = accentColor,
                        onMentionClick = translationHost.openProfile,
                        onHashtagClick = translationHost.openHashtag,
                        onUrlClick = translationHost.openUrl,
                    )
                }
            }
        }
        else -> {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onRequestTranslate)
                    .padding(vertical = 4.dp, horizontal = 2.dp)
                    .testTag(testTag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Translate,
                    contentDescription = "Translate post",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Translate post",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TweetShareActionSheet(
    tweet: Tweet,
    onCaptureScreenshot: () -> Unit,
    onShareScreenshotImage: () -> Unit,
    onDownloadMedia: () -> Unit,
    onShareLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Share & Save Post",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "@${tweet.author.handle.ifBlank { "x_user" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            ShareOptionCard(
                icon = Icons.Outlined.PhotoCamera,
                title = "Save Post as Image",
                subtitle = stringResource(R.string.share_option_screenshot_subtitle),
                testTag = "share_option_screenshot",
                onClick = onCaptureScreenshot,
            )

            ShareOptionCard(
                icon = Icons.Outlined.Image,
                title = stringResource(R.string.share_option_share_image_title),
                subtitle = stringResource(R.string.share_option_share_image_subtitle),
                testTag = "share_option_share_image",
                onClick = onShareScreenshotImage,
            )

            if (tweet.media.isNotEmpty()) {
                val mediaLabel = if (tweet.media.size == 1) {
                    "Download Media"
                } else {
                    "Download All Media (${tweet.media.size})"
                }
                ShareOptionCard(
                    icon = Icons.Outlined.Download,
                    title = mediaLabel,
                    subtitle = "Save attached photos or video directly to your device",
                    testTag = "share_option_download_media",
                    onClick = onDownloadMedia,
                )
            }

            ShareOptionCard(
                icon = Icons.Outlined.Share,
                title = "Share Post Link",
                subtitle = "Send this post's link to another app",
                testTag = "share_option_link",
                onClick = onShareLink,
            )
        }
    }
}

@Composable
private fun ShareOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun UserAvatar(
    url: String,
    displayName: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val initial = displayName.trim().removePrefix("@").firstOrNull()?.uppercaseChar()?.toString() ?: "X"
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (url.isNotBlank()) {
            val avatarContext = LocalContext.current
            val imageQualityMode = LocalImageQualityMode.current
            val avatarRequest = remember(url, avatarContext, imageQualityMode) {
                IlayImageCache.avatarRequest(avatarContext, url, mode = imageQualityMode)
            }
            AsyncImage(
                model = avatarRequest,
                contentDescription = "$displayName avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal const val TWEET_MEDIA_MIN_ASPECT_RATIO = 1f
internal const val TWEET_MEDIA_MAX_ASPECT_RATIO = 16f / 9f

/**
 * Clamps a media attachment's natural aspect ratio (`width / height`) to `[1:1, 16:9]`:
 * - Tall/vertical content (e.g. 9:16, 4:5, 3:4) clamps to `1:1` so long-form media never
 *   dominates vertical feed space.
 * - Mid-range ratios (e.g. 1:1, 4:3, 3:2) preserve their exact natural framing.
 * - Ultra-wide panoramas (e.g. 21:9) clamp to `16:9`.
 * - Missing or invalid dimensions fall back safely to `16:9`.
 */
internal fun clampedTweetMediaAspectRatio(item: MediaAttachment?): Float {
    if (item == null || item.width <= 0 || item.height <= 0) {
        return TWEET_MEDIA_MAX_ASPECT_RATIO
    }
    val naturalRatio = item.width.toFloat() / item.height.toFloat()
    if (!naturalRatio.isFinite() || naturalRatio <= 0f) {
        return TWEET_MEDIA_MAX_ASPECT_RATIO
    }
    return naturalRatio.coerceIn(TWEET_MEDIA_MIN_ASPECT_RATIO, TWEET_MEDIA_MAX_ASPECT_RATIO)
}

/**
 * Ilay-style Card Media Showcase:
 * - Clamps single-media and multi-media carousel frames to `[1:1, 16:9]` based on the first
 *   attachment's natural aspect ratio, while tapping opens the full uncropped asset in the lightbox.
 * - Multi-media (2-4 items): inline swipeable HorizontalPager gallery carousel with top-right
 *   "1 / N" pill counter and bottom-center morphing page dots.
 */
@Composable
fun TweetMediaGrid(
    media: List<MediaAttachment>,
    blurred: Boolean,
    onMediaClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frameShape = RoundedCornerShape(18.dp)
    val frameBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val firstMedia = media.firstOrNull()
    val mediaAspectRatio = remember(firstMedia?.width, firstMedia?.height) {
        clampedTweetMediaAspectRatio(firstMedia)
    }

    if (media.size == 1) {
        val item = media.first()
        MediaTile(
            item = item,
            blurred = blurred,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(mediaAspectRatio)
                .clip(frameShape)
                .clipToBounds()
                .border(1.dp, frameBorderColor, frameShape),
            onClick = { onMediaClick(0) },
        )
    } else if (media.size > 1) {
        val pagerState = rememberPagerState(pageCount = { media.size })

        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(mediaAspectRatio)
                .clip(frameShape)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, frameBorderColor, frameShape),
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                MediaTile(
                    item = media[page],
                    blurred = blurred,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { onMediaClick(page) },
                )
            }

            if (!blurred) {
                // Subtle bottom scrim so page dots stay crisp on bright photos
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f))
                            )
                        )
                )

                // Top-right Ilay gallery counter pill ("1 / 4")
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.68f),
                    border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.22f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                ) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${media.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }

                // Bottom-center MD3 Expressive morphing pill indicators
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.48f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(media.size) { index ->
                            val selected = pagerState.currentPage == index
                            val dotWidth by animateDpAsState(
                                targetValue = if (selected) 18.dp else 6.dp,
                                animationSpec = IlayMotion.spatialBouncy(),
                                label = "galleryDotWidth",
                            )
                            Box(
                                modifier = Modifier
                                    .width(dotWidth)
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) Color.White else Color.White.copy(alpha = 0.45f)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(
    item: MediaAttachment,
    blurred: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tileContext = LocalContext.current
    val imageQualityMode = LocalImageQualityMode.current
    val mediaUrl = item.thumbnailUrl.ifBlank { item.url }
    val mediaRequest = remember(mediaUrl, tileContext, imageQualityMode) {
        IlayImageCache.cardMediaRequest(
            context = tileContext,
            rawUrl = mediaUrl,
            compact = false,
            mode = imageQualityMode,
        )
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = mediaRequest,
            contentDescription = item.altText.ifBlank { "Attached media" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (!blurred && item.altText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.68f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
            ) {
                Text(
                    text = "ALT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (item.type == MediaType.VIDEO || item.type == MediaType.GIF) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            val badge = if (item.type == MediaType.GIF) "GIF" else formatDuration(item.durationMs)
            if (badge.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricActionButton(
    icon: ImageVector,
    count: Long,
    active: Boolean,
    activeColor: Color,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.15f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "actionScale"
    )
    val tint by animateColorAsState(
        targetValue = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "actionTint"
    )

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(19.dp)
                .scale(scale)
        )
        val formatted = FormatUtils.compactCount(count)
        if (formatted.isNotBlank()) {
            Spacer(Modifier.width(5.dp))
            Text(
                text = formatted,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "VIDEO"
    val totalSec = durationMs / 1000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%d:%02d", mins, secs)
}

internal fun shareTweetLink(context: Context, tweet: Tweet) {
    val url = "https://x.com/${tweet.author.handle.ifBlank { "i" }}/status/${tweet.id}"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
    }
    context.startActivity(Intent.createChooser(intent, "Share post link"))
}
