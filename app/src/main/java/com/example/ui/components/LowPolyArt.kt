package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

enum class LowPolyScene {
    TIMELINE,
    ACTIVITY,
    ARCHIVE,
}

private data class PolyFacet(
    val p1: Pair<Float, Float>,
    val p2: Pair<Float, Float>,
    val p3: Pair<Float, Float>,
    val shadeIndex: Int,
)

/**
 * Pure-Compose Material 3 Expressive low-poly faceted banner artwork.
 * Dynamically derives every triangular facet shade from [MaterialTheme.colorScheme]
 * (AMOLED, Dark, Light, Dynamic Color, and all Accent Presets) with zero raster assets.
 */
@Composable
fun LowPolyBannerArt(
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val palette = remember(
        cs.surfaceContainerLowest,
        cs.surfaceContainerLow,
        cs.surfaceContainerHigh,
        cs.primaryContainer,
        cs.primary,
        cs.secondaryContainer,
        cs.secondary,
        cs.tertiary,
    ) {
        listOf(
            lerp(cs.surfaceContainerLowest, cs.primaryContainer, 0.25f), // 0: deep sky
            lerp(cs.surfaceContainerLow, cs.secondaryContainer, 0.42f),  // 1: upper facet
            lerp(cs.primaryContainer, cs.secondaryContainer, 0.50f),     // 2: mid sky
            lerp(cs.primaryContainer, cs.primary, 0.28f),                // 3: lit sky
            lerp(cs.secondaryContainer, cs.primary, 0.35f),              // 4: ridge shadow
            lerp(cs.primary, cs.primaryContainer, 0.45f),                // 5: ridge lit
            lerp(cs.primary, cs.secondary, 0.35f),                       // 6: crystal highlight
            lerp(cs.surfaceContainerHigh, cs.primaryContainer, 0.55f),   // 7: foreground base
            lerp(cs.primary, cs.tertiary, 0.38f),                        // 8: warm accent facet
        )
    }
    val edgeStrokeColor = cs.onPrimaryContainer.copy(alpha = 0.14f)

    Canvas(
        modifier = modifier.semantics { contentDescription = contentDescriptionText },
    ) {
        drawFacetMesh(BANNER_FACETS, palette, edgeStrokeColor)

        // Subtle floating MD3E crystalline origami prism accents
        drawDiamondPrism(
            center = Offset(size.width * 0.78f, size.height * 0.32f),
            radiusX = size.height * 0.17f,
            radiusY = size.height * 0.22f,
            lightColor = lerp(cs.primary, Color.White, 0.35f).copy(alpha = 0.85f),
            midColor = cs.primary.copy(alpha = 0.78f),
            shadowColor = cs.secondary.copy(alpha = 0.72f),
            strokeColor = edgeStrokeColor,
        )
        drawDiamondPrism(
            center = Offset(size.width * 0.18f, size.height * 0.28f),
            radiusX = size.height * 0.09f,
            radiusY = size.height * 0.12f,
            lightColor = cs.tertiary.copy(alpha = 0.72f),
            midColor = cs.primary.copy(alpha = 0.62f),
            shadowColor = cs.primaryContainer.copy(alpha = 0.80f),
            strokeColor = edgeStrokeColor,
        )
    }
}

/**
 * Pure-Compose Material 3 Expressive low-poly illustration for empty and onboarding states.
 * Adapts smoothly to any container aspect ratio and Material 3 dynamic/accent color scheme.
 */
