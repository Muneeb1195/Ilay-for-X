package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.theme.IlayMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Parametric polar-harmonic shape descriptor for Material 3 Expressive's morphing LoadingIndicator.
 * Each shape is evaluated smoothly over `theta in [0, 2*PI)` so morphing between consecutive
 * expressive silhouettes (SoftBurst -> Clover -> Pentagon -> Pill -> Flower -> Cookie -> Diamond)
 * is completely continuous with zero polygon-vertex popping.
 */
private data class ExpressiveShapeSpec(
    val lobes: Float,
    val amplitude: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val phaseOffset: Float = 0f,
    val harmonic2Lobes: Float = 0f,
    val harmonic2Amp: Float = 0f,
)

private val MD3E_SHAPE_SEQUENCE: List<ExpressiveShapeSpec> = listOf(
    // 1. Soft Burst (10-point expressive scallop)
    ExpressiveShapeSpec(lobes = 10f, amplitude = 0.14f),
    // 2. 4-Sided Clover / Cookie
    ExpressiveShapeSpec(lobes = 4f, amplitude = 0.21f, phaseOffset = (PI / 4.0).toFloat()),
    // 3. Soft Pentagon
    ExpressiveShapeSpec(lobes = 5f, amplitude = 0.13f, harmonic2Lobes = 10f, harmonic2Amp = 0.025f),
    // 4. Expressive Pill / Capsule
    ExpressiveShapeSpec(lobes = 2f, amplitude = 0.16f, scaleX = 1.12f, scaleY = 0.76f),
    // 5. Sunny / 8-Petal Flower
    ExpressiveShapeSpec(lobes = 8f, amplitude = 0.16f),
    // 6. Rounded 6-Sided Cookie
    ExpressiveShapeSpec(lobes = 6f, amplitude = 0.15f),
    // 7. Expressive Soft Squircle / Diamond
    ExpressiveShapeSpec(lobes = 4f, amplitude = 0.14f, scaleX = 0.96f, scaleY = 1.06f),
)

private const val MORPH_STEP_DURATION_MS = 650
private const val SAMPLE_POINTS = 72

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun sampleShapeRadius(spec: ExpressiveShapeSpec, theta: Float): Pair<Float, Float> {
    val baseR = 0.84f +
        spec.amplitude * cos(spec.lobes * theta + spec.phaseOffset) +
        spec.harmonic2Amp * cos(spec.harmonic2Lobes * theta)
    val x = baseR * spec.scaleX * cos(theta)
    val y = baseR * spec.scaleY * sin(theta)
    return x to y
}

private fun DrawScope.drawMorphingExpressiveShape(
    path: Path,
    fromSpec: ExpressiveShapeSpec,
    toSpec: ExpressiveShapeSpec,
    morphFraction: Float,
    rotationDegrees: Float,
    color: Color,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = (minOf(size.width, size.height) / 2f) * 0.92f
    if (radius <= 0f) return

    path.reset()
    val twoPi = (2.0 * PI).toFloat()
    for (i in 0 until SAMPLE_POINTS) {
        val theta = (i.toFloat() / SAMPLE_POINTS.toFloat()) * twoPi
        val (x0, y0) = sampleShapeRadius(fromSpec, theta)
        val (x1, y1) = sampleShapeRadius(toSpec, theta)
        val px = cx + lerpFloat(x0, x1, morphFraction) * radius
        val py = cy + lerpFloat(y0, y1, morphFraction) * radius
        if (i == 0) {
            path.moveTo(px, py)
        } else {
            path.lineTo(px, py)
        }
    }
    path.close()

    rotate(degrees = rotationDegrees) {
        drawPath(path = path, color = color)
    }
}

/**
 * Newer Material 3 Expressive (MD3E) shape-morphing Loading Indicator.
 * Continuously morphs through organic geometric silhouettes with fluid rotational momentum,
 * replacing the legacy spinning arc indicator for indeterminate loading states.
 */
