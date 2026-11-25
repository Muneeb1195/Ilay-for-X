package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.data.remote.IlayImageCache
import com.example.data.remote.LocalImageQualityMode
import com.example.domain.model.LinkPreview
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.Poll
import com.example.domain.model.QuotedTweet
import com.example.domain.model.Tweet
import com.example.ui.theme.IlayMotion
import com.example.ui.theme.VerifiedBlue

/**
 * Material 3 Expressive custom timeline component for rendering an embedded parent (quoted) post.
 *
 * Layout constraints & design guarantees:
 * - Strict width bounding (`fillMaxWidth`) inside parent cards with ellipsized author/handle overflow
 *   and non-wrapping timestamps so narrow/split-screen viewports never clip.
 * - Distinct tonal elevation (`surfaceContainerHigh`) + subtle start-edge accent rail linking
 *   commentary to the parent post.
 * - Height-capped embedded media previews (`148dp` compact / `184dp` standard) with sensitive-media
 *   blur support, video/GIF badges, and direct lightbox taps.
 * - Graceful compact summaries for embedded polls, link previews, engagement counters, and
 *   unavailable/tombstone parent posts.
 */
@Composable
fun EmbeddedQuotePostCard(
    quoted: QuotedTweet,
    onOpenParent: (Tweet) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    blurSensitiveMedia: Boolean = false,
    onOpenProfile: ((String) -> Unit)? = null,
    onOpenMedia: ((List<MediaAttachment>, Int) -> Unit)? = null,
    interactive: Boolean = true,
) {
    val cardShape = RoundedCornerShape(18.dp)

    if (quoted.isUnavailable) {
        UnavailableQuotePostCard(
            reason = quoted.unavailableReason.ifBlank { "This post is unavailable." },
            shape = cardShape,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val host = LocalTweetCardHost.current
    val handle = quoted.author.handle.ifBlank { "x_user" }
    val displayName = quoted.author.displayName.ifBlank { "@$handle" }
    val relativeTime = remember(quoted.createdAtEpochMs, context) {
        if (quoted.createdAtEpochMs > 0L) FormatUtils.formatDeviceTime(context, quoted.createdAtEpochMs) else ""
    }
    val accent = MaterialTheme.colorScheme.primary
    val mentionHandler = onOpenProfile ?: host.openProfile
    val staticHighlightedBody = remember(quoted.text, accent, interactive) {
        if (!interactive) FormatUtils.highlightTweetEntities(quoted.text, accent) else null
    }
    var revealSensitive by remember(quoted.id) { mutableStateOf(false) }

    Surface(
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .let { base -> if (interactive) base.clickable { onOpenParent(quoted.toTweet()) } else base }
            .animateContentSize(IlayMotion.spatialDefault())
            .semantics {
                contentDescription = "Quoted post by $displayName @$handle"
            }
            .testTag("quoted_tweet_${quoted.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            // Subtle MD3E vertical accent bar visually anchoring the embedded parent post
            Box(
                modifier = Modifier
                    .padding(start = 10.dp, top = 12.dp, bottom = 12.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.50f))
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = 10.dp,
                        end = 12.dp,
                        top = if (compact) 10.dp else 12.dp,
                        bottom = if (compact) 10.dp else 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            ) {
                // Constrained Parent Author Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base ->
                            if (onOpenProfile != null) {
                                base
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenProfile(handle) }
                            } else {
                                base
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(
                        url = quoted.author.avatarUrl,
                        displayName = displayName,
                        size = if (compact) 18.dp else 22.dp,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (quoted.author.isVerified) {
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "Verified account",
                            tint = VerifiedBlue,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "@$handle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (relativeTime.isNotBlank()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "· $relativeTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }

                // Parent Post Body Text (strictly line-bounded)
                if (quoted.text.isNotBlank()) {
                    if (interactive) {
                        IlayLinkMovementText(
                            text = quoted.text,
                            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            accentColor = accent,
                            maxLines = if (compact) 3 else 5,
                            overflow = TextOverflow.Ellipsis,
                            onMentionClick = mentionHandler,
                            onHashtagClick = host.openHashtag,
                            onUrlClick = host.openUrl,
                            modifier = Modifier.testTag("quoted_body_${quoted.id}"),
                        )
                    } else {
                        Text(
                            text = staticHighlightedBody ?: FormatUtils.highlightTweetEntities(quoted.text, accent),
                            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (compact) 3 else 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Height-Constrained Embedded Media Preview
                if (quoted.media.isNotEmpty()) {
                    val isBlurred = blurSensitiveMedia && quoted.isSensitive && !revealSensitive
                    EmbeddedQuoteMediaPreview(
                        media = quoted.media,
                        compact = compact,
                        blurred = isBlurred,
                        onRevealSensitive = { revealSensitive = true },
                        onMediaClick = { index ->
                            if (interactive) {
                                if (onOpenMedia != null) {
                                    onOpenMedia(quoted.media, index)
                                } else {
                                    onOpenParent(quoted.toTweet())
                                }
                            }
                        },
                    )
                }

                // Embedded Poll Indicator Pill
                quoted.poll?.let { poll ->
                    EmbeddedPollBadge(poll = poll)
                }

                // Embedded Link Preview Pill (when parent post has no media)
                if (quoted.media.isEmpty() && quoted.poll == null) {
                    quoted.linkPreview?.let { link ->
                        EmbeddedLinkBadge(preview = link)
                    }
                }

                // Subtle Parent Engagement Footer (standard density only)
                if (!compact) {
                    val m = quoted.metrics
                    val hasStats = m.replies > 0L || m.reposts > 0L || m.likes > 0L
                    if (hasStats) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val statsText = remember(m) {
                                buildList {
                                    if (m.replies > 0L) add("${FormatUtils.compactCount(m.replies)} replies")
                                    if (m.reposts > 0L) add("${FormatUtils.compactCount(m.reposts)} reposts")
                                    if (m.likes > 0L) add("${FormatUtils.compactCount(m.likes)} likes")
                                }.joinToString(" · ")
                            }
                            Text(
                                text = statsText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (interactive) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = "Open quoted post",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnavailableQuotePostCard(
    reason: String,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("quoted_tweet_unavailable"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmbeddedQuoteMediaPreview(
    media: List<MediaAttachment>,
    compact: Boolean,
    blurred: Boolean,
    onRevealSensitive: () -> Unit,
    onMediaClick: (Int) -> Unit,
) {
    val clipShape = RoundedCornerShape(12.dp)
    val frameAspectRatio = if (media.size == 1) {
        clampedTweetMediaAspectRatio(media.first())
    } else {
        TWEET_MEDIA_MAX_ASPECT_RATIO
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(frameAspectRatio)
            .clip(clipShape)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        when (media.size) {
            1 -> {
                EmbeddedMediaTile(
                    item = media[0],
                    blurred = blurred,
                    onClick = { if (blurred) onRevealSensitive() else onMediaClick(0) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            2 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    media.forEachIndexed { idx, item ->
                        EmbeddedMediaTile(
                            item = item,
                            blurred = blurred,
                            onClick = { if (blurred) onRevealSensitive() else onMediaClick(idx) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
            else -> {
                val extraCount = (media.size - 2).coerceAtLeast(0)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    EmbeddedMediaTile(
                        item = media[0],
                        blurred = blurred,
                        onClick = { if (blurred) onRevealSensitive() else onMediaClick(0) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    if (compact) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            EmbeddedMediaTile(
                                item = media[1],
                                blurred = blurred,
                                onClick = { if (blurred) onRevealSensitive() else onMediaClick(1) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (extraCount > 0 && !blurred) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.72f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp),
                                ) {
                                    Text(
                                        text = "+$extraCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            EmbeddedMediaTile(
                                item = media[1],
                                blurred = blurred,
                                onClick = { if (blurred) onRevealSensitive() else onMediaClick(1) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                EmbeddedMediaTile(
                                    item = media[2],
                                    blurred = blurred,
                                    onClick = { if (blurred) onRevealSensitive() else onMediaClick(2) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (media.size > 3 && !blurred) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color.Black.copy(alpha = 0.72f),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp),
                                    ) {
                                        Text(
                                            text = "+${media.size - 3}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (blurred) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onRevealSensitive),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VisibilityOff,
                            contentDescription = "Reveal sensitive media",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "Sensitive media · Tap to view",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddedMediaTile(
    item: MediaAttachment,
    blurred: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageQualityMode = LocalImageQualityMode.current
    val imageUrl = item.thumbnailUrl.ifBlank { item.url }
    val request = remember(imageUrl, context, imageQualityMode) {
        IlayImageCache.cardMediaRequest(context, imageUrl, compact = false, mode = imageQualityMode)
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.altText.ifBlank { "Quoted post media" },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurred) Modifier.blur(20.dp) else Modifier),
        )

        if (!blurred && (item.type == MediaType.VIDEO || item.type == MediaType.GIF)) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = if (item.type == MediaType.GIF) "Play GIF" else "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            val badgeText = when {
                item.type == MediaType.GIF -> "GIF"
                item.durationMs > 0L -> {
                    val secs = (item.durationMs / 1000L).coerceAtLeast(1L)
                    "%d:%02d".format(secs / 60L, secs % 60L)
                }
                else -> "VIDEO"
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun EmbeddedPollBadge(poll: Poll) {
    val summary = remember(poll.choices.size, poll.totalVotes, poll.isFinal) {
        val countPart = "${poll.choices.size} options"
        val votesPart = if (poll.totalVotes > 0L) {
            " · ${FormatUtils.compactCount(poll.totalVotes)} votes"
        } else ""
        val statusPart = if (poll.isFinal) " · Final" else ""
        "Poll · $countPart$votesPart$statusPart"
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Poll,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmbeddedLinkBadge(preview: LinkPreview) {
    val label = preview.title.ifBlank { preview.domain.ifBlank { preview.displayUrl } }
    if (label.isBlank()) return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
