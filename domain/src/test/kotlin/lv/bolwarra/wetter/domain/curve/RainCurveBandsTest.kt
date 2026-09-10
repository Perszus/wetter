package lv.bolwarra.wetter.domain.curve

import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one thing the app's chart and the home-screen widget must agree about.
 *
 * They are drawn by different code against different graphics APIs, at sizes
 * five times apart, and only one of them has room to name the level in words. If
 * they place the same rate at different heights, or sort it into different
 * bands, the reader has two pictures of one afternoon and no way to tell which
 * is lying - which is worse than having only the app.
 */
class RainCurveBandsTest {

    @Test
    fun `dry is on the floor`() {
        assertEquals(0f, RainCurveBands.heightFraction(0f), 1e-6f)
        assertEquals(PrecipitationIntensity.NONE, RainCurveBands.levelOf(0.0))
    }

    @Test
    fun `the least rain there is steps clear of the floor`() {
        // The question the chart is asked most is whether it will rain at all,
        // and a drizzle drawn at two percent of the height answers it wrong.
        val trace = RainCurveBands.heightFraction(
            PrecipitationIntensity.TRACE_MM_PER_HOUR.toFloat(),
        )
        assertTrue("a trace should be visible, was $trace", trace > 0.05f)
        assertTrue("a trace should still be near the floor, was $trace", trace < 0.2f)
    }

    @Test
    fun `height never falls as the rate climbs`() {
        var last = -1f
        var mm = 0.0
        while (mm <= 60.0) {
            val height = RainCurveBands.heightFraction(mm.toFloat())
            assertTrue("height fell at $mm mm/h: $last then $height", height >= last - 1e-6f)
            last = height
            mm += 0.05
        }
    }

    @Test
    fun `the scale has a top and stays on it`() {
        assertEquals(1f, RainCurveBands.heightFraction(50f), 1e-6f)
        assertEquals(1f, RainCurveBands.heightFraction(500f), 1e-6f)
    }

    @Test
    fun `the band edges are where the level changes`() {
        // Both painters put something at these two heights - a guide line, a
        // colour step - and the curve has to cross them at the same rate the
        // level does, or the line changes colour somewhere the reader can see
        // is not the boundary.
        val moderate = PrecipitationIntensity.MODERATE_MM_PER_HOUR
        val heavy = PrecipitationIntensity.HEAVY_MM_PER_HOUR

        assertEquals(
            RainCurveBands.heightFraction(moderate.toFloat()),
            RainCurveBands.moderateEdge,
            1e-6f,
        )
        assertEquals(
            RainCurveBands.heightFraction(heavy.toFloat()),
            RainCurveBands.heavyEdge,
            1e-6f,
        )

        assertEquals(PrecipitationIntensity.LIGHT, RainCurveBands.levelOf(moderate - 0.01))
        assertEquals(PrecipitationIntensity.MODERATE, RainCurveBands.levelOf(moderate))
        assertEquals(PrecipitationIntensity.MODERATE, RainCurveBands.levelOf(heavy - 0.01))
        assertEquals(PrecipitationIntensity.HEAVY, RainCurveBands.levelOf(heavy))
    }

    @Test
    fun `the edges sit in order and leave every band room to be seen`() {
        val light = RainCurveBands.moderateEdge
        val moderate = RainCurveBands.heavyEdge - RainCurveBands.moderateEdge
        val heavy = 1f - RainCurveBands.heavyEdge

        val bands = listOf(
            "light" to light,
            "moderate" to moderate,
            "heavy" to heavy,
        )
        bands.forEach { (name, share) ->
            assertTrue("$name band got $share of the track", share > 0.15f)
        }
        assertEquals(1f, light + moderate + heavy, 1e-6f)
    }

    @Test
    fun `a trace is folded into light, not given a band of its own`() {
        // "Barely" was a level nobody does anything differently about. The
        // bottom band means anything from a spit to a proper shower.
        assertEquals(
            PrecipitationIntensity.LIGHT,
            RainCurveBands.levelOf(PrecipitationIntensity.TRACE_MM_PER_HOUR),
        )
        assertEquals(
            PrecipitationIntensity.LIGHT,
            RainCurveBands.levelOf(PrecipitationIntensity.LIGHT_MM_PER_HOUR),
        )
    }

    @Test
    fun `the short-track lift moves nothing across a band edge`() {
        // The widget's bitmap and the week's hour strip apply spreadWithinLight;
        // the tall chart does not. That is allowed to change where a drizzle
        // sits and is never allowed to change which band it sits in - otherwise
        // two surfaces drawn from one forecast disagree about whether an hour is
        // light or moderate, which is the whole thing RainCurveBands exists to
        // prevent.
        //
        // The guarantee is that both ends of the light band are fixed points.
        assertEquals(0f, RainCurveBands.spreadWithinLight(0f), 0.0001f)
        assertEquals(
            "the light ceiling must not move",
            RainCurveBands.moderateEdge,
            RainCurveBands.spreadWithinLight(RainCurveBands.moderateEdge),
            0.0001f,
        )

        // And above it nothing is touched at all.
        listOf(RainCurveBands.heavyEdge, 0.9f, 1f).forEach {
            assertEquals(
                "$it should be untouched",
                it,
                RainCurveBands.spreadWithinLight(it),
                0.0001f,
            )
        }
    }

    @Test
    fun `the lift only ever raises, and never overtakes itself`() {
        // Monotonic, so the order of two hours is the same on every surface, and
        // one-directional, since the point is to lift a drizzle off the floor.
        var previous = -1f
        var step = 0
        while (step <= 100) {
            val fraction = step / 100f
            val lifted = RainCurveBands.spreadWithinLight(fraction)
            assertTrue(
                "$fraction lifted to $lifted, which is below it",
                lifted >= fraction - 0.0001f,
            )
            assertTrue("$fraction lifted out of 0..1 as $lifted", lifted in 0f..1f)
            assertTrue("the lift went backwards at $fraction", lifted >= previous)
            previous = lifted
            step++
        }
    }

    @Test
    fun `every band keeps the same height on a lifted surface`() {
        // The three levels are a scale of the words somebody acts on. A scale
        // whose steps are different sizes on the widget than on the chart asks
        // the reader to remember which surface they are looking at.
        val chart = listOf(
            RainCurveBands.moderateEdge,
            RainCurveBands.heavyEdge - RainCurveBands.moderateEdge,
            1f - RainCurveBands.heavyEdge,
        )
        val lifted = listOf(
            RainCurveBands.spreadWithinLight(RainCurveBands.moderateEdge),
            RainCurveBands.spreadWithinLight(RainCurveBands.heavyEdge) -
                RainCurveBands.spreadWithinLight(RainCurveBands.moderateEdge),
            1f - RainCurveBands.spreadWithinLight(RainCurveBands.heavyEdge),
        )
        chart.zip(lifted).forEachIndexed { index, (a, b) ->
            assertEquals("band $index is a different height on the two surfaces", a, b, 0.0001f)
        }
    }
}
