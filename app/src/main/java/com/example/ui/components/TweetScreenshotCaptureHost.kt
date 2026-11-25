package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.toBitmap
import com.example.R
import com.example.data.remote.AdaptiveImageQualitySelector
import com.example.data.remote.IlayImageCache
import com.example.data.remote.ImageUsage
import com.example.data.remote.LocalImageQualityMode
import com.example.data.remote.NetworkTier
import com.example.domain.model.ImageQualityMode
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.Poll
import com.example.domain.model.QuotedTweet
import com.example.domain.model.Tweet
import com.example.ui.theme.VerifiedBlue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ScreenshotCaptureAction {
    OPEN_MARKUP_EDITOR,
    SHARE_IMAGE,
}

@Immutable
data class ScreenshotCaptureRequest(
    val requestId: Long = System.nanoTime(),
    val tweet: Tweet,
    val parentTweet: Tweet? = null,
    val parentMissingNotice: Boolean = false,
    val action: ScreenshotCaptureAction = ScreenshotCaptureAction.OPEN_MARKUP_EDITOR,
)

/**
 * Offscreen, unconstrained-height capture host that renders an untruncated, clean,
 * reply-aware screenshot card for a post:
 * - Never clips long tweets (> 280 chars) or omits quoted tweet text.
 * - Automatically stacks the resolved original parent tweet above the reply with a vertical rail.
 * - Pre-loads avatars & media into Coil memory cache before snapshotting to a PNG Bitmap.
 * - Saves to MediaStore / scoped FileProvider and opens directly in Android's system markup editor.
 */
@Composable
fun TweetScreenshotCaptureHost(
    request: ScreenshotCaptureRequest?,
    onFinished: (message: String) -> Unit,
) {
    if (request == null) return

    val context = LocalContext.current
    val imageQualityMode = LocalImageQualityMode.current
    val savedOpeningEditorMsg = stringResource(R.string.screenshot_saved_opening_editor)
    val savedOpeningShareMsg = stringResource(R.string.screenshot_saved_opening_share)
    val savedGalleryOnlyMsg = stringResource(R.string.screenshot_saved_gallery_only)
    val graphicsLayer = rememberGraphicsLayer()
    var preloadedBitmaps by remember(request.requestId) {
        mutableStateOf<Map<String, ImageBitmap>?>(null)
    }

    LaunchedEffect(request.requestId) {
        val loaded = resolveScreenshotBitmaps(
            context = context,
            request = request,
            mode = imageQualityMode,
        )
        preloadedBitmaps = loaded
        // Allow Compose to complete layout + synchronous draw with resolved software bitmaps
        withFrameNanos { }
        withFrameNanos { }
        withFrameNanos { }

        val result = runCatching {
            val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
            TweetMediaSaver.saveScreenshotAndReturnUri(
                context = context,
                bitmap = bitmap,
                filenamePrefix = "ilay_post_${request.tweet.id}",
            ).getOrThrow()
        }

        result.fold(
            onSuccess = { saved ->
                val msg = when (request.action) {
                    ScreenshotCaptureAction.SHARE_IMAGE -> {
                        val shared = TweetMediaSaver.openImageShareSheet(context, saved.uri)
                        if (shared) savedOpeningShareMsg else savedGalleryOnlyMsg
                    }
                    ScreenshotCaptureAction.OPEN_MARKUP_EDITOR -> {
                        when (TweetMediaSaver.openInSystemMarkupEditorOrShare(context, saved.uri)) {
                            TweetMediaSaver.ScreenshotLaunchOutcome.OPENED_MARKUP_EDITOR -> savedOpeningEditorMsg
                            TweetMediaSaver.ScreenshotLaunchOutcome.OPENED_SHARE_SHEET -> savedOpeningShareMsg
                            TweetMediaSaver.ScreenshotLaunchOutcome.SAVED_ONLY -> savedGalleryOnlyMsg
                        }
                    }
                }
                onFinished(msg)
            },
            onFailure = { err ->
                onFinished(err.message ?: "Could not capture post screenshot")
            },
        )
    }

    val bitmaps = preloadedBitmaps ?: return

    // Measure with unconstrained height so very long posts + parent tweets capture completely,
    // while reporting 0 layout height so it never shifts or blocks visible screen content.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0f)
            .layout { measurable, constraints ->
                val maxWidthPx = constraints.maxWidth.coerceAtMost(1440)
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = maxWidthPx,
                        maxWidth = maxWidthPx,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    )
                )
                layout(placeable.width, 0) {
                    placeable.place(0, 0)
                }
            }
            .testTag("tweet_screenshot_capture_host"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .drawWithContent {
                    graphicsLayer.record {
                        this@drawWithContent.drawContent()
                    }
                    drawLayer(graphicsLayer)
                }
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            ScreenshotExportCard(request = request, bitmaps = bitmaps)
        }
    }
}

