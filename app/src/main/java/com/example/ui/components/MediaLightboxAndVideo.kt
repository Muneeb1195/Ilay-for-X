package com.example.ui.components

import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.remote.AdaptiveImageQualitySelector
import com.example.data.remote.IlayImageCache
import com.example.data.remote.ImageUsage
import com.example.data.remote.LocalImageQualityMode
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.ui.theme.IlayMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Full-screen Material 3 Expressive Media Viewer for photos, videos, and GIFs.
 * - Swipeable HorizontalPager with smooth spring pill indicators (locks paging while zoomed)
 * - Pinch-to-zoom (1x..5x), double-tap zoom (1x <-> 2.5x), and viewport-clamped panning
 * - Vertical drag-to-dismiss at 1x scale with dynamic backdrop alpha fade
 * - Tap-to-toggle immersive chrome with Save to Gallery, Share, and expandable ALT description
 * - Custom Video/GIF player with buffering indicator, Play/Pause, Mute toggle, and lifecycle cleanup
 */
internal const val LIGHTBOX_CHROME_AUTO_HIDE_MS = 3_000L

@Composable
fun MediaLightboxDialog(
    mediaList: List<MediaAttachment>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSaveMedia: (suspend (MediaAttachment) -> Result<Boolean>)? = null,
    onShowMessage: (String) -> Unit = {},
) {
    if (mediaList.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 130.dp.toPx() }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, mediaList.lastIndex),
        pageCount = { mediaList.size },
    )
    val current = mediaList[pagerState.currentPage]

    val imageQualityMode = LocalImageQualityMode.current
    val networkTier = remember(context, imageQualityMode) {
        AdaptiveImageQualitySelector.detectNetworkTier(context)
    }

    var chromeVisible by remember { mutableStateOf(true) }
    var chromeInteractionNonce by remember { mutableIntStateOf(0) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var isVideoScrubbing by remember { mutableStateOf(false) }
    var altExpanded by remember(pagerState.currentPage) { mutableStateOf(false) }
    var forcedOriginalIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val bumpChromeTimer: () -> Unit = {
        chromeInteractionNonce++
    }

    // Automatically collapse top/bottom overlays & system bars after 3s of inactivity
    LaunchedEffect(chromeVisible, chromeInteractionNonce, altExpanded, isSaving, isVideoScrubbing) {
        if (chromeVisible && !altExpanded && !isSaving && !isVideoScrubbing) {
            delay(LIGHTBOX_CHROME_AUTO_HIDE_MS)
            chromeVisible = false
        }
    }

    // Vertical drag-to-dismiss state (active when unzoomed)
    val dismissDragY = remember { Animatable(0f) }
    val dragProgress = (abs(dismissDragY.value) / (dismissThresholdPx * 2.2f)).coerceIn(0f, 1f)
    val backdropAlpha = (1f - dragProgress * 0.65f).coerceIn(0.35f, 1f)
    val contentScale = 1f - dragProgress * 0.14f

    LaunchedEffect(pagerState.currentPage) {
        isCurrentPageZoomed = false
        isVideoScrubbing = false
        dismissDragY.snapTo(0f)
        if (chromeVisible) {
            chromeInteractionNonce++
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Configure Dialog Window for true edge-to-edge full-screen rendering & immersive system bars
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.setBackgroundDrawable(android.graphics.Color.BLACK.toDrawable())
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = attrs
                }
            }
            onDispose {
                val w = (dialogView.parent as? DialogWindowProvider)?.window
                if (w != null) {
                    WindowCompat.getInsetsController(w, dialogView)
                        .show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        LaunchedEffect(dialogView, chromeVisible) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, dialogView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            if (chromeVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backdropAlpha)),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isCurrentPageZoomed && abs(dismissDragY.value) < 6f,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dismissDragY.value
                        scaleX = contentScale
                        scaleY = contentScale
                    },
            ) { page ->
                val item = mediaList[page]
                val isActivePage = pagerState.currentPage == page
                if ((item.type == MediaType.VIDEO || item.type == MediaType.GIF) &&
                    !item.videoUrl.isNullOrBlank()
                ) {
                    val (playbackVideoUrl, _) = remember(
                        item.id,
                        item.videoUrl,
                        item.dataSaverVideoUrl,
                        imageQualityMode,
                        networkTier,
                        forcedOriginalIds,
                    ) {
                        AdaptiveImageQualitySelector.selectVideoUrl(
                            media = item,
                            mode = imageQualityMode,
                            networkTier = networkTier,
                            forceOriginal = item.id in forcedOriginalIds,
                        )
                    }
                    LightboxVideoPage(
                        media = item,
                        videoUrl = playbackVideoUrl,
                        isActivePage = isActivePage,
                        chromeVisible = chromeVisible,
                        hasBottomPagerOverlay = mediaList.size > 1 || item.altText.isNotBlank(),
                        onToggleChrome = {
                            chromeVisible = !chromeVisible
                            if (chromeVisible) bumpChromeTimer()
                        },
                        onChromeInteraction = bumpChromeTimer,
                        onScrubbingChanged = { scrubbing ->
                            isVideoScrubbing = scrubbing
                            if (!scrubbing && chromeVisible) bumpChromeTimer()
                        },
                        onVerticalDrag = { dy ->
                            scope.launch { dismissDragY.snapTo(dismissDragY.value + dy) }
                        },
                        onVerticalDragEnd = {
                            scope.launch {
                                if (abs(dismissDragY.value) >= dismissThresholdPx) {
                                    onDismiss()
                                } else {
                                    dismissDragY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 420f))
                                }
                            }
                        },
                    )
                } else {
                    val isForcedOriginal = item.id in forcedOriginalIds
                    LightboxPhotoPage(
                        media = item,
                        isActivePage = isActivePage,
                        forceOriginal = isForcedOriginal,
                        onZoomChanged = { zoomed ->
                            if (isActivePage) isCurrentPageZoomed = zoomed
                        },
                        onSingleTap = {
                            chromeVisible = !chromeVisible
                            if (chromeVisible) bumpChromeTimer()
                        },
                        onVerticalDrag = { dy ->
                            scope.launch { dismissDragY.snapTo(dismissDragY.value + dy) }
                        },
                        onVerticalDragEnd = {
                            scope.launch {
                                if (abs(dismissDragY.value) >= dismissThresholdPx) {
                                    onDismiss()
                                } else {
                                    dismissDragY.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 420f))
                                }
                            }
                        },
                    )
                }
            }

            // Top Chrome Scrim + Action Row
            AnimatedVisibility(
                visible = chromeVisible && dragProgress < 0.2f,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.55f),
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("close_lightbox_button"),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Close media viewer",
                                    tint = Color.White,
                                )
                            }
                        }

                        // Counter + media type pill
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val currentPhotoVariant = remember(
                                current.id,
                                current.url,
                                current.thumbnailUrl,
                                imageQualityMode,
                                networkTier,
                                forcedOriginalIds,
                            ) {
                                if (current.type == MediaType.PHOTO) {
                                    AdaptiveImageQualitySelector.selectVariant(
                                        rawUrl = current.url.ifBlank { current.thumbnailUrl },
                                        usage = ImageUsage.EXPANDED_LIGHTBOX,
                                        mode = imageQualityMode,
                                        networkTier = networkTier,
                                        forceOriginal = current.id in forcedOriginalIds,
                                    )
                                } else {
                                    null
                                }
                            }
                            val (_, isVideoCappedAt1080p) = remember(
                                current.id,
                                current.videoUrl,
                                current.dataSaverVideoUrl,
                                imageQualityMode,
                                networkTier,
                                forcedOriginalIds,
                            ) {
                                if (current.type == MediaType.VIDEO) {
                                    AdaptiveImageQualitySelector.selectVideoUrl(
                                        media = current,
                                        mode = imageQualityMode,
                                        networkTier = networkTier,
                                        forceOriginal = current.id in forcedOriginalIds,
                                    )
                                } else {
                                    "" to false
                                }
                            }
                            val canUpgradePhotoToOrig = current.type == MediaType.PHOTO &&
                                currentPhotoVariant?.variantLabel != "ORIG" &&
                                (current.url.contains("pbs.twimg.com/", ignoreCase = true) ||
                                    current.thumbnailUrl.contains("pbs.twimg.com/", ignoreCase = true))
                            val canUpgradeQuality = canUpgradePhotoToOrig || isVideoCappedAt1080p
                            val typeBadge = when (current.type) {
                                MediaType.GIF -> "GIF"
                                MediaType.VIDEO -> if (isVideoCappedAt1080p) "1080p · MAX" else "VIDEO"
                                MediaType.PHOTO -> when {
                                    canUpgradePhotoToOrig -> "${currentPhotoVariant?.variantLabel ?: "1080p"} · ORIG"
                                    else -> currentPhotoVariant?.variantLabel ?: "ORIG"
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .let { base ->
                                        if (canUpgradeQuality) {
                                            base.clickable {
                                                bumpChromeTimer()
                                                forcedOriginalIds = forcedOriginalIds + current.id
                                            }
                                        } else {
                                            base
                                        }
                                    }
                                    .testTag("lightbox_quality_badge"),
                            ) {
                                Text(
                                    text = typeBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                            if (mediaList.size > 1) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.55f),
                                ) {
                                    Text(
                                        text = "${pagerState.currentPage + 1} / ${mediaList.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }

                        // Save & Share buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onSaveMedia != null) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.55f),
                                ) {
                                    IconButton(
                                        onClick = {
                                            bumpChromeTimer()
                                            if (!isSaving) {
                                                scope.launch {
                                                    isSaving = true
                                                    onSaveMedia(current)
                                                        .onSuccess { onShowMessage("Saved to Gallery") }
                                                        .onFailure { err ->
                                                            onShowMessage(err.message ?: "Failed to save media")
                                                        }
                                                    isSaving = false
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("lightbox_save_button"),
                                    ) {
                                        if (isSaving) {
                                            IlayLoadingIndicator(
                                                size = 18.dp,
                                                color = Color.White,
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.Download,
                                                contentDescription = "Save media to gallery",
                                                tint = Color.White,
                                            )
                                        }
                                    }
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.55f),
                            ) {
                                IconButton(
                                    onClick = {
                                        bumpChromeTimer()
                                        val shareTarget = current.videoUrl?.takeIf { it.isNotBlank() }
                                            ?: current.url.ifBlank { current.thumbnailUrl }
                                        if (shareTarget.isNotBlank()) {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareTarget)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share media"))
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("lightbox_share_button"),
                                ) {
                                    Icon(
                                        Icons.Outlined.Share,
                                        contentDescription = "Share media link",
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Chrome Scrim: Page Indicator Dots + Expandable ALT Text
            AnimatedVisibility(
                visible = chromeVisible && dragProgress < 0.2f && (mediaList.size > 1 || current.altText.isNotBlank()),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (current.altText.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Black.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    altExpanded = !altExpanded
                                    bumpChromeTimer()
                                }
                                .animateContentSize(IlayMotion.spatialDefault())
                                .testTag("lightbox_alt_card"),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color.White.copy(alpha = 0.2f),
                                    ) {
                                        Text(
                                            text = "ALT",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    Text(
                                        text = if (altExpanded) "Tap to collapse description" else "Image description · Tap to expand",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.72f),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = current.altText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                    maxLines = if (altExpanded) 12 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (altExpanded) {
                                        Modifier.verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                    }

                    // M3E expanding pill page dots
                    if (mediaList.size > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(mediaList.size) { idx ->
                                val selected = idx == pagerState.currentPage
                                val dotWidth by animateDpAsState(
                                    targetValue = if (selected) 22.dp else 7.dp,
                                    animationSpec = IlayMotion.spatialDefault(),
                                    label = "pagerDotW",
                                )
                                Box(
                                    modifier = Modifier
                                        .height(7.dp)
                                        .width(dotWidth)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) Color.White else Color.White.copy(alpha = 0.38f)
                                        )
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
private fun LightboxPhotoPage(
    media: MediaAttachment,
    isActivePage: Boolean,
    forceOriginal: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onSingleTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
) {
    val context = LocalContext.current
    val imageQualityMode = LocalImageQualityMode.current
    val scope = rememberCoroutineScope()
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val scaleAnim = remember(media.id) { Animatable(1f) }
    var offsetX by remember(media.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(media.id) { mutableFloatStateOf(0f) }

    // Defer full-resolution download until this photo page is actually viewed by the user.
    var shouldLoadFullRes by remember(media.id) { mutableStateOf(isActivePage) }
    var isLoadingFullRes by remember(media.id, forceOriginal) { mutableStateOf(false) }

    LaunchedEffect(isActivePage) {
        if (isActivePage) {
            shouldLoadFullRes = true
        } else if (scaleAnim.value > 1f) {
            scaleAnim.snapTo(1f)
            offsetX = 0f
            offsetY = 0f
        }
    }

    fun clampOffset(rawX: Float, rawY: Float, currentScale: Float): Offset {
        if (currentScale <= 1f || containerSize == IntSize.Zero) return Offset.Zero
        val maxX = ((containerSize.width * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
        val maxY = ((containerSize.height * (currentScale - 1f)) / 2f).coerceAtLeast(0f)
        return Offset(
            x = rawX.coerceIn(-maxX, maxX),
            y = rawY.coerceIn(-maxY, maxY),
        )
    }

    val rawMediaUrl = media.url.ifBlank { media.thumbnailUrl }
    val rawThumbUrl = media.thumbnailUrl.ifBlank { media.url }
    val imageRequest = remember(
        rawMediaUrl,
        rawThumbUrl,
        context,
        imageQualityMode,
        shouldLoadFullRes,
        forceOriginal,
    ) {
        if (shouldLoadFullRes) {
            IlayImageCache.lightboxImageRequest(
                context = context,
                highResUrl = rawMediaUrl,
                thumbnailUrl = rawThumbUrl,
                mode = imageQualityMode,
                forceOriginal = forceOriginal,
            )
        } else {
            // Adjacent pager pages reuse the already-cached lower-res timeline preview until swiped into view
            IlayImageCache.cardMediaRequest(
                context = context,
                rawUrl = rawThumbUrl,
                compact = false,
                mode = imageQualityMode,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(media.id) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { tapOffset ->
                        scope.launch {
                            if (scaleAnim.value > 1.15f) {
                                offsetX = 0f
                                offsetY = 0f
                                onZoomChanged(false)
                                scaleAnim.animateTo(1f, IlayMotion.spatialDefault())
                            } else {
                                val targetScale = 2.5f
                                val centerX = containerSize.width / 2f
                                val centerY = containerSize.height / 2f
                                val clamped = clampOffset(
                                    rawX = (centerX - tapOffset.x) * (targetScale - 1f),
                                    rawY = (centerY - tapOffset.y) * (targetScale - 1f),
                                    currentScale = targetScale,
                                )
                                offsetX = clamped.x
                                offsetY = clamped.y
                                onZoomChanged(true)
                                scaleAnim.animateTo(targetScale, IlayMotion.spatialDefault())
                            }
                        }
                    },
                )
            }
            .pointerInput(media.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var verticalDismissDragging = false
                    var lockedHorizontalForPager = false
                    var accumDx = 0f
                    var accumDy = 0f

                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (pointerCount >= 2 || scaleAnim.value > 1.05f) {
                            // Multi-touch pinch or zoomed panning: consume events so pager does not steal them
                            val newScale = (scaleAnim.value * zoomChange).coerceIn(1f, 5f)
                            scope.launch { scaleAnim.snapTo(newScale) }
                            val clamped = clampOffset(
                                rawX = offsetX + panChange.x,
                                rawY = offsetY + panChange.y,
                                currentScale = newScale,
                            )
                            offsetX = clamped.x
                            offsetY = clamped.y
                            onZoomChanged(newScale > 1.05f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pointerCount == 1 && panChange != Offset.Zero) {
                            accumDx += panChange.x
                            accumDy += panChange.y
                            if (!verticalDismissDragging && !lockedHorizontalForPager) {
                                if (abs(accumDy) > 14f && abs(accumDy) > abs(accumDx) * 1.25f) {
                                    verticalDismissDragging = true
                                } else if (abs(accumDx) > 14f) {
                                    lockedHorizontalForPager = true
                                }
                            }
                            if (verticalDismissDragging) {
                                onVerticalDrag(panChange.y)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (verticalDismissDragging) {
                        onVerticalDragEnd()
                    }
                    if (scaleAnim.value <= 1.05f) {
                        offsetX = 0f
                        offsetY = 0f
                        onZoomChanged(false)
                        if (scaleAnim.value != 1f) {
                            scope.launch { scaleAnim.animateTo(1f, IlayMotion.spatialDefault()) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // AsyncImage renders the cached lower-res timeline preview on frame 0 via placeholderMemoryCacheKey
        // while downloading the full-resolution variant in the background.
        AsyncImage(
            model = imageRequest,
            contentDescription = media.altText.ifBlank { "Full-screen photo" },
            contentScale = ContentScale.Fit,
            onLoading = { if (shouldLoadFullRes) isLoadingFullRes = true },
            onSuccess = { isLoadingFullRes = false },
            onError = { isLoadingFullRes = false },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scaleAnim.value,
                    scaleY = scaleAnim.value,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )

        AnimatedVisibility(
            visible = isLoadingFullRes && isActivePage,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 76.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.68f),
                modifier = Modifier.testTag("lightbox_loading_full_res_pill"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    IlayLoadingIndicator(
                        size = 16.dp,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.lightbox_loading_full_res),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun LightboxVideoPage(
    media: MediaAttachment,
    videoUrl: String,
    isActivePage: Boolean,
    chromeVisible: Boolean,
    hasBottomPagerOverlay: Boolean,
    onToggleChrome: () -> Unit,
    onChromeInteraction: () -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
) {
    var isBuffering by remember(media.id) { mutableStateOf(true) }
    var videoFailed by remember(media.id) { mutableStateOf(false) }
    var isPlaying by remember(media.id) { mutableStateOf(true) }
    var hasCompleted by remember(media.id) { mutableStateOf(false) }
    var isMuted by remember(media.id) { mutableStateOf(media.type == MediaType.GIF) }
    var currentPositionMs by remember(media.id) { mutableLongStateOf(0L) }
    var durationMs by remember(media.id) { mutableLongStateOf(media.durationMs) }
    var isScrubbing by remember(media.id) { mutableStateOf(false) }
    var scrubFraction by remember(media.id) { mutableFloatStateOf(0f) }
    var videoViewRef by remember(media.id) { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember(media.id) { mutableStateOf<MediaPlayer?>(null) }

    val togglePlayPause: () -> Unit = {
        onChromeInteraction()
        val vv = videoViewRef
        if (vv != null) {
            if (isPlaying) {
                runCatching {
                    currentPositionMs = vv.currentPosition.toLong()
                    vv.pause()
                }
                isPlaying = false
            } else {
                runCatching {
                    if (hasCompleted) {
                        currentPositionMs = 0L
                        vv.seekTo(0)
                        hasCompleted = false
                    }
                    vv.start()
                }
                isPlaying = true
            }
        }
    }

    // Pause video cleanly when swiping to another page
    LaunchedEffect(isActivePage) {
        val vv = videoViewRef
        if (vv != null) {
            if (isActivePage && isPlaying && !hasCompleted && !videoFailed) {
                runCatching { vv.start() }
            } else if (!isActivePage) {
                runCatching {
                    val pos = vv.currentPosition.toLong()
                    if (pos > 0L) currentPositionMs = pos
                    if (vv.isPlaying) vv.pause()
                }
            }
        }
    }

    // Apply mute state dynamically to live MediaPlayer
    LaunchedEffect(isMuted, mediaPlayerRef) {
        val vol = if (isMuted) 0f else 1f
        runCatching { mediaPlayerRef?.setVolume(vol, vol) }
    }

    // Poll playback position & duration for the live progress bar and scrubber
    LaunchedEffect(media.id, isActivePage, isPlaying, videoFailed) {
        while (isActivePage && isPlaying && !videoFailed) {
            val vv = videoViewRef
            if (vv != null && !isScrubbing) {
                runCatching {
                    val d = vv.duration.toLong()
                    if (d > 0L) durationMs = d
                    val p = vv.currentPosition.toLong()
                    if (p >= 0L) currentPositionMs = p.coerceAtMost(durationMs.coerceAtLeast(p))
                }
            }
            delay(200L)
        }
    }

    DisposableEffect(media.id) {
        onDispose {
            runCatching {
                val pos = videoViewRef?.currentPosition?.toLong() ?: 0L
                if (pos > 0L) currentPositionMs = pos
                videoViewRef?.stopPlayback()
            }
            videoViewRef = null
            mediaPlayerRef = null
        }
    }

    val liveFraction = if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderFraction = if (isScrubbing) scrubFraction else liveFraction
    val displayedPositionMs = if (isScrubbing && durationMs > 0L) {
        (scrubFraction * durationMs).toLong()
    } else {
        currentPositionMs
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(media.id) {
                detectTapGestures(onTap = { onToggleChrome() })
            }
            .pointerInput(media.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var verticalDragging = false
                    var accumDx = 0f
                    var accumDy = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        if (event.changes.count { it.pressed } == 1 && pan != Offset.Zero) {
                            accumDx += pan.x
                            accumDy += pan.y
                            if (!verticalDragging && abs(accumDy) > 16f && abs(accumDy) > abs(accumDx) * 1.3f) {
                                verticalDragging = true
                            }
                            if (verticalDragging) {
                                onVerticalDrag(pan.y)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (verticalDragging) onVerticalDragEnd()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!videoFailed && videoUrl.isNotBlank()) {
            AndroidView(
                factory = { ctx ->
                    val container = FrameLayout(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        clipChildren = true
                        clipToPadding = true
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                    val videoView = VideoView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        )
                        videoViewRef = this
                        tag = videoUrl
                        setVideoURI(videoUrl.toUri())
                        setOnPreparedListener { mp ->
                            mediaPlayerRef = mp
                            mp.isLooping = media.type == MediaType.GIF
                            runCatching { mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
                            val vol = if (isMuted) 0f else 1f
                            mp.setVolume(vol, vol)
                            val d = mp.duration.toLong()
                            if (d > 0L) durationMs = d
                            if (currentPositionMs > 0L) {
                                runCatching { seekTo(currentPositionMs.toInt()) }
                            }
                            isBuffering = false
                            if (isActivePage && isPlaying && !hasCompleted) {
                                start()
                            }
                        }
                        setOnCompletionListener { mp ->
                            if (media.type != MediaType.GIF) {
                                isPlaying = false
                                hasCompleted = true
                                val d = mp.duration.toLong()
                                if (d > 0L) {
                                    durationMs = d
                                    currentPositionMs = d
                                }
                            }
                        }
                        setOnInfoListener { _, what, _ ->
                            when (what) {
                                MediaPlayer.MEDIA_INFO_BUFFERING_START -> isBuffering = true
                                MediaPlayer.MEDIA_INFO_BUFFERING_END,
                                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> isBuffering = false
                            }
                            false
                        }
                        setOnErrorListener { _, _, _ ->
                            isBuffering = false
                            videoFailed = true
                            true
                        }
                    }
                    container.addView(videoView)
                    container
                },
                update = { container ->
                    val videoView = container.getChildAt(0) as? VideoView ?: return@AndroidView
                    if (videoView.tag != videoUrl) {
                        runCatching {
                            val pos = videoView.currentPosition.toLong()
                            if (pos > 0L) currentPositionMs = pos
                        }
                        isBuffering = true
                        videoFailed = false
                        videoView.tag = videoUrl
                        videoView.setVideoURI(videoUrl.toUri())
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            )

            if (isBuffering) {
                IlayLoadingIndicator(
                    size = 40.dp,
                    color = Color.White,
                )
            }

            // Always-visible sleek 3dp playback progress bar at the bottom when chrome is hidden
            if (!chromeVisible && !hasCompleted && !isBuffering && durationMs > 0L) {
                LinearProgressIndicator(
                    progress = { liveFraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .testTag("lightbox_video_mini_progress"),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Butt,
                    drawStopIndicator = {},
                )
            }

            // Custom sleek Video/GIF controls overlay + seekable progress scrubber
            AnimatedVisibility(
                visible = (chromeVisible || hasCompleted) && !isBuffering,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Centered Play/Pause/Replay pill
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.58f),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        IconButton(
                            onClick = togglePlayPause,
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("lightbox_video_play_pause"),
                        ) {
                            Icon(
                                imageVector = when {
                                    isPlaying -> Icons.Filled.Pause
                                    hasCompleted -> Icons.Filled.Replay
                                    else -> Icons.Filled.PlayArrow
                                },
                                contentDescription = when {
                                    isPlaying -> "Pause video"
                                    hasCompleted -> "Replay video"
                                    else -> "Play video"
                                },
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }

                    // Bottom Video Progress Bar & Controls Strip
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                )
                            )
                            .navigationBarsPadding()
                            .padding(
                                start = 14.dp,
                                end = 14.dp,
                                top = 16.dp,
                                bottom = if (hasBottomPagerOverlay) 56.dp else 14.dp,
                            ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            IconButton(
                                onClick = togglePlayPause,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("lightbox_video_bar_play_pause"),
                            ) {
                                Icon(
                                    imageVector = when {
                                        isPlaying -> Icons.Filled.Pause
                                        hasCompleted -> Icons.Filled.Replay
                                        else -> Icons.Filled.PlayArrow
                                    },
                                    contentDescription = when {
                                        isPlaying -> "Pause"
                                        hasCompleted -> "Replay"
                                        else -> "Play"
                                    },
                                    tint = Color.White,
                                )
                            }

                            Text(
                                text = formatMediaDuration(displayedPositionMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                            )

                            Slider(
                                value = sliderFraction,
                                onValueChange = { fraction ->
                                    if (!isScrubbing) {
                                        isScrubbing = true
                                        onScrubbingChanged(true)
                                    }
                                    scrubFraction = fraction.coerceIn(0f, 1f)
                                },
                                onValueChangeFinished = {
                                    val targetMs = (scrubFraction * durationMs).toLong().coerceAtLeast(0L)
                                    currentPositionMs = targetMs
                                    if (durationMs > 0L && targetMs < durationMs - 250L) {
                                        hasCompleted = false
                                    }
                                    runCatching { videoViewRef?.seekTo(targetMs.toInt()) }
                                    isScrubbing = false
                                    onScrubbingChanged(false)
                                },
                                enabled = durationMs > 0L,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.30f),
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("lightbox_video_scrubber"),
                            )

                            Text(
                                text = formatMediaDuration(durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.80f),
                            )

                            if (media.type == MediaType.VIDEO) {
                                IconButton(
                                    onClick = {
                                        onChromeInteraction()
                                        isMuted = !isMuted
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("lightbox_video_mute_toggle"),
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (isMuted) "Unmute video" else "Mute video",
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // High-res thumbnail fallback when video stream is unavailable
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = media.thumbnailUrl.ifBlank { media.url },
                        contentDescription = media.altText.ifBlank { "Video preview" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.72f),
                    ) {
                        Text(
                            text = "Video stream unavailable",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun formatMediaDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
