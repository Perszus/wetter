package lv.bolwarra.wetter.domain.model

import lv.bolwarra.wetter.domain.climate.ClimateNormals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The difference between measurable and worth saying.
 *
 * Measured against aerodrome reports over 1295 hours at ten airports: an hour
 * the model placed in the trace band was actually precipitating at the station
 * 53% of the time, and an hour at light or above 79% of the time. A sentence
 * built on the first of those is a coin flip stated as a fact.
 *
 * So there are two bars and they are deliberately different heights. The curve
 * draws anything measurable, because something is falling and the chart is a
 * picture of the data. The words and the marks wait for rain.
 */
class WorthNamingTest {

    @Test
    fun `the two bars sit where they are supposed to`() {
        // Measurable, and not rain.
        assertTrue(PrecipitationIntensity.ofRate(0.1).isWet)
        assertFalse(PrecipitationIntensity.ofRate(0.1).isWorthNaming)
        assertTrue(PrecipitationIntensity.ofRate(0.4).isWet)
        assertFalse(PrecipitationIntensity.ofRate(0.4).isWorthNaming)

        // Rain.
        assertTrue(PrecipitationIntensity.ofRate(0.5).isWorthNaming)
        assertTrue(PrecipitationIntensity.ofRate(3.0).isWorthNaming)
        assertTrue(PrecipitationIntensity.ofRate(60.0).isWorthNaming)

        // Neither.
        assertFalse(PrecipitationIntensity.ofRate(0.05).isWet)
        assertFalse(PrecipitationIntensity.ofRate(0.05).isWorthNaming)
        assertFalse(PrecipitationIntensity.ofRate(null).isWorthNaming)
    }

    @Test
    fun `anything worth naming is also measurable`() {
        // The bars must nest. A level that was worth saying but not wet would be
        // the app naming something it also draws as nothing.
        PrecipitationIntensity.entries.forEach { level ->
            if (level.isWorthNaming) {
                assertTrue("$level is worth naming but not wet", level.isWet)
            }
        }
    }

    @Test
    fun `a day's total is judged as a total, not as a rate`() {
        // The month page asked an hourly-rate scale about a whole day's
        // accumulation, so a tenth of a millimetre spread over twenty-four hours
        // washed the square - while the climatology squares beside it used the
        // conventional rain-day line. One grid, two definitions of wet.
        //
        // These two constants are in different units on purpose and must not be
        // confused again; this pins the gap between them.
        assertEquals(0.1, PrecipitationIntensity.TRACE_MM_PER_HOUR, 0.0001)
        assertEquals(1.0, ClimateNormals.WET_DAY_MM, 0.0001)
        assertTrue(
            "a rain day must be a higher bar than a trace hour",
            ClimateNormals.WET_DAY_MM > PrecipitationIntensity.TRACE_MM_PER_HOUR,
        )
    }
}
