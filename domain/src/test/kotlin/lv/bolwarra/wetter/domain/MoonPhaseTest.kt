package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checked against real new and full moons, not against the formula restated.
 *
 * A test that recomputes the same arithmetic proves only that it is
 * deterministic. These are published dates: if the reference epoch, the synodic
 * month or the sign handling is wrong, they fail.
 */
class MoonPhaseTest {

    /** Tolerance in days. The mean synodic month is worth about this much. */
    private val slack = 0.6

    private fun daysFromNew(instant: Instant): Double {
        val fraction = MoonPhase.fractionAt(instant)
        val days = fraction * MoonPhase.SYNODIC_DAYS
        // Distance to the nearest new moon, in either direction round the cycle.
        return minOf(days, MoonPhase.SYNODIC_DAYS - days)
    }

    @Test
    fun `known new moons come out new`() {
        // Published new moons.
        listOf(
            "2024-01-11T11:57:00Z",
            "2024-08-04T11:13:00Z",
            "2025-03-29T10:58:00Z",
        ).forEach { moment ->
            val off = daysFromNew(Instant.parse(moment))
            assertTrue("$moment was $off days from new", off < slack)
        }
    }

    @Test
    fun `known full moons come out full`() {
        listOf(
            "2024-01-25T17:54:00Z",
            "2024-08-19T18:26:00Z",
            "2025-03-14T06:55:00Z",
        ).forEach { moment ->
            val fraction = MoonPhase.fractionAt(Instant.parse(moment))
            val off = Math.abs(fraction - 0.5) * MoonPhase.SYNODIC_DAYS
            assertTrue("$moment was $off days from full", off < slack)
            assertEquals(MoonPhaseName.FULL, MoonPhase.nameAt(Instant.parse(moment)))
        }
    }

    @Test
    fun `illumination runs from dark to lit and back`() {
        val newMoon = Instant.parse("2024-01-11T11:57:00Z")
        assertTrue(MoonPhase.illuminationAt(newMoon) < 0.02)

        val full = newMoon.plus(Duration.ofHours((MoonPhase.SYNODIC_DAYS * 12).toLong()))
        assertTrue(MoonPhase.illuminationAt(full) > 0.98)

        val quarter = newMoon.plus(Duration.ofHours((MoonPhase.SYNODIC_DAYS * 6).toLong()))
        assertEquals(0.5, MoonPhase.illuminationAt(quarter), 0.05)
    }

    @Test
    fun `dates before the reference epoch still run forwards`() {
        // Kotlin's rem keeps the dividend's sign, so a naive modulo would send
        // the cycle backwards for anything before 2000 and yield a negative
        // fraction. A 1969 new moon is the check.
        val apollo = Instant.parse("1969-07-14T21:00:00Z")
        assertTrue(MoonPhase.fractionAt(apollo) in 0.0..1.0)
        assertTrue("was ${daysFromNew(apollo)} days from new", daysFromNew(apollo) < slack)
    }

    @Test
    fun `the cycle is continuous across the wrap`() {
        // Anchored to the model's own zero crossing rather than a published new
        // moon. This is testing the wrap arithmetic, and the mean synodic month
        // puts its idea of new up to half a day off the real one - enough to
        // fail a tight bound here for a reason that has nothing to do with the
        // wrap. Epoch accuracy is what the published-date tests above are for.
        val exact = Instant.parse("2000-01-06T18:14:00Z")
            .plus(Duration.ofSeconds((MoonPhase.SYNODIC_DAYS * 300 * 86_400).toLong()))

        val justBefore = MoonPhase.fractionAt(exact.minus(Duration.ofHours(2)))
        val justAfter = MoonPhase.fractionAt(exact.plus(Duration.ofHours(2)))

        // One just under 1, the other just over 0, with no jump in between.
        assertTrue("expected near 1, was $justBefore", justBefore > 0.99)
        assertTrue("expected near 0, was $justAfter", justAfter < 0.01)
        assertEquals(MoonPhaseName.NEW, MoonPhase.nameAt(exact.minus(Duration.ofHours(2))))
        assertEquals(MoonPhaseName.NEW, MoonPhase.nameAt(exact.plus(Duration.ofHours(2))))
    }

    @Test
    fun `every eighth of the cycle gets a distinct name`() {
        val newMoon = Instant.parse("2024-01-11T11:57:00Z")
        val seen = (0 until 8).map { eighth ->
            val hours = (MoonPhase.SYNODIC_DAYS * 24 * eighth / 8).toLong()
            MoonPhase.nameAt(newMoon.plus(Duration.ofHours(hours)))
        }
        assertEquals(MoonPhaseName.entries.toList(), seen)
    }
}