private data class ScreenshotImageTarget(
    val lookupKey: String,
    val candidateUrls: List<String>,
)

internal fun buildMediaCandidateUrls(
    item: MediaAttachment,
    mode: ImageQualityMode,
    networkTier: NetworkTier,
): List<String> {
    val rawThumb = item.thumbnailUrl.ifBlank { item.url }.trim()
    val rawUrl = item.url.trim()
    val isRawUrlImage = rawUrl.isNotBlank() &&
        !rawUrl.contains(".mp4", ignoreCase = true) &&
        !rawUrl.contains(".m3u8", ignoreCase = true)
    return buildList {
        if (rawThumb.isNotBlank()) {
            add(AdaptiveImageQualitySelector.previewUrlFor(rawThumb, compact = false, mode = mode, networkTier = networkTier))
            add(AdaptiveImageQualitySelector.previewUrlFor(rawThumb, compact = true, mode = mode, networkTier = networkTier))
            add(rawThumb)
        }
        if (isRawUrlImage && rawUrl != rawThumb) {
            add(AdaptiveImageQualitySelector.previewUrlFor(rawUrl, compact = false, mode = mode, networkTier = networkTier))
            add(rawUrl)
        }
    }.filter { it.isNotBlank() }.distinct()
}

private fun buildAvatarCandidateUrls(
    rawUrl: String,
    mode: ImageQualityMode,
    networkTier: NetworkTier,
): List<String> {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return emptyList()
    val variant = AdaptiveImageQualitySelector.selectVariant(
        rawUrl = trimmed,
        usage = ImageUsage.AVATAR,
        mode = mode,
        networkTier = networkTier,
    ).url
    return listOf(variant, trimmed).filter { it.isNotBlank() }.distinct()
}

private fun screenshotMediaKey(item: MediaAttachment): String =
    "media:${item.id.ifBlank { item.thumbnailUrl.ifBlank { item.url } }}"

private fun screenshotAvatarKey(rawUrl: String): String = "avatar:${rawUrl.trim()}"

