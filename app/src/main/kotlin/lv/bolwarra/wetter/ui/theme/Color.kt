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
 * Material 3's role names describe a component library, not weather. They give
 * no place to say "this is rain" or "this hour is at night", so the app names
 * its own roles here and derives a [ColorScheme] from them for the few Material
 * components it renders.
 *
 * Every role below is a *job*, not a colour. Nothing may be added here that
 * cannot be described as a job, and nothing that has a job may be duplicated
 * under a second name - both of which the palette this replaces did.
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
    /**
     * Where the light lands. Never white: a highlight is the same surface
     * catching more of the one light, not a second, brighter material added on
     * top of it.
     */
    val surfaceHighlight: Color,
    /** Where it does not. Never black, for the same reason. */
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
    /** Labels, units, axis ticks. Still passes AA - it is read, not decorated. */
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

    val isLight: Boolean,
)

/**
 * How firmly something is stated, as an alpha.
 *
 * Before this there were nine alpha constants scattered across three files, each
 * an independent judgement made in isolation, and no two components agreed on
 * what "faint" meant. Emphasis is a hierarchy decision like any other, so it is
 * declared once, next to the tones it modifies.
 *
 * Five steps, because a sixth would not be distinguishable and would only invite
 * a tenth judgement.
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
 * How a plate is built.
 *
 * One specification, read twice. The light plate and the dark plate are the same
 * set of contrast *relationships* pointed in opposite directions - not two
 * palettes that happen to share role names, which is what measuring the previous
 * one revealed it to be: every accent on the light plate failed WCAG AA while
 * the same accents on the dark plate passed at AAA.
 *
 * @param ground the page's lightness, L*. Neither plate goes to an extreme:
 *   pure white is a light source rather than a surface, and pure black has no
 *   room beneath it for anything to recede into.
 * @param inkIsDarker which way the ink runs from the ground.
 */
private data class PlateSpec(
    val ground: Double,
    val inkIsDarker: Boolean,
    val sky: Atmosphere = Atmosphere.Neutral,
) {

    /**
     * A tone at a stated contrast ratio against the ground, on the ink side.
     *
     * The atmosphere is applied here, once, so a sky can never move one role
     * without moving the rest. It scales the *requirement*, not the answer -
     * haze that compresses contrast compresses it for every role at the same
     * rate, which is what keeps the hierarchy intact while the light changes.
     */
    fun ink(ratio: Double, chroma: Double = Tone.NEUTRAL_CHROMA, hue: Double = Tone.NEUTRAL_HUE) =
        Tone.of(
            Tone.lightnessFor(ground, 1.0 + (ratio - 1.0) * sky.contrast, inkIsDarker),
            chroma * sky.chroma,
            hue,
        )

    /**
     * A surface, a stated number of lightness steps from the ground.
     *
     * Light falls from above, so a raised surface is lighter than its ground on
     * the light plate and *also* lighter on the dark plate. A dark plate that
     * darkened its raised surfaces would be lighting the scene from below, which
     * reads as a hole rather than as a step, and is the single most common way a
     * dark theme goes wrong.
     */
    fun surface(steps: Double) = Tone.of((ground + steps).coerceIn(4.0, 99.4))
}

/** How far apart two surfaces sit. Small, because the step is the whole effect. */
private const val SURFACE_STEP = 3.4

/**
 * How far past a raised surface the light reaches, and how far past a sunken one
 * it fails to.
 *
 * Light falls from above and slightly in front, and it is the only light in the
 * app. It has no colour of its own - it raises lightness and nothing else -
 * which is why a highlight is generated from the same ground everything else is
 * and never mixed towards white. Mixing towards white is what the dial used to
 * do, and on the dark plate it put a bloom on the face bright enough to read as
 * a second light source in the room.
 *
 * Shade reaches less far than highlight. A surface turned away from the light
 * still receives the ambient of the room, so the fall-off is not symmetrical.
 */
private const val HIGHLIGHT_REACH = 2.2

private const val SHADE_REACH = 1.5

/**
 * The filled heading band, against its ground - and so also the contrast its
 * own text carries, since that text is the ground.
 */
private const val BAND_CONTRAST = 5.4

/**
 * The contrast each ink role is solved for.
 *
 * These are the design rules. A tone is never picked; it is the answer to one of
 * these numbers, which is what makes the two plates the same system and what
 * stops a future colour from quietly failing.
 */
private const val PRIMARY_CONTRAST = 13.0

private const val SECONDARY_CONTRAST = 7.2

/**
 * AA for body text, with a margin. The role this replaces measured 3.28 against
 * its own ground while carrying every label, unit and axis tick in the app -
 * thirty call sites of unreadable-by-standard text.
 */
private const val TERTIARY_CONTRAST = 4.7

/** Below the readable bar on purpose: disabled must not look merely quiet. */
private const val DISABLED_CONTRAST = 2.4

/** Enough to find when looked for, not enough to draw the eye. */
private const val HAIRLINE_CONTRAST = 1.7

/** Quieter than a hairline: gridlines sit behind live data. */
private const val GRIDLINE_CONTRAST = 1.35

/** Any accent carrying text or a thin line meets the same bar the labels do. */
private const val ACCENT_CONTRAST = 4.7

