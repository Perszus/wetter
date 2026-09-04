package lv.bolwarra.wetter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

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
    darkTheme: Boolean = isSystemInDarkTheme(),
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
    val colors = remember(darkTheme, sky) {
        if (darkTheme) darkPlate(sky) else lightPlate(sky)
    }

    CompositionLocalProvider(
        LocalWetterColors provides colors,
        LocalSpacing provides Spacing(),
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
}