@Composable
fun IlayLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescriptionText: String = "Loading",
) {
    val shapeCount = MD3E_SHAPE_SEQUENCE.size
    val totalCycleMs = shapeCount * MORPH_STEP_DURATION_MS
    val infiniteTransition = rememberInfiniteTransition(label = "md3eLoadingIndicator")

    val cycleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = shapeCount.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = totalCycleMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "md3eMorphCycle",
    )

    val path = remember { Path() }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = contentDescriptionText
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        val rawIndex = floor(cycleProgress).toInt()
        val fromIndex = ((rawIndex % shapeCount) + shapeCount) % shapeCount
        val toIndex = (fromIndex + 1) % shapeCount
        val localFraction = (cycleProgress - rawIndex).coerceIn(0f, 1f)
        val easedMorph = FastOutSlowInEasing.transform(localFraction)

        // Subtle spring-like quarter-turn cadence synced with each morph transition
        val rotationDegrees = (rawIndex * 90f) + (easedMorph * 90f) + (cycleProgress * 18f)

        drawMorphingExpressiveShape(
            path = path,
            fromSpec = MD3E_SHAPE_SEQUENCE[fromIndex],
            toSpec = MD3E_SHAPE_SEQUENCE[toIndex],
            morphFraction = easedMorph,
            rotationDegrees = rotationDegrees,
            color = color,
        )
    }
}

/**
 * Material 3 Expressive Contained Loading Indicator: houses the morphing MD3E shape inside
 * a circular Monet container (defaults to `primaryContainer` / `onPrimaryContainer` or
 * `tertiaryContainer` / `onTertiaryContainer` for contrasting focal states).
 */
@Composable
fun IlayContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    containerSize: Dp = 48.dp,
    indicatorSize: Dp = 28.dp,
    containerColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    tonalElevation: Dp = 3.dp,
    shadowElevation: Dp = 2.dp,
    contentDescriptionText: String = "Loading",
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        modifier = modifier.size(containerSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            IlayLoadingIndicator(
                size = indicatorSize,
                color = indicatorColor,
                contentDescriptionText = contentDescriptionText,
            )
        }
    }
}

/**
 * Material 3 Expressive Pull-to-Refresh Loading Indicator.
 * Tracks drag distance (`state.distanceFraction`) to scale, rotate, and morph the shape while
 * pulling down, then transitions smoothly into the continuous MD3E morphing animation when refreshing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IlayPullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    maxDropDistance: Dp = 76.dp,
) {
    val pullFraction = state.distanceFraction.coerceIn(0f, 1.35f)
    val visible = isRefreshing || pullFraction > 0.04f
    if (!visible) return

    val targetScale = if (isRefreshing) 1f else pullFraction.coerceIn(0f, 1f)
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = IlayMotion.spatialBouncy(),
        label = "md3ePtrScale",
    )
    val maxDropPx = with(LocalDensity.current) { maxDropDistance.toPx() }
    val targetOffsetY = if (isRefreshing) {
        maxDropPx * 0.72f
    } else {
        (pullFraction.coerceAtMost(1.25f) * maxDropPx * 0.78f)
    }
    val animatedOffsetY by animateFloatAsState(
        targetValue = targetOffsetY,
        animationSpec = IlayMotion.spatialDefault(),
        label = "md3ePtrOffset",
    )
    val pullAlpha = if (isRefreshing) 1f else (pullFraction * 1.4f).coerceIn(0f, 1f)
    val pullPath = remember { Path() }

    Box(
        modifier = modifier
            .offset { IntOffset(x = 0, y = animatedOffsetY.roundToInt()) }
            .scale(animatedScale.coerceAtLeast(0.01f))
            .alpha(pullAlpha),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isRefreshing) {
                    IlayLoadingIndicator(
                        size = 26.dp,
                        color = indicatorColor,
                        contentDescriptionText = "Refreshing",
                    )
                } else {
                    val clamped = pullFraction.coerceIn(0f, 1f)
                    Canvas(modifier = Modifier.size(26.dp)) {
                        drawMorphingExpressiveShape(
                            path = pullPath,
                            fromSpec = MD3E_SHAPE_SEQUENCE[0],
                            toSpec = MD3E_SHAPE_SEQUENCE[1],
                            morphFraction = clamped,
                            rotationDegrees = clamped * 180f,
                            color = indicatorColor,
                        )
                    }
                }
            }
        }
    }
}
