// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Wetter's colour roles. Each role is a job (rain, night, a hairline), not a
 * colour, and no job has two names. A Material [ColorScheme] is derived from these
 * for the few Material components the app uses.
 */
@Immutable
data class WetterColors(
    // --- ground and surfaces ---------------------------------------------------
    /** The page. Painted by the window before Compose starts - keep res/values/colors.xml in step. */
    val surface: Color,
    /** Lifted a step: tiles, sheets, the selected row. */
    val surfaceRaised: Color,
    /** Recessed a step: the track a chart is drawn into, the bar under it. */
    val surfaceSunken: Color,
    /** Where the light lands: the same surface catching more light, never white. */
    val surfaceHighlight: Color,
    /** Where it does not: never black. */
    val surfaceShade: Color,
    /** The heading band inside a tile - the one filled block in the app. */
    val surfaceStrong: Color,
    /** Text on [surfaceStrong]. */
    val onSurfaceStrong: Color,

    // --- structure -------------------------------------------------------------
    /** Section rules and separators. Always a hairline, never a border. */
    val hairline: Color,
    /** Chart gridlines. Quieter than [hairline]: they sit behind live data. */
    val gridline: Color,

    // --- ink -------------------------------------------------------------------
    /** Readings, headings, anything the screen was opened for. */
    val textPrimary: Color,
    /** Supporting prose and secondary readings. */
    val textSecondary: Color,
    /** Labels, units, axis ticks. Still passes AA. */
    val textTertiary: Color,
    /** Deliberately below the readable bar, so it cannot be mistaken for live. */
    val textDisabled: Color,

    // --- interaction -----------------------------------------------------------
    /** The one colour that means "this responds to you". Never used decoratively. */
    val interactive: Color,
    /** Held or selected. */
    val interactivePressed: Color,

    // --- weather ---------------------------------------------------------------
    /** Full-intensity precipitation. The loudest hue in the app. */
    val precipitation: Color,
    /** Light precipitation, and the fill under a curve. */
    val precipitationMuted: Color,
    /** The empty part of a precipitation track. */
    val precipitationTrack: Color,
    /** Warm end of the temperature axis. Deliberately quieter than rain. */
    val temperatureWarm: Color,
    /** Cool end of the temperature axis. */
    val temperatureCool: Color,
    /** Daylight: the sun's mark, and the wash behind daylit hours. */
    val day: Color,
    /** Night: the moon's mark, and the wash behind hours after sunset. */
    val night: Color,

    // --- state -----------------------------------------------------------------
    /** Severe weather still ahead, and anything stale. */
    val warning: Color,
    /** Severe weather happening now. The only role louder than rain. */
    val danger: Color,
    /** A check that passed, a forecast that verified. */
    val positive: Color,
    /** A check that failed. */
    val negative: Color,
    /** Neutral notice: an explanation, a note about provenance. */
    val informational: Color,

    /**
     * How strongly an object (the dial) shades itself with this plate's surface
     * ladder. Plates space their surfaces differently for panels; scaling by this
     * gives an object the same depth of shading on every plate.
     */
    val relief: Float,

    val isLight: Boolean,

    /** A light plate's dark counterpart in the same tone; null on a dark plate. */
    private val darkSibling: WetterColors? = null,
) {
    /**
     * The dial's colours: always a dark face, so the glass pictures, which are
     * drawn as light, always show. On a light plate this is its dark counterpart
     * in the same tone, a true-grey black under Pure White.
     */
    val dial: WetterColors get() = darkSibling ?: this
}

/**
 * How firmly something is stated, as an alpha: one five-step hierarchy shared by
 * every component.
 */
object Emphasis {
    /** As stated. */
    const val FULL = 1.0f

    /** Present, and one rank down. */
    const val STRONG = 0.70f

    /** Supporting: a fill under a line, an inactive level. */
    const val MUTED = 0.42f

    /** A trace: the far end of a gradient, a scrub cursor. */
    const val FAINT = 0.20f

    /** Barely there, and meant to be. */
    const val GHOST = 0.08f
}

/**
 * How a plate is built: one specification read in either direction, so the light
 * and dark plates are the same contrast relationships inverted.
 *
 * @param ground the page's lightness, L*. Neither extreme: pure white is a light
 * source, and pure black leaves nothing beneath it to recede into.
 * @param inkIsDarker which way the ink runs from the ground.
 */
