// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.curve

import kotlin.math.sqrt
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity

/**
 * The vertical axis of every rain curve Wetter draws: given a rate, how high up
 * the track it sits and which level it is in. Geometry only, shared by the app's
 * chart and the widgets so both put the same hour at the same height.
 *
 * Not a scale of millimetres: the bands are wildly unevenly spaced (a trace is a
 * tenth of a millimetre, torrential fifty), so the axis is anchored to the
 * intensity bands, each given an equal slice of the height. It is linear in how
 * wet you get, not in millimetres, and carries no numbers.
 */
object RainCurveBands {

    /**
     * The level the curve names a rate: coarser than the domain's own. A trace is part
     * of light, and violent rain shares the top band with heavy rather than reserving
     * height for weather that almost never arrives.
     */
    fun levelOf(millimetresPerHour: Double): PrecipitationIntensity = when {
        millimetresPerHour < PrecipitationIntensity.TRACE_MM_PER_HOUR ->
            PrecipitationIntensity.NONE

        millimetresPerHour < PrecipitationIntensity.MODERATE_MM_PER_HOUR ->
            PrecipitationIntensity.LIGHT

        millimetresPerHour < PrecipitationIntensity.HEAVY_MM_PER_HOUR ->
            PrecipitationIntensity.MODERATE

        else -> PrecipitationIntensity.HEAVY
    }

    /** Where a rate sits on the track, as a fraction of its height. */
    fun heightFraction(millimetresPerHour: Float): Float {
        val mm = millimetresPerHour.toDouble()
        if (mm <= 0.0) return 0f
        for (i in 0 until ANCHORS.size - 1) {
            val (lowMm, lowY) = ANCHORS[i]
            val (highMm, highY) = ANCHORS[i + 1]
            if (mm <= highMm) {
                val t = ((mm - lowMm) / (highMm - lowMm)).toFloat()
                // Eased across each band rather than run straight through it, so a smooth rate
                // draws no kink at the boundaries.
                return lowY + (highY - lowY) * smoothStep(t)
            }
        }
        return 1f
    }

    /**
     * The heights at which light becomes moderate, and moderate becomes heavy, for
     * the guide lines and colour steps both painters draw.
     */
    val moderateEdge: Float get() = heightFraction(MODERATE_EDGE_MM)

    val heavyEdge: Float get() = heightFraction(HEAVY_EDGE_MM)

    /**
     * The same height, spread out inside the light band, for a short track where the
     * least rain would otherwise be a pixel or two.
     *
     * The light band's ceiling is a fixed point, so the three levels stay the same
     * height; only where a rate lands within light changes. Dry stays at zero. Opt-in:
     * the widgets and the hour strip call it, the tall chart does not.
     */
    fun spreadWithinLight(fraction: Float): Float {
        val ceiling = moderateEdge
        if (fraction <= 0f || fraction >= ceiling) return fraction
        return ceiling * sqrt(fraction / ceiling)
    }

    /**
     * Dry sits on the floor and the least rain steps just clear of it, so whether it
     * will rain at all is answered without reading anything.
     */
    private val ANCHORS = listOf(
        0.0 to 0.00f,
        // The least rain there is, lifted just clear of the floor.
        PrecipitationIntensity.TRACE_MM_PER_HOUR to TRACE_FLOOR,
        PrecipitationIntensity.MODERATE_MM_PER_HOUR to LIGHT_TOP,
        PrecipitationIntensity.HEAVY_MM_PER_HOUR to MODERATE_TOP,
        PrecipitationIntensity.VIOLENT_MM_PER_HOUR to 1.00f,
    )

    /**
     * Smoothstep: flat at both ends, steepest in the middle. Local, unlike a spline,
     * so one band's height never shifts the curve inside its neighbours.
     */
    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

private const val TRACE_FLOOR = 0.08f

private const val MODERATE_EDGE_MM = PrecipitationIntensity.MODERATE_MM_PER_HOUR.toFloat()
private const val HEAVY_EDGE_MM = PrecipitationIntensity.HEAVY_MM_PER_HOUR.toFloat()

/**
 * The three levels take equal height, so how far a curve has climbed is how far
 * through the levels it is. The floor sits inside the light band: a trace is the
 * bottom of light, not a fourth level.
 */
private const val BANDS = 3f
private const val LIGHT_TOP = 1f / BANDS
private const val MODERATE_TOP = 2f / BANDS
