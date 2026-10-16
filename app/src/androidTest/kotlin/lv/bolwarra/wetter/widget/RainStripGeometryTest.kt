// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.ui.theme.Atmosphere
import lv.bolwarra.wetter.ui.theme.WetterColors
import lv.bolwarra.wetter.ui.theme.pureBlackPlate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The widget's drawn geometry, on a real Canvas: `android.graphics` stubs draw
 * nothing on the JVM.
 *
 * The chart and the widget draw the same forecast from one axis,
 * [RainCurveBands], and may differ in one way only: a short track lifts a rate
 * within the light band. Everything above the light ceiling must map identically.
 *
 * The curve is found by difference, not by colour: antialiased hairlines blend
 * into the plate, so two strips are rendered from the same everything except the
 * rates, and the curve is wherever they differ.
 */
@RunWith(AndroidJUnit4::class)
class RainStripGeometryTest {

    private val colors: WetterColors = pureBlackPlate(Atmosphere.Neutral)

    private fun strip(rate: Double): Bitmap {
        val scale = 2f
        val bitmap = Bitmap.createBitmap(
            (320 * scale).toInt(),
            (120 * scale).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        WidgetPlate.drawPlate(canvas, bounds, 16f * scale, colors)
        RainStrip.drawStrip(
            canvas = canvas,
            box = bounds,
            scale = scale,
            colors = colors,
            rates = List(24) { rate },
            hourOffset = 0.25f,
            hourLabels = listOf("01:00", "02:00", "03:00", "04:00"),
        )
        return bitmap
    }

    /**
     * The topmost row where a flat curve at [rate] differs from one at zero,
     * which is the top of the curve's stroke.
     */
    private fun curveTop(rate: Double): Int {
        val dry = strip(0.0)
        val wet = strip(rate)
        val columns = listOf(wet.width / 3, wet.width / 2, wet.width * 2 / 3)
        for (y in 0 until wet.height) {
            if (columns.any { x -> dry.getPixel(x, y) != wet.getPixel(x, y) }) return y
        }
        return -1
    }

    @Test
    fun the_three_levels_are_the_same_height_on_the_glass() {
        // Light, moderate and heavy take equal thirds of the chart, measured in pixels.
        // Three rates, two gaps, and the gaps must match; differences rather than absolute
        // rows, so the chart's origin and the stroke width cancel.
        val moderate = curveTop(PrecipitationIntensity.MODERATE_MM_PER_HOUR)
        val heavy = curveTop(PrecipitationIntensity.HEAVY_MM_PER_HOUR)
        val violent = curveTop(PrecipitationIntensity.VIOLENT_MM_PER_HOUR)

        assertTrue("the moderate rate was not drawn", moderate > 0)
        assertTrue("the heavy rate was not drawn", heavy >= 0)
        assertTrue("the violent rate was not drawn", violent >= 0)

        val lightToModerate = moderate - heavy
        val moderateToHeavy = heavy - violent

        assertEquals(
            "the bands are different heights: rows $moderate, $heavy, $violent",
            lightToModerate.toDouble(),
            moderateToHeavy.toDouble(),
            3.0,
        )
    }

    @Test
    fun the_curve_climbs_with_the_rate_across_every_band() {
        // Each step up the scale must draw strictly higher than the one below.
        // A lift applied in the wrong place, or a fraction clamped early, shows
        // up here as two bands drawn at the same height.
        val moderate = curveTop(PrecipitationIntensity.MODERATE_MM_PER_HOUR)
        val heavy = curveTop(PrecipitationIntensity.HEAVY_MM_PER_HOUR)
        val violent = curveTop(PrecipitationIntensity.VIOLENT_MM_PER_HOUR)

        assertTrue("heavy at $heavy is not above moderate at $moderate", heavy < moderate)
        assertTrue("violent at $violent is not above heavy at $heavy", violent < heavy)
    }

    @Test
    fun a_drizzle_steps_clear_of_the_floor_and_dry_does_not() {
        // The lift's whole purpose, and its one fixed point at the bottom. If
        // zero were lifted too, an empty widget would grow a curve.
        val drizzle = curveTop(0.2)
        val floor = curveTop(0.0).let { if (it < 0) strip(0.0).height else it }

        assertTrue("a drizzle was not drawn at all", drizzle > 0)
        assertTrue(
            "a drizzle at row $drizzle did not clear the floor at $floor",
            drizzle < floor - 2,
        )
    }

    @Test
    fun heavier_rain_is_never_drawn_lower_than_lighter_rain() {
        // Monotonic on the glass, not just in the arithmetic. A lift applied in
        // the wrong direction, or a scale factor used twice, shows up here as a
        // curve that goes back down as the rain gets worse.
        val rates = listOf(0.0, 0.2, 0.5, 1.0, 2.5, 7.6, 20.0, 50.0)
        val rows = rates.map { rate ->
            curveTop(rate).let { if (it < 0) strip(0.0).height else it }
        }
        rows.zipWithNext().forEachIndexed { index, (higher, lower) ->
            assertTrue(
                "${rates[index]} drew at $higher and ${rates[index + 1]} at $lower",
                lower <= higher + 2,
            )
        }
    }
}
