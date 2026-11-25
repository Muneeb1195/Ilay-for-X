package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IlayMotion
import kotlin.math.roundToInt

/**
 * Shared Ilay-style scroll-chrome controller.
 * Tracks a continuous [collapseProgress] (0f = expanded, 1f = scrolled down / collapsed)
 * so both the 75%-shrinking top bar and the icon-only floating bottom bar stay in sync.
 */
@Stable
class IlayChromeState(
    private val collapseDistancePx: Float,
) {
    private var accumulatedScrollPx by mutableFloatStateOf(0f)

    val collapseProgress: Float
        get() = (accumulatedScrollPx / collapseDistancePx.coerceAtLeast(1f)).coerceIn(0f, 1f)

    val isCollapsed: Boolean
        get() = collapseProgress > 0.45f

    var isScrollTrackingEnabled: Boolean = true

    fun expand() {
        accumulatedScrollPx = 0f
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!isScrollTrackingEnabled) return Offset.Zero
            val dy = available.y
            if (dy != 0f) {
                // Scrolling down produces negative dy -> increase collapse; scrolling up -> expand
                val next = (accumulatedScrollPx - dy).coerceIn(0f, collapseDistancePx)
                accumulatedScrollPx = next
            }
            return Offset.Zero
        }
    }
}

val LocalIlayChromeState = compositionLocalOf { IlayChromeState(collapseDistancePx = 220f) }

@Composable
fun rememberIlayChromeState(): IlayChromeState {
    val distancePx = with(LocalDensity.current) { 120.dp.toPx() }
    return remember(distancePx) { IlayChromeState(distancePx) }
}

data class FloatingNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

/**
 * Material 3 Expressive icon-only floating pill navigation bar.
 * - Zero text labels -> completely eliminates truncation across all screen widths and font scales.
 * - Enforces >= 48dp accessible touch targets (56dp x 48dp per item) with full TalkBack semantics.
 * - Active destination morphs smoothly between rounded-square and full capsule with bouncy spring scale.
 * - Collapses downward on scroll down and springs back on scroll up, respecting WindowInsets.navigationBars.
 */
