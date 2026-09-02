package lv.bolwarra.wetter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Wetter's colour vocabulary.
 *
 * Material 3's role names (primary / secondary / tertiary / container...) describe
 * a component library, not weather. They give no place to say "this is rain" or
 * "this hour is at night", so the app names its own roles here and derives a
 * [ColorScheme] from them afterwards for the few Material components it uses.
 *
 * Two rules hold the palette together:
 *
 *  - Precipitation owns the only saturated hue. Temperature is deliberately
 *    quieter than rain, because rain is the primary signal (docs/design-principles.md).
 *  - Neither plate uses pure black or pure white. Both grounds are tinted
 *    slightly cool so the rain hue reads as part of the same instrument rather
 *    than as an accent stuck onto neutral grey.
 */
@Immutable
data class WetterColors(
    /** The page ground. Painted by the window before Compose starts — keep in sync with res/values/colors.xml. */
    val surface: Color,
    /** Lifted a step from [surface]: sheets, dialogs, the selected row. Used sparingly. */
    val surfaceRaised: Color,
    /** Recessed a step: the track a timeline bar is drawn into. */
    val surfaceSunken: Color,
    /** Section rules and separators. Always thin, never a heavy border. */
    val hairline: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    /** Axis ticks, units, timestamps — legible but never competing. */
    val textTertiary: Color,

    /** Full-intensity precipitation. The loudest colour in the app. */
    val precipitation: Color,
    /** Light precipitation, and the fill for low-probability hours. */
    val precipitationMuted: Color,
    /** The empty part of a precipitation bar's track. */
    val precipitationTrack: Color,

    /** Warm end of the temperature curve. */
    val temperatureWarm: Color,
    /** Cool end of the temperature curve. */
    val temperatureCool: Color,

    /** Wash behind hours that fall between sunset and sunrise. */
    val night: Color,
    /** The "now" marker and interactive affordances. */
    val accent: Color,
    /** Stale data, failed refresh, severe weather. Never used decoratively. */
    val warning: Color,

    val isLight: Boolean,
)

/** Daylight plate: paper, not white. */
val LightWetterColors = WetterColors(
    surface = Color(0xFFF4F6F7),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFE7ECEF),
    hairline = Color(0xFFD3DADE),

    textPrimary = Color(0xFF10171B),
    textSecondary = Color(0xFF4A565D),
    textTertiary = Color(0xFF7C8A92),

    precipitation = Color(0xFF0E7FB8),
    precipitationMuted = Color(0xFF8CC6E2),
    precipitationTrack = Color(0xFFDDE4E8),

    temperatureWarm = Color(0xFFC2603A),
    temperatureCool = Color(0xFF3F7DA8),

    night = Color(0xFFE2E7EA),
    accent = Color(0xFF0E7FB8),
    warning = Color(0xFFB4541F),

    isLight = true,
)

/**
 * Night plate: a deep blue-grey, not black. Black grounds make thin rules vanish
 * and force every foreground to shout; a tonal ground lets the same hierarchy
 * survive the switch (docs/design-principles.md).
 */
val DarkWetterColors = WetterColors(
    surface = Color(0xFF0F1418),
    surfaceRaised = Color(0xFF171D22),
    surfaceSunken = Color(0xFF0A0E11),
    hairline = Color(0xFF273037),

    textPrimary = Color(0xFFE4EAED),
    textSecondary = Color(0xFF9CA9B1),
    textTertiary = Color(0xFF6B787F),

    precipitation = Color(0xFF4FB0E0),
    precipitationMuted = Color(0xFF2C6E8E),
    precipitationTrack = Color(0xFF1B2329),

    temperatureWarm = Color(0xFFE08A5E),
    temperatureCool = Color(0xFF6FA8CC),

    night = Color(0xFF0A0E11),
    accent = Color(0xFF4FB0E0),
    warning = Color(0xFFE08A5E),

    isLight = false,
)

/**
 * Material components inherit the app's ground and accent rather than the other
 * way round. Only the roles Wetter actually renders are mapped meaningfully; the
 * container roles fall back to surfaces so nothing can introduce a colour that
 * was never chosen here.
 */
fun WetterColors.toMaterialScheme(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = surface,
        primaryContainer = surfaceRaised,
        onPrimaryContainer = textPrimary,
        secondary = textSecondary,
        onSecondary = surface,
        background = surface,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = textSecondary,
        surfaceContainer = surfaceRaised,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerLow = surfaceSunken,
        outline = hairline,
        outlineVariant = hairline,
        error = warning,
        onError = surface,
    )
}

val LocalWetterColors = staticCompositionLocalOf<WetterColors> {
    error("No WetterColors provided — wrap the content in WetterTheme { }.")
}
