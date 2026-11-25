package com.example.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Harmonious Material 3 Expressive tonal accent presets — each preset defines primary,
 * primaryContainer, AND complementary (~60° HCT-rotated) Monet-aligned tertiary / tertiaryContainer
 * tokens for both Dark/AMOLED and Light themes.
 */
enum class AccentPreset(
    val label: String,
    val darkColor: Color,
    val onDarkPrimary: Color,
    val darkContainer: Color,
    val onDarkContainer: Color,
    val lightColor: Color,
    val lightContainer: Color,
    val onLightContainer: Color,
    val darkTertiary: Color,
    val onDarkTertiary: Color,
    val darkTertiaryContainer: Color,
    val onDarkTertiaryContainer: Color,
    val lightTertiary: Color,
    val onLightTertiary: Color,
    val lightTertiaryContainer: Color,
    val onLightTertiaryContainer: Color,
) {
    SKY(
        label = "Sky Cobalt",
        darkColor = SkyCobalt,
        onDarkPrimary = Color(0xFF04141F),
        darkContainer = SkyCobaltContainerDark,
        onDarkContainer = SkyCobaltOnContainerDark,
        lightColor = SkyCobaltDark,
        lightContainer = SkyCobaltContainerLight,
        onLightContainer = SkyCobaltOnContainerLight,
        // Complementary Warm Coral-Amber tertiary for cool Sky Cobalt
        darkTertiary = Color(0xFFFFB59D),
        onDarkTertiary = Color(0xFF471506),
        darkTertiaryContainer = Color(0xFF662916),
        onDarkTertiaryContainer = Color(0xFFFFDBD0),
        lightTertiary = Color(0xFF9C4227),
        onLightTertiary = Color.White,
        lightTertiaryContainer = Color(0xFFFFDBD0),
        onLightTertiaryContainer = Color(0xFF3A0B01),
    ),
    INDIGO(
        label = "Indigo",
        darkColor = SoftIndigo,
        onDarkPrimary = Color(0xFF0F102B),
        darkContainer = SoftIndigoContainerDark,
        onDarkContainer = SoftIndigoOnContainerDark,
        lightColor = SoftIndigoDark,
        lightContainer = Color(0xFFE0E7FF),
        onLightContainer = Color(0xFF1E1B4B),
        // Complementary Rose-Plum tertiary for cool Indigo
        darkTertiary = Color(0xFFF3B7D5),
        onDarkTertiary = Color(0xFF4B1E38),
        darkTertiaryContainer = Color(0xFF65344F),
        onDarkTertiaryContainer = Color(0xFFFFD8EA),
        lightTertiary = Color(0xFF814C68),
        onLightTertiary = Color.White,
        lightTertiaryContainer = Color(0xFFFFD8EA),
        onLightTertiaryContainer = Color(0xFF340B23),
    ),
    EMERALD(
        label = "Emerald",
        darkColor = AccentEmerald,
        onDarkPrimary = Color(0xFF022C22),
        darkContainer = Color(0xFF064E3B),
        onDarkContainer = Color(0xFFA7F3D0),
        lightColor = Color(0xFF059669),
        lightContainer = Color(0xFFD1FAE5),
        onLightContainer = Color(0xFF064E3B),
        // Complementary Sky-Periwinkle tertiary for fresh Emerald
        darkTertiary = Color(0xFFA5CCEB),
        onDarkTertiary = Color(0xFF08344E),
        darkTertiaryContainer = Color(0xFF234B67),
        onDarkTertiaryContainer = Color(0xFFCDE5FF),
        lightTertiary = Color(0xFF3B6380),
        onLightTertiary = Color.White,
        lightTertiaryContainer = Color(0xFFCDE5FF),
        onLightTertiaryContainer = Color(0xFF001E31),
    ),
    CORAL(
        label = "Coral Rose",
        darkColor = AccentCoral,
        onDarkPrimary = Color(0xFF330612),
        darkContainer = Color(0xFF5C1024),
        onDarkContainer = Color(0xFFFFD1DC),
        lightColor = Color(0xFFE11D48),
        lightContainer = Color(0xFFFFE4E6),
        onLightContainer = Color(0xFF881337),
        // Complementary Honey-Gold tertiary for warm Coral Rose
        darkTertiary = Color(0xFFE6C185),
        onDarkTertiary = Color(0xFF422C00),
        darkTertiaryContainer = Color(0xFF5D4212),
        onDarkTertiaryContainer = Color(0xFFFFDFA6),
        lightTertiary = Color(0xFF775925),
        onLightTertiary = Color.White,
        lightTertiaryContainer = Color(0xFFFFDFA6),
        onLightTertiaryContainer = Color(0xFF271900),
    ),
    AMBER(
        label = "Warm Amber",
        darkColor = AccentAmber,
        onDarkPrimary = Color(0xFF2B1700),
        darkContainer = Color(0xFF523104),
        onDarkContainer = Color(0xFFFEF3C7),
        lightColor = Color(0xFFD97706),
        lightContainer = Color(0xFFFEF3C7),
        onLightContainer = Color(0xFF78350F),
        // Complementary Sage-Teal tertiary for Warm Amber
        darkTertiary = Color(0xFFA9D2B5),
        onDarkTertiary = Color(0xFF123823),
        darkTertiaryContainer = Color(0xFF2B4F39),
        onDarkTertiaryContainer = Color(0xFFC5EED0),
        lightTertiary = Color(0xFF43674F),
        onLightTertiary = Color.White,
        lightTertiaryContainer = Color(0xFFC5EED0),
        onLightTertiaryContainer = Color(0xFF002110),
    ),
    VIOLET(
        label = "Violet",
        darkColor = AccentViolet,
        onDarkPrimary = Color(0xFF1F0A3D),
        darkContainer = Color(0xFF3B1A6E),
        onDarkContainer = Color(0xFFEDE9FE),
        lightColor = Color(0xFF7C3AED),
        lightContainer = Color(0xFFEDE9FE),
        onLightContainer = Color(0xFF4C1D95),
        // Complementary Mint-Teal tertiary for expressive Violet
        darkTertiary = Color(0xFF8CD6C4),
        onDarkTertiary = Color(0xFF00382D),
        darkTertiaryContainer = Color(0xFF005142),
        onDarkTertiaryContainer = Color(0xFFA8F2E0),
        lightTertiary = Color(0xFF1D6B59),
        onLightTertiary = Color.White,
        lightTertiaryContainer = Color(0xFFA8F2E0),
        onLightTertiaryContainer = Color(0xFF002019),
    ),
}