private data class PlateSpec(
    val ground: Double,
    val inkIsDarker: Boolean,
    val sky: Atmosphere = Atmosphere.Neutral,
    /**
     * How far this plate's greys stray from true grey: a trace of blue on the app's
     * own plates, zero on the pure ones.
     */
    val neutral: Double = Tone.NEUTRAL_CHROMA,
    /**
     * How far apart two surfaces sit: small on the app's plates, about twice as far
     * on the zone-system plates for legibility on OLED.
     */
    val step: Double = SURFACE_STEP,
    /**
     * Which way a raised surface goes: lighter, since light falls from above, except
     * on Pure White, which has no headroom above its ground and recesses instead.
     */
    val raisedIsLighter: Boolean = true,
) {

    /**
     * A tone at a stated contrast ratio against the ground, on the ink side. The
     * atmosphere scales the requirement, so haze moves every role together.
     */
    fun ink(ratio: Double, chroma: Double = neutral, hue: Double = Tone.NEUTRAL_HUE) = Tone.of(
        Tone.lightnessFor(ground, 1.0 + (ratio - 1.0) * sky.contrast, inkIsDarker),
        chroma * sky.chroma,
        hue,
    )

    /** A surface a stated number of lightness steps from the ground. */
    fun surface(steps: Double) =
        Tone.of((ground + if (raisedIsLighter) steps else -steps).coerceIn(4.0, 99.4), neutral)
}

/** How far apart two surfaces sit; small, because the step is the whole effect. */
private const val SURFACE_STEP = 3.4

/**
 * How far past a raised surface the light reaches, and how far past a sunken one
 * it fails to. Shade reaches less far than highlight. The light raises lightness
 * only; it has no colour of its own.
 */
private const val HIGHLIGHT_REACH = 2.2

private const val SHADE_REACH = 1.5

/**
 * The filled heading band against its ground, which is also its text's contrast,
 * since that text is the ground.
 */
private const val BAND_CONTRAST = 5.4

/**
 * The contrast each ink role is solved for. Tones are never picked; each is the
 * answer to one of these numbers.
 */
private const val PRIMARY_CONTRAST = 13.0

private const val SECONDARY_CONTRAST = 7.2

/** AA for body text, with a margin. */
private const val TERTIARY_CONTRAST = 4.7

/** Below the readable bar on purpose: disabled must not look merely quiet. */
private const val DISABLED_CONTRAST = 2.4

/** Enough to find when looked for, not enough to draw the eye. */
private const val HAIRLINE_CONTRAST = 1.7

/** Quieter than a hairline: gridlines sit behind live data. */
private const val GRIDLINE_CONTRAST = 1.35

/**
 * Between body and headline: stronger than the text around it without becoming
 * the loudest thing on the page.
 */
private const val INTERACTIVE_CONTRAST = 10.0
private const val INTERACTIVE_PRESSED_CONTRAST = 6.2

/** What a coloured role is held to, so an accent carries a label's weight. */
private const val ACCENT_CONTRAST = 4.7

/** Accents that only ever fill an area, never carry a line or a glyph. */
private const val FILL_CONTRAST = 2.1

/**
 * Hues, in CIE degrees. Precipitation owns the only high chroma; temperature runs
 * at a third of it so it never out-shouts rain.
 */
private const val RAIN_HUE = 252.0

private const val RAIN_CHROMA = 40.0

private const val WARM_HUE = 58.0

private const val COOL_HUE = 228.0

private const val TEMPERATURE_CHROMA = 22.0

/** Warm, and clearly not the temperature scale: more chroma, redder. */
private const val WARNING_HUE = 62.0

private const val DANGER_HUE = 30.0

private const val ALERT_CHROMA = 54.0

private const val POSITIVE_HUE = 148.0

private const val STATE_CHROMA = 26.0

private fun plate(spec: PlateSpec, isLight: Boolean, darkSibling: WetterColors? = null) =
    WetterColors(
        surface = Tone.of(spec.ground, spec.neutral),
        relief = (SURFACE_STEP / spec.step).toFloat().coerceAtMost(1f),

        surfaceRaised = spec.surface(spec.step),
        surfaceSunken = spec.surface(-spec.step),
        surfaceHighlight = spec.surface(spec.step * HIGHLIGHT_REACH),
        surfaceShade = spec.surface(-spec.step * SHADE_REACH),
        // The one filled block: it inverts its plate, dark on light and light on dark.
        surfaceStrong = spec.ink(BAND_CONTRAST),
        // The page showing through, so the band's contrast holds by construction.
        onSurfaceStrong = Tone.of(spec.ground, spec.neutral),

        hairline = spec.ink(HAIRLINE_CONTRAST),
        gridline = spec.ink(GRIDLINE_CONTRAST),

        textPrimary = spec.ink(PRIMARY_CONTRAST),
        textSecondary = spec.ink(SECONDARY_CONTRAST),
        textTertiary = spec.ink(TERTIARY_CONTRAST),
        textDisabled = spec.ink(DISABLED_CONTRAST),

        // Ink, not a hue: the one saturated hue means rain, so actions are set apart by
        // weight.
        interactive = spec.ink(INTERACTIVE_CONTRAST),
        interactivePressed = spec.ink(INTERACTIVE_PRESSED_CONTRAST),

        precipitation = spec.ink(ACCENT_CONTRAST, RAIN_CHROMA, RAIN_HUE),
        precipitationMuted = spec.ink(FILL_CONTRAST, RAIN_CHROMA * 0.62, RAIN_HUE),
        precipitationTrack = spec.ink(HAIRLINE_CONTRAST, RAIN_CHROMA * 0.25, RAIN_HUE),

        temperatureWarm = spec.ink(ACCENT_CONTRAST, TEMPERATURE_CHROMA, WARM_HUE),
        temperatureCool = spec.ink(ACCENT_CONTRAST, TEMPERATURE_CHROMA, COOL_HUE),

        day = spec.ink(FILL_CONTRAST, TEMPERATURE_CHROMA * 0.5, WARM_HUE),
        night = spec.ink(FILL_CONTRAST, TEMPERATURE_CHROMA * 0.5, COOL_HUE),

        warning = spec.ink(ACCENT_CONTRAST, ALERT_CHROMA * 0.8, WARNING_HUE),
        danger = spec.ink(ACCENT_CONTRAST, ALERT_CHROMA, DANGER_HUE),
        positive = spec.ink(ACCENT_CONTRAST, STATE_CHROMA, POSITIVE_HUE),
        negative = spec.ink(ACCENT_CONTRAST, STATE_CHROMA, DANGER_HUE),
        informational = spec.ink(ACCENT_CONTRAST, STATE_CHROMA, RAIN_HUE),

        isLight = isLight,
        darkSibling = darkSibling,
    )