@Composable
fun LowPolyEmptyStateArt(
    scene: LowPolyScene,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val palette = remember(
        cs.surfaceContainerLow,
        cs.surfaceContainer,
        cs.surfaceContainerHigh,
        cs.primaryContainer,
        cs.primary,
        cs.secondaryContainer,
        cs.secondary,
        cs.tertiary,
    ) {
        listOf(
            lerp(cs.surfaceContainerLow, cs.primaryContainer, 0.22f),  // 0: soft sky backdrop
            lerp(cs.surfaceContainer, cs.secondaryContainer, 0.34f),   // 1: upper left
            lerp(cs.surfaceContainerHigh, cs.primaryContainer, 0.42f), // 2: horizon mist
            lerp(cs.primaryContainer, cs.secondaryContainer, 0.55f),   // 3: far peak
            lerp(cs.primaryContainer, cs.primary, 0.32f),              // 4: mid ridge
            lerp(cs.secondaryContainer, cs.secondary, 0.45f),          // 5: shaded ridge
            lerp(cs.primary, cs.secondary, 0.30f),                     // 6: hero crystal main
            lerp(cs.primary, Color.White, 0.32f),                      // 7: hero crystal lit
            lerp(cs.tertiary, cs.primary, 0.35f),                      // 8: expressive accent
        )
    }
    val edgeStrokeColor = cs.onSurface.copy(alpha = 0.10f)

    Canvas(
        modifier = modifier.semantics { contentDescription = contentDescriptionText },
    ) {
        // 1. Background faceted low-poly terrain mesh
        drawFacetMesh(EMPTY_BG_FACETS, palette, edgeStrokeColor)

        // 2. Scene-specific centerpiece low-poly origami / geometric emblem
        val cx = size.width * 0.5f
        val cy = size.height * 0.47f
        val unit = minOf(size.width, size.height) * 0.36f

        when (scene) {
            LowPolyScene.TIMELINE -> {
                // Low-poly origami skybird / faceted X-wing prism
                drawPolygon(
                    points = listOf(
                        Offset(cx - unit * 1.15f, cy - unit * 0.48f),
                        Offset(cx - unit * 0.10f, cy - unit * 0.12f),
                        Offset(cx - unit * 0.58f, cy + unit * 0.28f),
                    ),
                    fill = palette[7],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(
                        Offset(cx - unit * 0.10f, cy - unit * 0.12f),
                        Offset(cx + unit * 1.18f, cy - unit * 0.62f),
                        Offset(cx + unit * 0.32f, cy + unit * 0.16f),
                    ),
                    fill = palette[6],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(
                        Offset(cx - unit * 0.58f, cy + unit * 0.28f),
                        Offset(cx - unit * 0.10f, cy - unit * 0.12f),
                        Offset(cx + unit * 0.32f, cy + unit * 0.16f),
                        Offset(cx - unit * 0.04f, cy + unit * 0.64f),
                    ),
                    fill = palette[5],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(
                        Offset(cx + unit * 0.32f, cy + unit * 0.16f),
                        Offset(cx + unit * 1.18f, cy - unit * 0.62f),
                        Offset(cx + unit * 0.72f, cy + unit * 0.44f),
                    ),
                    fill = palette[8],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(
                        Offset(cx - unit * 0.04f, cy + unit * 0.64f),
                        Offset(cx + unit * 0.32f, cy + unit * 0.16f),
                        Offset(cx + unit * 0.72f, cy + unit * 0.44f),
                    ),
                    fill = palette[4],
                    stroke = edgeStrokeColor,
                )
            }

            LowPolyScene.ACTIVITY -> {
                // Low-poly faceted resonance bell / signal crystal with radiating shards
                drawDiamondPrism(
                    center = Offset(cx, cy),
                    radiusX = unit * 0.68f,
                    radiusY = unit * 0.86f,
                    lightColor = palette[7],
                    midColor = palette[6],
                    shadowColor = palette[5],
                    strokeColor = edgeStrokeColor,
                )
                // Left & right floating low-poly pulse shards
                drawPolygon(
                    points = listOf(
                        Offset(cx - unit * 1.32f, cy - unit * 0.18f),
                        Offset(cx - unit * 0.88f, cy - unit * 0.46f),
                        Offset(cx - unit * 0.94f, cy + unit * 0.22f),
                    ),
                    fill = palette[8].copy(alpha = 0.88f),
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(
                        Offset(cx + unit * 1.34f, cy + unit * 0.12f),
                        Offset(cx + unit * 0.88f, cy - unit * 0.36f),
                        Offset(cx + unit * 0.92f, cy + unit * 0.40f),
                    ),
                    fill = palette[4].copy(alpha = 0.92f),
                    stroke = edgeStrokeColor,
                )
                drawDiamondPrism(
                    center = Offset(cx + unit * 0.96f, cy - unit * 0.64f),
                    radiusX = unit * 0.20f,
                    radiusY = unit * 0.24f,
                    lightColor = palette[8],
                    midColor = palette[7],
                    shadowColor = palette[6],
                    strokeColor = edgeStrokeColor,
                )
            }

            LowPolyScene.ARCHIVE -> {
                // Low-poly faceted bookmark ribbon prism
                val left = cx - unit * 0.56f
                val right = cx + unit * 0.56f
                val top = cy - unit * 0.78f
                val midY = cy + unit * 0.08f
                val bottom = cy + unit * 0.78f
                val notchY = cy + unit * 0.36f

                drawPolygon(
                    points = listOf(Offset(left, top), Offset(right, top), Offset(cx, midY)),
                    fill = palette[7],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(Offset(left, top), Offset(cx, midY), Offset(left, bottom)),
                    fill = palette[6],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(Offset(right, top), Offset(right, bottom), Offset(cx, midY)),
                    fill = palette[5],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(Offset(left, bottom), Offset(cx, midY), Offset(cx, notchY)),
                    fill = palette[8],
                    stroke = edgeStrokeColor,
                )
                drawPolygon(
                    points = listOf(Offset(right, bottom), Offset(cx, notchY), Offset(cx, midY)),
                    fill = palette[4],
                    stroke = edgeStrokeColor,
                )
            }
        }
    }
}

