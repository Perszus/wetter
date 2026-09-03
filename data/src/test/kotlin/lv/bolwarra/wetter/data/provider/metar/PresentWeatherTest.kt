package lv.bolwarra.wetter.data.provider.metar

import lv.bolwarra.wetter.domain.observation.ObservedIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The groups here are taken from real reports, including the two that were
 * actually falling over Denmark and Germany when this was written.
 */
class PresentWeatherTest {

    @Test
    fun `an absent group is unknown, not dry`() {
        // Fed into verification, treating a missing field as a confirmed dry
        // hour would credit forecasts for predicting weather nobody observed.
        assertNull(PresentWeather.precipitationFrom(null))
        assertNull(PresentWeather.precipitationFrom(""))
        assertNull(PresentWeather.precipitationFrom("   "))
    }

    @Test
    fun `rain in its various forms is rain`() {
        listOf("-RA", "RA", "+RA", "-RA BR", "SHRA", "TSRA", "-FZRA", "RASN").forEach {
            assertTrue("$it should be precipitation", PresentWeather.precipitationFrom(it)!!)
        }
    }

    @Test
    fun `snow, drizzle, hail and the unidentified all count`() {
        // The question is whether you get wet, so an automated station that can
        // tell something is falling but not what still counts.
        listOf("-SN", "+SN", "DZ", "-DZ", "GR", "GS", "PL", "UP", "SG").forEach {
            assertTrue("$it should be precipitation", PresentWeather.precipitationFrom(it)!!)
        }
    }

    @Test
    fun `mist and fog are present weather but nothing is falling`() {
        listOf("BR", "FG", "HZ", "FU", "BCFG", "MIFG", "DU", "SQ").forEach {
            assertFalse("$it should not be precipitation", PresentWeather.precipitationFrom(it)!!)
        }
    }

    @Test
    fun `rain in the vicinity is not rain here`() {
        // Visible from the station and not falling on it. Counting it would put
        // an observation of rain at a place where none fell.
        assertFalse(PresentWeather.precipitationFrom("VCSH")!!)
        assertFalse(PresentWeather.precipitationFrom("VCTS")!!)
        assertNull(PresentWeather.intensityFrom("VCSH"))
    }

    @Test
    fun `the prefix gives the intensity, and its absence means moderate`() {
        assertEquals(ObservedIntensity.LIGHT, PresentWeather.intensityFrom("-RA"))
        assertEquals(ObservedIntensity.MODERATE, PresentWeather.intensityFrom("RA"))
        assertEquals(ObservedIntensity.HEAVY, PresentWeather.intensityFrom("+RA"))
        assertEquals(ObservedIntensity.LIGHT, PresentWeather.intensityFrom("-SHRA"))
    }

    @Test
    fun `intensity comes from the falling part, not from whatever is first`() {
        // "BR -RA" leads with mist. The intensity belongs to the rain.
        assertEquals(ObservedIntensity.LIGHT, PresentWeather.intensityFrom("BR -RA"))
        assertEquals(ObservedIntensity.HEAVY, PresentWeather.intensityFrom("FG +SN"))
    }

    @Test
    fun `nothing falling has no intensity`() {
        assertNull(PresentWeather.intensityFrom("BR"))
        assertNull(PresentWeather.intensityFrom(null))
        assertNull(PresentWeather.intensityFrom(""))
    }

    @Test
    fun `real reports parse the way they read`() {
        // Odense, in light rain and mist.
        assertTrue(PresentWeather.precipitationFrom("-RA BR")!!)
        assertEquals(ObservedIntensity.LIGHT, PresentWeather.intensityFrom("-RA BR"))

        // Lubeck, light rain.
        assertTrue(PresentWeather.precipitationFrom("-RA")!!)

        // Riga, nothing significant at all.
        assertNull(PresentWeather.precipitationFrom(null))
    }
}