/**
 * Material 3 Expressive spring motion tokens:
 * - Spatial springs: slightly bouncy physics for position, scale, and corner-radius morphs.
 * - Effects springs: critically damped, snappy physics for color, alpha, and tonal elevation.
 */
object IlayMotion {
    fun <T> spatialDefault(): SpringSpec<T> = spring(
        dampingRatio = 0.80f,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> spatialBouncy(): SpringSpec<T> = spring(
        dampingRatio = 0.65f,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> effectsDefault(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

private fun amoledColorScheme(accent: AccentPreset): ColorScheme = darkColorScheme(
    primary = accent.darkColor,
    onPrimary = accent.onDarkPrimary,
    primaryContainer = accent.darkContainer,
    onPrimaryContainer = accent.onDarkContainer,
    secondary = SoftIndigo,
    onSecondary = Color(0xFF0F102B),
    secondaryContainer = SoftIndigoContainerDark,
    onSecondaryContainer = SoftIndigoOnContainerDark,
    tertiary = accent.darkTertiary,
    onTertiary = accent.onDarkTertiary,
    tertiaryContainer = accent.darkTertiaryContainer,
    onTertiaryContainer = accent.onDarkTertiaryContainer,
    background = AmoledBlack,
    onBackground = InkLight,
    surface = AmoledBlack,
    onSurface = InkLight,
    surfaceVariant = AmoledSurface,
    onSurfaceVariant = InkLightMuted,
    surfaceContainerLowest = AmoledBlack,
    surfaceContainerLow = AmoledSurfaceLow,
    surfaceContainer = AmoledSurface,
    surfaceContainerHigh = AmoledSurfaceHigh,
    surfaceContainerHighest = AmoledSurfaceHighest,
    outline = AmoledOutline,
    outlineVariant = AmoledOutlineVariant,
    error = ErrorCrimson,
    onError = Color.White,
)

private fun slateDarkColorScheme(accent: AccentPreset): ColorScheme = darkColorScheme(
    primary = accent.darkColor,
    onPrimary = accent.onDarkPrimary,
    primaryContainer = accent.darkContainer,
    onPrimaryContainer = accent.onDarkContainer,
    secondary = SoftIndigo,
    onSecondary = Color(0xFF0F102B),
    secondaryContainer = SoftIndigoContainerDark,
    onSecondaryContainer = SoftIndigoOnContainerDark,
    tertiary = accent.darkTertiary,
    onTertiary = accent.onDarkTertiary,
    tertiaryContainer = accent.darkTertiaryContainer,
    onTertiaryContainer = accent.onDarkTertiaryContainer,
    background = SlateBackground,
    onBackground = InkLight,
    surface = SlateBackground,
    onSurface = InkLight,
    surfaceVariant = SlateSurface,
    onSurfaceVariant = InkLightMuted,
    surfaceContainerLowest = SlateBackground,
    surfaceContainerLow = SlateSurfaceLow,
    surfaceContainer = SlateSurface,
    surfaceContainerHigh = SlateSurfaceHigh,
    surfaceContainerHighest = Color(0xFF2D3B4D),
    outline = SlateOutline,
    outlineVariant = SlateOutlineVariant,
    error = ErrorCrimson,
    onError = Color.White,
)

private fun daylightLightColorScheme(accent: AccentPreset): ColorScheme = lightColorScheme(
    primary = accent.lightColor,
    onPrimary = Color.White,
    primaryContainer = accent.lightContainer,
    onPrimaryContainer = accent.onLightContainer,
    secondary = SoftIndigoDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = accent.lightTertiary,
    onTertiary = accent.onLightTertiary,
    tertiaryContainer = accent.lightTertiaryContainer,
    onTertiaryContainer = accent.onLightTertiaryContainer,
    background = DaylightBackground,
    onBackground = InkDark,
    surface = DaylightBackground,
    onSurface = InkDark,
    surfaceVariant = DaylightSurface,
    onSurfaceVariant = InkDarkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = DaylightSurfaceLow,
    surfaceContainer = DaylightSurface,
    surfaceContainerHigh = DaylightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFD5DDE8),
    outline = DaylightOutline,
    outlineVariant = DaylightOutlineVariant,
    error = ErrorCrimson,
    onError = Color.White,
)

val IlayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun IlayForXTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    amoledDark: Boolean = true,
    useDynamicColor: Boolean = false,
    accentPreset: AccentPreset = AccentPreset.SKY,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }
    val useAmoled = useDark && amoledDark

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val base = if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (useAmoled) {
                base.copy(
                    background = AmoledBlack,
                    surface = AmoledBlack,
                    surfaceContainerLowest = AmoledBlack,
                    surfaceContainerLow = AmoledSurfaceLow,
                )
            } else base
        }
        useAmoled -> amoledColorScheme(accentPreset)
        useDark -> slateDarkColorScheme(accentPreset)
        else -> daylightLightColorScheme(accentPreset)
    }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !useDark
                controller.isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IlayTypography,
        shapes = IlayShapes,
        content = content
    )
}
