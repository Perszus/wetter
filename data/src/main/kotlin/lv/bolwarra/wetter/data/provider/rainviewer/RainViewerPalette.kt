// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.provider.rainviewer

import kotlin.math.pow

/**
 * Turns a radar tile pixel into a rainfall rate.
 *
 * The tiles carry no numbers, only a colour scale with no published meaning, so
 * its order was established by measurement: a translucent tan ramp, then opaque
 * cool colours (dark blue to pale cyan), then warm ones (dark red to yellow), with
 * magenta on top. Storms are stratified, and around measured cores each family
 * sat inside the one below it, with the translucent ramp's alpha rising towards
 * the cores.
 *
 * The absolute calibration is not established; the span is the conventional one
 * for a radar composite ([MIN_DBZ], [MAX_DBZ]). Rates are good for shape (where,
 * which way, growing or not) and approximate in magnitude, and the fusion weights
 * them accordingly.
 */
internal object RainViewerPalette {

    /**
     * The span the colour scale is read across. The top is capped at 55 dBZ: above
     * that the echo is hail or a melting layer, and Marshall-Palmer, a rain relation,
     * would read it as over 1000 mm/h. The cap puts the top near 100 mm/h.
     */
    const val MIN_DBZ = 5.0
    const val MAX_DBZ = 55.0

    /** Marshall-Palmer, the standard relation between reflectivity and rate. */
    private const val Z_A = 200.0
    private const val Z_B = 1.6

    /** Where each family ends, as a fraction of the whole scale. */
    private const val TRANSLUCENT_TOP = 0.37
    private const val COOL_TOP = 0.67
    private const val WARM_TOP = 0.97

    /** Measured bounds of the translucent ramp's alpha. */
    private const val FAINTEST_ALPHA = 20.0
    private const val STRONGEST_ALPHA = 190.0

    /** Measured luminance bounds within each opaque family. */
    private const val COOL_MIN_LUMA = 53.0
    private const val COOL_MAX_LUMA = 198.0
    private const val WARM_MIN_LUMA = 28.0
    private const val WARM_MAX_LUMA = 216.0

    /** Red and blue both at least this high means the magenta topping the scale. */
    private const val MAGENTA_CHANNEL = 200

    /**
     * Millimetres per hour for one pixel, from packed ARGB.
     *
     * A fully transparent pixel is 0.0, not unobserved: this source draws "no echo"
     * and "outside coverage" identically, so within the composite transparent is read
     * as dry. [lv.bolwarra.wetter.domain.radar.RadarField.hasEchoNear] guards the
     * places it cannot see.
     */
    fun rateOf(argb: Int): Float {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return 0f

        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF

        val fraction = fractionOf(alpha, red, green, blue)
        val dbz = MIN_DBZ + fraction * (MAX_DBZ - MIN_DBZ)
        return millimetresPerHour(dbz).toFloat()
    }

    /** Where a colour sits on the scale, 0 at the faintest trace and 1 at hail. */
    fun fractionOf(alpha: Int, red: Int, green: Int, blue: Int): Double {
        if (alpha < 255) {
            val within = ((alpha - FAINTEST_ALPHA) / (STRONGEST_ALPHA - FAINTEST_ALPHA))
                .coerceIn(0.0, 1.0)
            return within * TRANSLUCENT_TOP
        }

        val luma = 0.299 * red + 0.587 * green + 0.114 * blue
        // Magenta first: with red and blue both at full, the cool family's test would
        // otherwise claim it.
        if (red >= MAGENTA_CHANNEL && blue >= MAGENTA_CHANNEL) {
            val within = (green / 255.0).coerceIn(0.0, 1.0)
            return WARM_TOP + within * (1.0 - WARM_TOP)
        }
        // Cool means blue is at least as strong as red: the blues and cyans.
        if (blue >= red) {
            val within = ((luma - COOL_MIN_LUMA) / (COOL_MAX_LUMA - COOL_MIN_LUMA))
                .coerceIn(0.0, 1.0)
            return TRANSLUCENT_TOP + within * (COOL_TOP - TRANSLUCENT_TOP)
        }
        val within = ((luma - WARM_MIN_LUMA) / (WARM_MAX_LUMA - WARM_MIN_LUMA))
            .coerceIn(0.0, 1.0)
        return COOL_TOP + within * (WARM_TOP - COOL_TOP)
    }

    /** Marshall-Palmer inverted: Z = 200 R^1.6, so R = (Z/200)^(1/1.6). */
    fun millimetresPerHour(dbz: Double): Double {
        val z = 10.0.pow(dbz / 10.0)
        return (z / Z_A).pow(1.0 / Z_B)
    }
}