private fun DrawScope.drawFacetMesh(
    facets: List<PolyFacet>,
    palette: List<Color>,
    strokeColor: Color,
) {
    val w = size.width
    val h = size.height
    val path = Path()
    for (facet in facets) {
        path.reset()
        path.moveTo(facet.p1.first * w, facet.p1.second * h)
        path.lineTo(facet.p2.first * w, facet.p2.second * h)
        path.lineTo(facet.p3.first * w, facet.p3.second * h)
        path.close()
        drawPath(path = path, color = palette[facet.shadeIndex % palette.size])
        drawPath(path = path, color = strokeColor, style = Stroke(width = 1f))
    }
}

private fun DrawScope.drawDiamondPrism(
    center: Offset,
    radiusX: Float,
    radiusY: Float,
    lightColor: Color,
    midColor: Color,
    shadowColor: Color,
    strokeColor: Color,
) {
    val top = Offset(center.x, center.y - radiusY)
    val right = Offset(center.x + radiusX, center.y)
    val bottom = Offset(center.x, center.y + radiusY)
    val left = Offset(center.x - radiusX, center.y)
    val core = Offset(center.x - radiusX * 0.14f, center.y - radiusY * 0.10f)

    drawPolygon(listOf(top, core, left), lightColor, strokeColor)
    drawPolygon(listOf(top, right, core), midColor, strokeColor)
    drawPolygon(listOf(left, core, bottom), midColor, strokeColor)
    drawPolygon(listOf(core, right, bottom), shadowColor, strokeColor)
}

private fun DrawScope.drawPolygon(
    points: List<Offset>,
    fill: Color,
    stroke: Color,
) {
    if (points.size < 3) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
        close()
    }
    drawPath(path = path, color = fill)
    drawPath(path = path, color = stroke, style = Stroke(width = 1.2f))
}