private suspend fun resolveScreenshotBitmaps(
    context: Context,
    request: ScreenshotCaptureRequest,
    mode: ImageQualityMode,
): Map<String, ImageBitmap> = withContext(Dispatchers.IO) {
    val networkTier = AdaptiveImageQualitySelector.detectNetworkTier(context)
    val targets = LinkedHashMap<String, ScreenshotImageTarget>()

    fun addAvatar(url: String) {
        if (url.isBlank()) return
        val key = screenshotAvatarKey(url)
        if (key !in targets) {
            targets[key] = ScreenshotImageTarget(
                lookupKey = key,
                candidateUrls = buildAvatarCandidateUrls(url, mode, networkTier),
            )
        }
    }

    fun addMedia(item: MediaAttachment) {
        val key = screenshotMediaKey(item)
        if (key !in targets) {
            val candidates = buildMediaCandidateUrls(item, mode, networkTier)
            if (candidates.isNotEmpty()) {
                targets[key] = ScreenshotImageTarget(lookupKey = key, candidateUrls = candidates)
            }
        }
    }

    fun collectTweet(t: Tweet) {
        addAvatar(t.author.avatarUrl)
        t.media.take(4).forEach(::addMedia)
        t.quotedTweet?.let { q ->
            addAvatar(q.author.avatarUrl)
            q.media.take(2).forEach(::addMedia)
        }
    }

    request.parentTweet?.let(::collectTweet)
    collectTweet(request.tweet)
    if (targets.isEmpty()) return@withContext emptyMap()

    val loader = SingletonImageLoader.get(context)

    suspend fun loadCandidate(url: String, cacheOnly: Boolean): ImageBitmap? {
        val canonical = IlayImageCache.canonicalKey(url)
        val req = ImageRequest.Builder(context)
            .data(url)
            .diskCacheKey(canonical)
            .memoryCacheKey(canonical)
            .diskCachePolicy(if (cacheOnly) CachePolicy.READ_ONLY else CachePolicy.ENABLED)
            .memoryCachePolicy(if (cacheOnly) CachePolicy.READ_ONLY else CachePolicy.ENABLED)
            .networkCachePolicy(if (cacheOnly) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .allowHardware(false)
            .crossfade(false)
            .build()
        val res = runCatching { loader.execute(req) }.getOrNull() as? SuccessResult ?: return null
        val rawBmp = runCatching { res.image.toBitmap() }.getOrNull() ?: return null
        val safeBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && rawBmp.config == Bitmap.Config.HARDWARE) {
            rawBmp.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            rawBmp
        }
        return safeBmp?.asImageBitmap()
    }

    withTimeoutOrNull(8_000L) {
        coroutineScope {
            targets.values.map { target ->
                async {
                    // 1. Instant local hit from Coil memory/disk cache (reuses already-loaded feed variants)
                    var bitmap: ImageBitmap? = null
                    for (candidate in target.candidateUrls) {
                        bitmap = loadCandidate(candidate, cacheOnly = true)
                        if (bitmap != null) break
                    }
                    // 2. Network fallback if not yet in cache
                    if (bitmap == null) {
                        for (candidate in target.candidateUrls) {
                            bitmap = loadCandidate(candidate, cacheOnly = false)
                            if (bitmap != null) break
                        }
                    }
                    bitmap?.let { target.lookupKey to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    } ?: emptyMap()
}

@Composable
private fun ScreenshotExportCard(
    request: ScreenshotCaptureRequest,
    bitmaps: Map<String, ImageBitmap>,
) {
    val context = LocalContext.current
    val entityAccent = MaterialTheme.colorScheme.primary
    val tweet = request.tweet
    val parent = request.parentTweet

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            // Optional Parent Tweet stacked above with thread rail
            if (parent != null) {
                ScreenshotParentTweetBlock(
                    parent = parent,
                    entityAccent = entityAccent,
                    bitmaps = bitmaps,
                )
            } else if (request.parentMissingNotice && !tweet.replyToHandle.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.screenshot_parent_unavailable,
                                tweet.replyToHandle.removePrefix("@"),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Primary / Reply Target Tweet (full untruncated text & full content)
            val displayHandle = tweet.author.handle.ifBlank { "x_user" }
            val displayName = tweet.author.displayName.ifBlank { "@$displayHandle" }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ScreenshotAvatar(
                    url = tweet.author.avatarUrl,
                    displayName = displayName,
                    sizeDp = 46,
                    bitmaps = bitmaps,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (tweet.author.isVerified) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Verified,
                                contentDescription = null,
                                tint = VerifiedBlue,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Text(
                        text = "@$displayHandle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (parent == null && !request.parentMissingNotice && !tweet.replyToHandle.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Replying to @${tweet.replyToHandle.removePrefix("@")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = entityAccent,
                )
            }

            if (tweet.text.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = FormatUtils.highlightTweetEntities(tweet.text, entityAccent),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (tweet.media.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ScreenshotStaticMediaGrid(media = tweet.media, bitmaps = bitmaps)
            }

            tweet.poll?.let { poll ->
                Spacer(Modifier.height(12.dp))
                ScreenshotPollSummary(poll = poll)
            }

            tweet.linkPreview?.let { preview ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Link,
                            contentDescription = null,
                            tint = entityAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            if (preview.title.isNotBlank()) {
                                Text(
                                    text = preview.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = preview.domain.ifBlank { FormatUtils.formatDisplayUrl(preview.url) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            tweet.quotedTweet?.let { quoted ->
                Spacer(Modifier.height(12.dp))
                ScreenshotQuotedTweetBlock(quoted = quoted, entityAccent = entityAccent, bitmaps = bitmaps)
            }

            // Full timestamp + clean engagement summary footer (no interactive chrome)
            val fullTimestamp = FormatUtils.formatFullDeviceTimestamp(context, tweet.createdAtEpochMs)
            if (fullTimestamp.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = buildString {
                        append(fullTimestamp)
                        if (tweet.sourceLabel.isNotBlank()) append(" · ${tweet.sourceLabel}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val stats = buildList {
                if (tweet.metrics.replies > 0L) add("${FormatUtils.compactCount(tweet.metrics.replies)} Replies")
                if (tweet.metrics.reposts > 0L) add("${FormatUtils.compactCount(tweet.metrics.reposts)} Reposts")
                if (tweet.metrics.likes > 0L) add("${FormatUtils.compactCount(tweet.metrics.likes)} Likes")
                if (tweet.metrics.views > 0L) add("${FormatUtils.compactCount(tweet.metrics.views)} Views")
            }
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    stats.forEach { stat ->
                        Text(
                            text = stat,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotParentTweetBlock(
    parent: Tweet,
    entityAccent: Color,
    bitmaps: Map<String, ImageBitmap>,
) {
    val context = LocalContext.current
    val handle = parent.author.handle.ifBlank { "x_user" }
    val displayName = parent.author.displayName.ifBlank { "@$handle" }
    val timeLabel = FormatUtils.formatDeviceTime(context, parent.createdAtEpochMs)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // Left column: Parent Avatar + vertical thread connector rail reaching down to reply avatar
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(46.dp)
                .fillMaxHeight(),
        ) {
            ScreenshotAvatar(
                url = parent.author.avatarUrl,
                displayName = displayName,
                sizeDp = 42,
                bitmaps = bitmaps,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .weight(1f, fill = true)
                    .padding(bottom = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (parent.author.isVerified) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = null,
                        tint = VerifiedBlue,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = "@$handle" + if (timeLabel.isNotBlank()) " · $timeLabel" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (parent.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = FormatUtils.highlightTweetEntities(parent.text, entityAccent),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (parent.media.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ScreenshotStaticMediaGrid(media = parent.media, bitmaps = bitmaps)
            }
            parent.quotedTweet?.let { qt ->
                Spacer(Modifier.height(8.dp))
                ScreenshotQuotedTweetBlock(quoted = qt, entityAccent = entityAccent, bitmaps = bitmaps)
            }
        }
    }
}

@Composable
private fun ScreenshotQuotedTweetBlock(
    quoted: QuotedTweet,
    entityAccent: Color,
    bitmaps: Map<String, ImageBitmap>,
) {
    val handle = quoted.author.handle.ifBlank { "x_user" }
    val displayName = quoted.author.displayName.ifBlank { "@$handle" }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenshotAvatar(
                    url = quoted.author.avatarUrl,
                    displayName = displayName,
                    sizeDp = 22,
                    bitmaps = bitmaps,
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
                        contentDescription = null,
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
                )
            }
            if (quoted.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = FormatUtils.highlightTweetEntities(quoted.text, entityAccent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (quoted.media.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ScreenshotStaticMediaGrid(media = quoted.media.take(2), bitmaps = bitmaps)
            }
        }
    }
}

@Composable
private fun ScreenshotPollSummary(poll: Poll) {
    val totalVotes = poll.totalVotes.coerceAtLeast(poll.choices.sumOf { it.votes }).coerceAtLeast(1L)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Poll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.poll_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            poll.choices.forEach { choice ->
                val pct = ((choice.votes * 100.0) / totalVotes).toInt().coerceIn(0, 100)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenshotStaticMediaGrid(
    media: List<MediaAttachment>,
    bitmaps: Map<String, ImageBitmap>,
) {
    val items = media.take(4)
    val frameShape = RoundedCornerShape(16.dp)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    when (items.size) {
        1 -> {
            ScreenshotMediaTile(
                item = items[0],
                bitmaps = bitmaps,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(clampedTweetMediaAspectRatio(items[0]))
                    .clip(frameShape)
                    .border(1.dp, borderColor, frameShape),
            )
        }
        2 -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(frameShape)
                    .border(1.dp, borderColor, frameShape),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items.forEach { item ->
                    ScreenshotMediaTile(
                        item = item,
                        bitmaps = bitmaps,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                    )
                }
            }
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(frameShape)
                    .border(1.dp, borderColor, frameShape),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    ScreenshotMediaTile(item = items[0], bitmaps = bitmaps, modifier = Modifier.weight(1f).aspectRatio(4f / 3f))
                    ScreenshotMediaTile(item = items[1], bitmaps = bitmaps, modifier = Modifier.weight(1f).aspectRatio(4f / 3f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    ScreenshotMediaTile(item = items[2], bitmaps = bitmaps, modifier = Modifier.weight(1f).aspectRatio(4f / 3f))
                    if (items.size > 3) {
                        ScreenshotMediaTile(item = items[3], bitmaps = bitmaps, modifier = Modifier.weight(1f).aspectRatio(4f / 3f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotMediaTile(
    item: MediaAttachment,
    bitmaps: Map<String, ImageBitmap>,
    modifier: Modifier = Modifier,
) {
    val resolvedBitmap = bitmaps[screenshotMediaKey(item)]
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (resolvedBitmap != null) {
            Image(
                bitmap = resolvedBitmap,
                contentDescription = item.altText.ifBlank { "Post media" },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.type == MediaType.VIDEO || item.type == MediaType.GIF) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun ScreenshotAvatar(
    url: String,
    displayName: String,
    sizeDp: Int,
    bitmaps: Map<String, ImageBitmap>,
) {
    val initial = displayName.trim().removePrefix("@").firstOrNull()?.uppercaseChar()?.toString() ?: "X"
    val resolvedBitmap = if (url.isNotBlank()) bitmaps[screenshotAvatarKey(url)] else null
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (resolvedBitmap != null) {
            Image(
                bitmap = resolvedBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
