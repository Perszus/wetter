package lv.bolwarra.wetter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The single entry point to Wetter's design language.
 *
 * Material You dynamic colour is deliberately not supported. The palette encodes
 * meaning — rain is the only saturated hue, temperature is quieter than rain —
 * and a wallpaper-derived scheme would break that relationship on most devices
 * (docs/decisions.md).
 */
@Composable
fun WetterTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkWetterColors else LightWetterColors

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
