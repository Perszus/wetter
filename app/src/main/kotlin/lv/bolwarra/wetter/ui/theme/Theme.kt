package lv.bolwarra.wetter.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.settings.ThemeChoice

/**
 * The single entry point to Wetter's design language.
 *
 * Material You dynamic colour is deliberately not supported. The palette encodes
 * meaning — rain is the only saturated hue, temperature is quieter than rain —
 * and a wallpaper-derived scheme would break that relationship on most devices
 * (docs/decisions.md).
 */
@Composable
fun WetterTheme(
    /**
     * How the reader has asked for numbers to be written, and which plate to use.
     *
     * Defaulted from the composition rather than from nothing, because this
     * theme is applied twice: once at the root and again inside the weather
     * screen, which re-reads it to change the light for the sky outside. A
     * default of `Preferences()` on the inner call would quietly reset the units
     * to metric and the plate to the system's for everything below it.
     */
    units: Preferences = LocalUnits.current,
    /**
     * The sky the app is being read under.
     *
     * Which plate is used stays a system setting - somebody who has asked for a
     * dark interface has asked for one, and the weather does not overrule that.
     * What the weather changes is the light *within* the chosen plate, which is
     * a change of atmosphere rather than a change of mode.
     */
    sky: Atmosphere = Atmosphere.Neutral,
    content: @Composable () -> Unit,
) {
    // Remembered because generating a plate is thirty colour-space conversions,
    // and the sky changes on the hour rather than on the frame.
    val colors = remember(units.theme, sky) { plateFor(units.theme, sky) }

    // The phone's own row, kept readable.
    //
    // The app draws under the status bar on purpose - the forecast reads as one
    // column running the full height of the display - but drawing under
    // somebody's clock is not the same as covering it. The system bar icons are
    // painted by the system in one of two ways, dark or light, and it picks by
    // the *phone's* dark mode rather than by ours: choosing Pure Black on a
    // phone set to light left dark icons on a near-black ground, which is a
    // blacked-out strip where the time should be.
    //
    // So it is told which way this plate runs, every time the plate changes.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightBars = colors.isLight
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    CompositionLocalProvider(
        LocalWetterColors provides colors,
        LocalSpacing provides Spacing(),
        LocalUnits provides units,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = WetterTypography,
            content = content,
        )
    }
}

/** `WetterTheme.colors`, `WetterTheme.spacing`, `WetterTheme.type` at any call site. */
object WetterTheme {
    val colors: WetterColors
        @Composable @ReadOnlyComposable
        get() = LocalWetterColors.current

    val spacing: Spacing
        @Composable @ReadOnlyComposable
        get() = LocalSpacing.current

    val type: WetterType get() = WetterType

    val units: Preferences
        @Composable @ReadOnlyComposable
        get() = LocalUnits.current
}

/**
 * Metric until somebody says otherwise, which is what most of the world reads.
 *
 * On the theme because that is what it is: a property of how the app is being
 * presented, wanted at nearly every leaf that draws a figure and of no interest
 * to any layer in between. Threading it down as a parameter would put it in a
 * dozen signatures that have no other use for it.
 */
val LocalUnits = staticCompositionLocalOf { Preferences() }

/**
 * Which plate a choice asks for.
 *
 * Composable because the system answer is: somebody who has told their phone
 * they want dark has answered this once already, and the app follows unless it
 * has been told otherwise.
 */
/**
 * The plate a choice asks for.
 *
 * Public because the settings panel draws a sample of each one, which means
 * building a plate that is not the one currently in force.
 */
fun plateFor(choice: ThemeChoice, sky: Atmosphere = Atmosphere.Neutral): WetterColors =
    when (choice) {
        ThemeChoice.PURE_WHITE -> pureWhitePlate(sky)
        ThemeChoice.PURE_BLACK -> pureBlackPlate(sky)
    }
