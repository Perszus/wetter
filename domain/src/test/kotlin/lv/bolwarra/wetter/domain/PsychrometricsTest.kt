package lv.bolwarra.wetter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PsychrometricsTest {

    @Test
    fun `saturated air has its dew point at the temperature`() {
        // The definition: at 100% humidity the air is already saturated.
        assertEquals(11.0, Psychrometrics.dewPoint(11.0, 100)!!, 0.05)
        assertEquals(-4.0, Psychrometrics.dewPoint(-4.0, 100)!!, 0.05)
    }

    @Test
    fun `known pairs come out right`() {
        // Commonly published dew points for these conditions. 20 C at half
        // humidity being a shade over 9 is the one most often quoted, and is the
        // check that the constants are the Magnus pair and not some other
        // fitting of the same curve.
        assertEquals(9.3, Psychrometrics.dewPoint(20.0, 50)!!, 0.2)
        assertEquals(21.4, Psychrometrics.dewPoint(30.0, 60)!!, 0.2)
        assertEquals(-4.6, Psychrometrics.dewPoint(5.0, 50)!!, 0.2)
    }

    @Test
    fun `dew point is never above the temperature`() {
        for (temperature in -30..40) {
            for (humidity in 1..100) {
                val dew = Psychrometrics.dewPoint(temperature.toDouble(), humidity)!!
                assertTrue(
                    "dew $dew above air $temperature at $humidity%",
                    dew <= temperature + 0.001,
                )
            }
        }
    }

    @Test
    fun `drier air has a lower dew point`() {
        val damp = Psychrometrics.dewPoint(15.0, 80)!!
        val dry = Psychrometrics.dewPoint(15.0, 30)!!
        assertTrue("$dry should be below $damp", dry < damp)
    }

    @Test
    fun `missing or impossible readings give nothing rather than a number`() {
        assertNull(Psychrometrics.dewPoint(null, 50))
        assertNull(Psychrometrics.dewPoint(12.0, null))
        // Zero humidity has no dew point; the logarithm runs to negative infinity.
        assertNull(Psychrometrics.dewPoint(12.0, 0))
        assertNull(Psychrometrics.dewPoint(12.0, -5))
        assertNull(Psychrometrics.dewPoint(12.0, 140))
    }

    @Test
    fun `compass names are centred on their bearing, not started at it`() {
        // North spans the wrap, so it is the case a naive division gets wrong.
        assertEquals(CompassPoint.NORTH, CompassPoint.of(0))
        assertEquals(CompassPoint.NORTH, CompassPoint.of(359))
        assertEquals(CompassPoint.NORTH, CompassPoint.of(340))
        assertEquals(CompassPoint.NORTH, CompassPoint.of(20))
        assertEquals(CompassPoint.NORTH_EAST, CompassPoint.of(45))
        assertEquals(CompassPoint.EAST, CompassPoint.of(90))
        assertEquals(CompassPoint.SOUTH, CompassPoint.of(180))
        assertEquals(CompassPoint.WEST, CompassPoint.of(270))
        assertEquals(CompassPoint.NORTH_WEST, CompassPoint.of(315))
    }

    @Test
    fun `bearings outside a single turn still resolve`() {
        assertEquals(CompassPoint.EAST, CompassPoint.of(450))
        assertEquals(CompassPoint.WEST, CompassPoint.of(-90))
    }
}