/** Daylight: paper under an overcast sky, not white. */
val LightWetterColors = lightPlate(Atmosphere.Neutral)

/** The light plate as the sky of the moment leaves it. */
fun lightPlate(sky: Atmosphere): WetterColors = plate(
    PlateSpec(LIGHT_GROUND + sky.ground, inkIsDarker = true, sky = sky),
    isLight = true,
    darkSibling = darkPlate(sky),
)

/**
 * L* 95: paper. Set so the page stays a page under the darkest sky in the
 * atmosphere table.
 */
private const val LIGHT_GROUND = 95.0

/** Night: a deep blue-grey, not black. */
val DarkWetterColors = darkPlate(Atmosphere.Neutral)

/**
 * The dark plate as the sky of the moment leaves it. The ground shift is
 * subtracted, so an overcast sky darkens the night rather than brightening it.
 */
fun darkPlate(sky: Atmosphere): WetterColors = plate(
    PlateSpec(DARK_GROUND - sky.ground * NIGHT_GROUND_SHARE, inkIsDarker = false, sky = sky),
    isLight = false,
)

/**
 * Pure White and Pure Black, shared with the other Bolwarra apps and built to
 * their own formulas rather than this app's solver.
 *
 * Pure Black follows Ansel Adams' zone system: zones about sixteen hex apart,
 * elevation as brightness, the ground at Zone I so a sunken surface has Zone 0
 * beneath it. Pure White follows Kenya Hara's paper: an off-white ground, near-
 * black ink, and elevation as a slight darkening.
 *
 * Both are true grey, where the app's own plates carry a trace of blue. Rain is
 * the same blue on all four.
 */
fun pureWhitePlate(sky: Atmosphere): WetterColors = plate(
    PlateSpec(
        ground = PURE_WHITE_GROUND + sky.ground,
        inkIsDarker = true,
        sky = sky,
        neutral = TRUE_GREY,
        step = PURE_STEP_PAPER,
        raisedIsLighter = false,
    ),
    isLight = true,
    darkSibling = pureBlackPlate(sky),
)

fun pureBlackPlate(sky: Atmosphere): WetterColors = plate(
    PlateSpec(
        ground = PURE_BLACK_GROUND - sky.ground * NIGHT_GROUND_SHARE,
        inkIsDarker = false,
        sky = sky,
        neutral = TRUE_GREY,
        step = PURE_STEP,
    ),
    isLight = false,
)

/** No trace of blue. */
private const val TRUE_GREY = 0.0

/** Hara's paper, `#F6F6F6`, as L*. */
private const val PURE_WHITE_GROUND = 96.9

/** Zone I, `#1A1A1A`, as L*. Zone 0 is left below it for the sunken surface. */
private const val PURE_BLACK_GROUND = 9.3

/** Zone I to Zone II, `#1A1A1A` to `#2A2A2A`, as a difference in L*. */
private const val PURE_STEP = 7.7

/** `#F6F6F6` to `#ECECEC`: the same ladder, compressed by the top of the range. */
private const val PURE_STEP_PAPER = 3.5

/**
 * L* 9: deep enough to read as night, with a step left beneath it for a sunken
 * surface.
 */
private const val DARK_GROUND = 9.0

/**
 * A dark plate has far less room below it than a light one has above, so its
 * ground moves a fraction as far.
 */
private const val NIGHT_GROUND_SHARE = 0.45

/**
 * Material components inherit the app's roles. Container roles fall back to
 * surfaces, so no colour appears that was not chosen here.
 */
fun WetterColors.toMaterialScheme(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = interactive,
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
        outlineVariant = gridline,
        error = danger,
        onError = surface,
    )
}

val LocalWetterColors = staticCompositionLocalOf<WetterColors> {
    error("No WetterColors provided — wrap the content in WetterTheme { }.")
}