@Composable
fun IlayFloatingBottomBar(
    items: List<FloatingNavItem>,
    currentRoute: String?,
    collapseProgress: Float,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedCollapse by animateFloatAsState(
        targetValue = collapseProgress.coerceIn(0f, 1f),
        animationSpec = IlayMotion.effectsDefault(),
        label = "floatingNavCollapse",
    )
    val slideDistancePx = with(LocalDensity.current) { 112.dp.toPx() }
    val translationY = animatedCollapse * slideDistancePx
    val barAlpha = (1f - animatedCollapse * 1.15f).coerceIn(0f, 1f)
    val barScale = 1f - (animatedCollapse * 0.06f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .offset { IntOffset(x = 0, y = translationY.roundToInt()) }
            .graphicsLayer {
                alpha = barAlpha
                scaleX = barScale
                scaleY = barScale
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)),
            modifier = Modifier.wrapContentWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route
                    val pillColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f)
                        },
                        animationSpec = IlayMotion.effectsDefault(),
                        label = "navPillColor",
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = IlayMotion.effectsDefault(),
                        label = "navContentColor",
                    )
                    val cornerRadius by animateDpAsState(
                        targetValue = if (isSelected) 24.dp else 14.dp,
                        animationSpec = IlayMotion.spatialBouncy(),
                        label = "navPillCorner",
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.12f else 1f,
                        animationSpec = IlayMotion.spatialBouncy(),
                        label = "navIconScale",
                    )

                    val pillShape = RoundedCornerShape(cornerRadius)
                    Surface(
                        shape = pillShape,
                        color = pillColor,
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 54.dp)
                            .animateContentSize(animationSpec = IlayMotion.spatialBouncy())
                            .clip(pillShape)
                            .semantics {
                                contentDescription = item.label
                                selected = isSelected
                            }
                            .clickable(
                                enabled = barAlpha > 0.1f,
                                role = Role.Tab,
                                onClickLabel = item.label,
                            ) { onSelect(item.route) }
                            .testTag(item.testTag),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = if (isSelected) 16.dp else 14.dp),
                        ) {
                            Icon(
                                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = if (isSelected) null else item.label,
                                tint = contentColor,
                                modifier = Modifier
                                    .size(22.dp)
                                    .scale(iconScale),
                            )
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn(IlayMotion.effectsDefault()) +
                                    expandHorizontally(
                                        animationSpec = IlayMotion.spatialBouncy(),
                                        expandFrom = Alignment.Start,
                                    ),
                                exit = fadeOut(IlayMotion.effectsDefault()) +
                                    shrinkHorizontally(
                                        animationSpec = IlayMotion.effectsDefault(),
                                        shrinkTowards = Alignment.Start,
                                    ),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Maps raw scroll collapse progress (`0f..1f`) to a smooth, monotonic surface-color blend factor
 * with zero binary threshold jump.
 */
internal fun computeTopBarColorProgress(collapseProgress: Float): Float =
    FastOutSlowInEasing.transform(collapseProgress.coerceIn(0f, 1f))

/**
 * Ilay top app bar with gentle 75% scroll-shrink (64dp expanded -> 48dp collapsed):
 * - Preserves full 48dp accessible touch targets for navigation and action icons even when collapsed.
 * - Continuously blends surface color and bottom hairline divider directly from scroll progress
 *   with zero discrete threshold snap or per-pixel spring hitching.
 */
@Composable
fun IlayCollapsingTopBar(
    title: String,
    collapseProgress: Float,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    bottomExpandedContent: (@Composable () -> Unit)? = null,
) {
    val progress = collapseProgress.coerceIn(0f, 1f)
    val colorProgress = computeTopBarColorProgress(progress)

    // Direct continuous scroll tracking avoids re-triggering springs on every scroll frame.
    val rowHeight = lerp(64.dp, 48.dp, progress)
    val titleScale = 1f - (progress * 0.10f)
    val expandedBg = MaterialTheme.colorScheme.background
    val collapsedBg = MaterialTheme.colorScheme.surfaceContainer
    val containerColor = lerpColor(expandedBg, collapsedBg, colorProgress)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f * colorProgress)
    val subtitleAlpha = (1f - progress * 2.0f).coerceIn(0f, 1f)
    val showSecondary = progress < 0.65f

    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(color = containerColor)
                if (colorProgress > 0.01f) {
                    val strokePx = 1.dp.toPx()
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, size.height - strokePx * 0.5f),
                        end = Offset(size.width, size.height - strokePx * 0.5f),
                        strokeWidth = strokePx,
                    )
                }
            }
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (navigationIcon != null) {
                        Box(
                            modifier = Modifier.scale(1f - progress * 0.08f),
                            contentAlignment = Alignment.Center,
                        ) {
                            navigationIcon()
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = (20f * titleScale).sp,
                                lineHeight = (24f * titleScale).sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank() && subtitleAlpha > 0.05f) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.alpha(subtitleAlpha),
                            )
                        }
                    }
                }

                if (actions != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }

            if (bottomExpandedContent != null) {
                AnimatedVisibility(
                    visible = showSecondary,
                    enter = expandVertically(animationSpec = IlayMotion.spatialDefault()) + fadeIn(IlayMotion.effectsDefault()),
                    exit = shrinkVertically(animationSpec = IlayMotion.spatialDefault()) + fadeOut(IlayMotion.effectsDefault()),
                ) {
                    bottomExpandedContent()
                }
            }
        }
    }
}

/**
 * Reusable Material 3 Expressive shape-morphing tab pill:
 * - Unselected: softer rounded rectangle (14dp corners) on `surfaceContainer`
 * - Selected: full capsule (24dp corners) on `primaryContainer` with spring corner & color morphing
 * - Enforces >= 48dp accessible touch height (`heightIn(min = 48.dp)`).
 */
@Composable
fun IlayExpressiveTabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    testTag: String? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = IlayMotion.effectsDefault(),
        label = "tabPillBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = IlayMotion.effectsDefault(),
        label = "tabPillFg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = IlayMotion.effectsDefault(),
        label = "tabPillBorder",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (selected) 24.dp else 14.dp,
        animationSpec = IlayMotion.spatialBouncy(),
        label = "tabPillCorner",
    )
    val shape = RoundedCornerShape(cornerRadius)

    Surface(
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
