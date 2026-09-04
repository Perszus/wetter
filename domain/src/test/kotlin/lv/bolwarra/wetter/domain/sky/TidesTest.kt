package lv.bolwarra.wetter.domain.sky

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.MoonPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TidesTest {

    /** A known new moon: the reference the phase model is anchored to. */
    private val newMoon = Instant.parse("2000-01-06T18:14:00Z")

    private fun daysOn(days: Double) = newMoon.plusSeconds((days * 86_400).toLong())

    private val quarter = MoonPhase.SYNODIC_DAYS / 4
    private val half = MoonPhase.SYNODIC_DAYS / 2

    @Test
    fun `new and full moon both give spring tides`() {
        assertEquals(TideState.SPRING, Tides.stateAt(newMoon))
        assertEquals(TideState.SPRING, Tides.stateAt(daysOn(half)))
    }

    @Test
    fun `both quarters give neap tides`() {
        assertEquals(TideState.NEAP, Tides.stateAt(daysOn(quarter)))
        assertEquals(TideState.NEAP, Tides.stateAt(daysOn(quarter * 3)))
    }

    @Test
    fun `there are two spring tides in a month, not one`() {
        // The whole point of doubling the angle. A model that used the moon's
        // elongation directly would put one spring tide a month at the new moon
        // and call the full moon a neap, which is backwards.
        assertEquals(1.0, Tides.strengthAt(newMoon), 1e-6)
        assertEquals(1.0, Tides.strengthAt(daysOn(half)), 1e-6)
        assertEquals(0.0, Tides.strengthAt(daysOn(quarter)), 1e-6)
    }

    @Test
    fun `between the extremes it says which way it is going`() {
        // Leaving the new moon, heading for the first quarter: easing off.
        assertEquals(TideState.EASING, Tides.stateAt(daysOn(3.5)))
        // Leaving the first quarter, heading for the full moon: building.
        assertEquals(TideState.BUILDING, Tides.stateAt(daysOn(quarter + 3.5)))
    }

    @Test
    fun `strength stays inside its bounds all month`() {
        var day = 0.0
        while (day < MoonPhase.SYNODIC_DAYS) {
            val strength = Tides.strengthAt(daysOn(day))
            assertTrue("$day: $strength", strength in 0.0..1.0)
            day += 0.25
        }
    }

    @Test
    fun `the rhythm repeats with the moon`() {
        val later = newMoon.plus(Duration.ofSeconds((MoonPhase.SYNODIC_DAYS * 86_400).toLong()))
        assertEquals(Tides.strengthAt(newMoon), Tides.strengthAt(later), 1e-6)
    }
}
