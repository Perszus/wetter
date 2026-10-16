// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Every colour in this app, generated rather than chosen.
 *
 * A tone is declared in the terms it is reasoned about (lightness, chroma, hue)
 * and converted to a screen value by arithmetic. sRGB values are not perceptual,
 * so hand-picked hexes drift in contrast; declaring "L* 44" fixes the contrast
 * against a known ground whatever the hue.
 *
 * The space is CIE L*C*h, CIELAB in polar form: L* is lightness in perceptually
 * even steps, C* the distance from grey, h the hue angle.
 */
object Tone {

    /**
     * The hue every neutral is built on. The greys carry a trace of blue, too little
     * to call blue, so they read as material, and the one saturated hue sits in the
     * surface rather than on it.
     */
    const val NEUTRAL_HUE = 250.0

    /** How far the neutrals stray from grey. Two units: felt, not seen. */
    const val NEUTRAL_CHROMA = 2.4

    /**
     * A tone, from lightness and optional colour.
     *
     * @param lightness CIE L*, 0 (black) to 100 (white). Neither end is used.
     * @param chroma CIE C*. Zero is a true grey; the neutrals sit at 2.4; the
     *   loudest thing in the app is under 50.
     * @param hue CIE h, degrees.
     */
    fun of(lightness: Double, chroma: Double = NEUTRAL_CHROMA, hue: Double = NEUTRAL_HUE): Color {
        val radians = Math.toRadians(hue)
        return fromLab(lightness, chroma * cos(radians), chroma * sin(radians))
    }

    /**
     * The lightness that sits at a given contrast ratio against a ground. Contrast is
     * a statement about luminance, so it is solved for, and holds when the hue
     * changes.
     *
     * @param ratio the WCAG contrast ratio wanted, 1..21.
     * @param darker true when the answer should be darker than the ground.
     */
    fun lightnessFor(groundLightness: Double, ratio: Double, darker: Boolean): Double {
        val ground = luminanceOf(groundLightness)
        val target = if (darker) {
            (ground + OFFSET) / ratio - OFFSET
        } else {
            ratio * (ground + OFFSET) - OFFSET
        }
        return lightnessOf(target.coerceIn(0.0, 1.0))
    }

    /** WCAG relative luminance of a tone at this lightness. */
    fun luminanceOf(lightness: Double): Double {
        val y = if (lightness > KAPPA_KNEE) {
            ((lightness + 16.0) / 116.0).pow(3.0)
        } else {
            lightness / KAPPA
        }
        return y
    }

    /** The inverse: the lightness that carries a given luminance. */
    fun lightnessOf(luminance: Double): Double =
        if (luminance > EPSILON) 116.0 * cbrt(luminance) - 16.0 else luminance * KAPPA

    /** The WCAG ratio between two lightnesses, for the tests that police this. */
    fun contrast(a: Double, b: Double): Double {
        val hi = maxOf(luminanceOf(a), luminanceOf(b))
        val lo = minOf(luminanceOf(a), luminanceOf(b))
        return (hi + OFFSET) / (lo + OFFSET)
    }

    // CIELAB to sRGB, D65.

    private fun fromLab(l: Double, a: Double, b: Double): Color {
        val fy = (l + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0

        val x = WHITE_X * inverseF(fx)
        val y = WHITE_Y * inverseF(fy)
        val z = WHITE_Z * inverseF(fz)

        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z

        return Color(gamma(r), gamma(g), gamma(bl), 1f)
    }

    private fun inverseF(t: Double): Double =
        if (t * t * t > EPSILON) t * t * t else (116.0 * t - 16.0) / KAPPA

    /**
     * Linear light to sRGB, clamped, so an out-of-gamut tone cannot wrap round to an
     * unintended colour. Every tone here sits well inside the gamut.
     */
    private fun gamma(channel: Double): Float {
        val v = channel.coerceIn(0.0, 1.0)
        val encoded = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
        return encoded.toFloat().coerceIn(0f, 1f)
    }

    /** WCAG's constant, which keeps the ratio finite at black. */
    private const val OFFSET = 0.05

    private const val EPSILON = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0
    private const val KAPPA_KNEE = 8.0

    private const val WHITE_X = 0.95047
    private const val WHITE_Y = 1.0
    private const val WHITE_Z = 1.08883
}