// Seamless Delaunay-style triangular mesh covering [0,1]x[0,1] for wide banner headers
private val BANNER_FACETS = listOf(
    // Sky row
    PolyFacet(0.00f to 0.00f, 0.24f to 0.00f, 0.15f to 0.44f, 0),
    PolyFacet(0.00f to 0.00f, 0.15f to 0.44f, 0.00f to 0.56f, 1),
    PolyFacet(0.24f to 0.00f, 0.50f to 0.00f, 0.36f to 0.36f, 1),
    PolyFacet(0.24f to 0.00f, 0.36f to 0.36f, 0.15f to 0.44f, 2),
    PolyFacet(0.50f to 0.00f, 0.74f to 0.00f, 0.62f to 0.42f, 2),
    PolyFacet(0.50f to 0.00f, 0.62f to 0.42f, 0.36f to 0.36f, 3),
    PolyFacet(0.74f to 0.00f, 1.00f to 0.00f, 0.88f to 0.46f, 1),
    PolyFacet(0.74f to 0.00f, 0.88f to 0.46f, 0.62f to 0.42f, 3),
    PolyFacet(1.00f to 0.00f, 1.00f to 0.54f, 0.88f to 0.46f, 0),
    // Mountain ridge row
    PolyFacet(0.00f to 0.56f, 0.15f to 0.44f, 0.26f to 0.74f, 4),
    PolyFacet(0.15f to 0.44f, 0.36f to 0.36f, 0.26f to 0.74f, 5),
    PolyFacet(0.36f to 0.36f, 0.62f to 0.42f, 0.48f to 0.72f, 6),
    PolyFacet(0.36f to 0.36f, 0.48f to 0.72f, 0.26f to 0.74f, 4),
    PolyFacet(0.62f to 0.42f, 0.88f to 0.46f, 0.74f to 0.76f, 5),
    PolyFacet(0.62f to 0.42f, 0.74f to 0.76f, 0.48f to 0.72f, 8),
    PolyFacet(0.88f to 0.46f, 1.00f to 0.54f, 0.74f to 0.76f, 4),
    // Foreground base row
    PolyFacet(0.00f to 0.56f, 0.26f to 0.74f, 0.00f to 1.00f, 7),
    PolyFacet(0.00f to 1.00f, 0.26f to 0.74f, 0.36f to 1.00f, 4),
    PolyFacet(0.26f to 0.74f, 0.48f to 0.72f, 0.36f to 1.00f, 7),
    PolyFacet(0.48f to 0.72f, 0.64f to 1.00f, 0.36f to 1.00f, 5),
    PolyFacet(0.48f to 0.72f, 0.74f to 0.76f, 0.64f to 1.00f, 4),
    PolyFacet(0.74f to 0.76f, 1.00f to 1.00f, 0.64f to 1.00f, 7),
    PolyFacet(0.74f to 0.76f, 1.00f to 0.54f, 1.00f to 1.00f, 5),
)

// Seamless low-poly backdrop mesh for empty-state illustrations
private val EMPTY_BG_FACETS = listOf(
    PolyFacet(0.00f to 0.00f, 0.30f to 0.00f, 0.18f to 0.46f, 0),
    PolyFacet(0.00f to 0.00f, 0.18f to 0.46f, 0.00f to 0.62f, 1),
    PolyFacet(0.30f to 0.00f, 0.66f to 0.00f, 0.50f to 0.38f, 1),
    PolyFacet(0.30f to 0.00f, 0.50f to 0.38f, 0.18f to 0.46f, 0),
    PolyFacet(0.66f to 0.00f, 1.00f to 0.00f, 0.84f to 0.44f, 0),
    PolyFacet(0.66f to 0.00f, 0.84f to 0.44f, 0.50f to 0.38f, 2),
    PolyFacet(1.00f to 0.00f, 1.00f to 0.60f, 0.84f to 0.44f, 1),
    // Lower faceted peaks
    PolyFacet(0.00f to 0.62f, 0.18f to 0.46f, 0.34f to 0.78f, 2),
    PolyFacet(0.18f to 0.46f, 0.50f to 0.38f, 0.34f to 0.78f, 3),
    PolyFacet(0.50f to 0.38f, 0.84f to 0.44f, 0.68f to 0.76f, 2),
    PolyFacet(0.50f to 0.38f, 0.68f to 0.76f, 0.34f to 0.78f, 1),
    PolyFacet(0.84f to 0.44f, 1.00f to 0.60f, 0.68f to 0.76f, 3),
    // Bottom foundation
    PolyFacet(0.00f to 0.62f, 0.34f to 0.78f, 0.00f to 1.00f, 3),
    PolyFacet(0.00f to 1.00f, 0.34f to 0.78f, 0.46f to 1.00f, 4),
    PolyFacet(0.34f to 0.78f, 0.68f to 0.76f, 0.46f to 1.00f, 3),
    PolyFacet(0.68f to 0.76f, 0.86f to 1.00f, 0.46f to 1.00f, 2),
    PolyFacet(0.68f to 0.76f, 1.00f to 0.60f, 1.00f to 1.00f, 4),
    PolyFacet(0.68f to 0.76f, 1.00f to 1.00f, 0.86f to 1.00f, 5),
)
