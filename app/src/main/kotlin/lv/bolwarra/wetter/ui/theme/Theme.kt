// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.settings.ThemeChoice

/**
 * The single entry point to Wetter's design language. No dynamic colour: the
 * palette encodes meaning, and a wallpaper-derived scheme would break it
 * (docs/decisions.md).
 */
@Composable
fun WetterTheme(
    /**
     * How the reader has asked for numbers to be written, and which plate to use.
     * Defaulted from the composition, because this theme is applied again inside the
     * weather screen, and a fresh default there would reset everything below it.
     */
    units: Preferences = LocalUnits.current,
    /**
     * The sky the app is being read under. It changes the light within the chosen
     * plate, never which plate.
     */
    sky: Atmosphere = Atmosphere.Neutral,
    content: @Composable () -> Unit,
) {
    // Remembered because generating a plate is thirty colour-space conversions,
    // and the sky changes on the hour rather than on the frame.
    val colors = remember(units.theme, sky) { plateFor(units.theme, sky) }

    // The system bar icons, kept readable: the system picks their colour by the
    // phone's dark mode, so it is told which way this plate runs whenever the plate
    // changes.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val lightBars = colors.isLight
        val ground = colors.surface.toArgb()
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
            // The window's own ground, painted before Compose draws. The launch colour can
            // only be the default plate, so this corrects it for the other one.
            window.setBackgroundDrawable(ColorDrawable(ground))
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
 * Metric until somebody says otherwise. On the theme because nearly every leaf
 * that draws a figure wants it and nothing in between does.
 */
val LocalUnits = staticCompositionLocalOf { Preferences() }

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
