package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Material 3-compliant vertical scroll indicator for LazyColumn timelines.
 * - Dynamically fades in on active scroll, dwells briefly, and fades out smoothly when idle.
 * - Drawn via drawWithContent for zero layout overhead and zero touch interception.
 * - Computes fractional progress across variable-height tweet cards and respects bottom chrome insets.
 */
fun Modifier.ilayVerticalScrollIndicator(
    state: LazyListState,
    topPadding: Dp = 8.dp,
    bottomPadding: Dp = 96.dp,
    endPadding: Dp = 3.dp,
    thumbWidth: Dp = 4.dp,
    minThumbHeight: Dp = 36.dp,
    thumbColor: Color? = null,
    trackColor: Color? = null,
): Modifier = composed {
    val resolvedThumbColor = thumbColor ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    val resolvedTrackColor = trackColor ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
    val alphaAnim = remember { Animatable(0f) }
    val isScrolling = state.isScrollInProgress

    LaunchedEffect(isScrolling) {
        if (isScrolling) {
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            )
        } else if (alphaAnim.value > 0f) {
            delay(750L)
            alphaAnim.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            )
        }
    }

    drawWithContent {
        drawContent()

        val currentAlpha = alphaAnim.value
        if (currentAlpha <= 0.01f) return@drawWithContent
        if (!state.canScrollForward && !state.canScrollBackward) return@drawWithContent

        val layoutInfo = state.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val totalItems = layoutInfo.totalItemsCount
        if (visibleItems.isEmpty() || totalItems <= 1) return@drawWithContent

        val firstVisible = visibleItems.first()
        val firstFraction = if (firstVisible.size > 0) {
            ((layoutInfo.viewportStartOffset - firstVisible.offset).toFloat() / firstVisible.size.toFloat())
                .coerceIn(0f, 1f)
        } else {
            0f
        }
        val exactFirstIndex = (firstVisible.index + firstFraction).coerceIn(0f, totalItems.toFloat())

        val avgItemSizePx = (visibleItems.sumOf { it.size }.toFloat() / visibleItems.size.coerceAtLeast(1))
            .coerceAtLeast(1f)
        val viewportHeightPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
            .toFloat()
            .coerceAtLeast(1f)
        val visibleSpan = (viewportHeightPx / avgItemSizePx).coerceIn(1f, totalItems.toFloat())
        val scrollableRange = (totalItems.toFloat() - visibleSpan).coerceAtLeast(0.001f)
        val scrollProgress = (exactFirstIndex / scrollableRange).coerceIn(0f, 1f)

        val topPadPx = topPadding.toPx()
        val bottomPadPx = bottomPadding.toPx()
        val endPadPx = endPadding.toPx()
        val thumbWidthPx = thumbWidth.toPx()
        val trackHeightPx = (size.height - topPadPx - bottomPadPx).coerceAtLeast(0f)
        if (trackHeightPx <= minThumbHeight.toPx()) return@drawWithContent

        val proportionalThumbHeight = (visibleSpan / totalItems.toFloat()) * trackHeightPx
        val thumbHeightPx = proportionalThumbHeight.coerceIn(
            minimumValue = minThumbHeight.toPx(),
            maximumValue = trackHeightPx * 0.42f,
        )
        val thumbTopPx = topPadPx + scrollProgress * (trackHeightPx - thumbHeightPx)

        val thumbX = if (layoutDirection == LayoutDirection.Rtl) {
            endPadPx
        } else {
            size.width - thumbWidthPx - endPadPx
        }

        // Subtle Material 3 hairline track behind the thumb
        val railWidthPx = (thumbWidthPx * 0.5f).coerceAtLeast(1.5.dp.toPx())
        val railX = thumbX + (thumbWidthPx - railWidthPx) / 2f
        drawRoundRect(
            color = resolvedTrackColor,
            topLeft = Offset(x = railX, y = topPadPx),
            size = Size(width = railWidthPx, height = trackHeightPx),
            cornerRadius = CornerRadius(railWidthPx / 2f, railWidthPx / 2f),
            alpha = currentAlpha,
        )

        // Capsule thumb indicator
        drawRoundRect(
            color = resolvedThumbColor,
            topLeft = Offset(x = thumbX, y = thumbTopPx),
            size = Size(width = thumbWidthPx, height = thumbHeightPx),
            cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f),
            alpha = currentAlpha,
        )
    }
}