/** Accents that only ever fill an area, never carry a line or a glyph. */
private const val FILL_CONTRAST = 2.1

/**
 * Hues, in CIE degrees.
 *
 * Precipitation owns the only high chroma in the app. Temperature runs warm to
 * cool at a third of it, so a temperature can never out-shout rain - which is
 * the app's oldest rule and the reason it has a palette at all.
 */
private const val RAIN_HUE = 252.0

private const val RAIN_CHROMA = 40.0

private const val WARM_HUE = 58.0

private const val COOL_HUE = 228.0

private const val TEMPERATURE_CHROMA = 22.0

/** Warm, and clearly not the temperature scale: half again the chroma, redder. */
private const val WARNING_HUE = 62.0

private const val DANGER_HUE = 30.0

private const val ALERT_CHROMA = 54.0

private const val POSITIVE_HUE = 148.0

private const val STATE_CHROMA = 26.0

private fun plate(spec: PlateSpec, isLight: Boolean) = WetterColors(
    surface = Tone.of(spec.ground),
    surfaceRaised = spec.surface(SURFACE_STEP),
    surfaceSunken = spec.surface(-SURFACE_STEP),
    surfaceHighlight = spec.surface(SURFACE_STEP * HIGHLIGHT_REACH),
    surfaceShade = spec.surface(-SURFACE_STEP * SHADE_REACH),
    // The one filled block in the app, and the only place a large area of ink
    // appears. It inverts its plate - a dark band on the light one, a light band
    // on the dark one - which is the same idea mirrored rather than a second
    // treatment, and is how everything else here is built.
    surfaceStrong = spec.ink(BAND_CONTRAST),
    // The page showing through. Setting it to the ground makes the band's
    // contrast true by construction instead of a second value to keep in step,
    // and it is why this pair cannot drift apart.
    onSurfaceStrong = Tone.of(spec.ground),

    hairline = spec.ink(HAIRLINE_CONTRAST),
    gridline = spec.ink(GRIDLINE_CONTRAST),

    textPrimary = spec.ink(PRIMARY_CONTRAST),
    textSecondary = spec.ink(SECONDARY_CONTRAST),
    textTertiary = spec.ink(TERTIARY_CONTRAST),
    textDisabled = spec.ink(DISABLED_CONTRAST),

    interactive = spec.ink(ACCENT_CONTRAST, RAIN_CHROMA * 0.7, RAIN_HUE),
    interactivePressed = spec.ink(SECONDARY_CONTRAST, RAIN_CHROMA * 0.7, RAIN_HUE),

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
)

/**
 * Daylight: paper under an overcast sky, not white.
 *
 * L* 94 rather than 100. A page at full white is a light source, and everything
 * placed on it has to fight it; a step down and the ink sits *in* the page.
 */
val LightWetterColors = lightPlate(Atmosphere.Neutral)

/** The light plate as the sky of the moment leaves it. */
fun lightPlate(sky: Atmosphere): WetterColors =
    plate(PlateSpec(LIGHT_GROUND + sky.ground, inkIsDarker = true, sky = sky), isLight = true)

/**
 * L* 95: paper.
 *
 * The first attempt at this sat at 94 and read as card rather than paper - once
 * an overcast sky took two more steps off it the page was grey, and the dial
 * that is meant to be the one lit object on it had nothing to be lighter than.
 * A page has to stay a page under the worst sky in the table, which is what sets
 * this number rather than how it looks on a clear day.
 */
private const val LIGHT_GROUND = 95.0

/**
 * Night: a deep blue-grey, not black.
 *
 * L* 13 rather than 0. Black grounds make thin rules vanish and force every
 * foreground to shout, and leave nothing for a sunken surface to recede into.
 */
val DarkWetterColors = darkPlate(Atmosphere.Neutral)

/**
 * The dark plate as the sky of the moment leaves it.
 *
 * The ground shift is subtracted rather than added: an overcast sky takes light
 * away, and on a plate where the ink is the light thing, taking light away means
 * moving the ground *down* in the same direction it already runs. Adding it here
 * would have made a storm brighten the night, which is the kind of mistake a
 * single shared sign hides until somebody opens the app in one.
 */
fun darkPlate(sky: Atmosphere): WetterColors = plate(
    PlateSpec(DARK_GROUND - sky.ground * NIGHT_GROUND_SHARE, inkIsDarker = false, sky = sky),
    isLight = false,
)

/**
 * L* 9, not 0 and not 13.
 *
 * Black leaves nothing for a sunken surface to recede into and makes every thin
 * rule vanish. But a ground much above this stops reading as night and starts
 * reading as grey - L* 13 rendered as #1F2225, which is a slate, not a dark
 * room. Nine is the deepest the plate can go while still having a step beneath
 * it.
 */
private const val DARK_GROUND = 9.0

/**
 * A dark plate has far less room beneath it than a light one has above it -
 * four lightness steps from L*13 is most of the way to black - so the ground
 * moves a fraction as far.
 */
private const val NIGHT_GROUND_SHARE = 0.45

/**
 * Material components inherit the app's ground and accent rather than the other
 * way round. Only the roles Wetter actually renders are mapped meaningfully; the
 * container roles fall back to surfaces so nothing can introduce a colour that
 * was never chosen here.
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
