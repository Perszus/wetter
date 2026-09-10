package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The moon phase against dates nobody can argue with.
 *
 * Eclipses are the anchor. A solar eclipse can only happen at a new moon and a
 * lunar one only at a full moon — that is what an eclipse *is* — so their dates
 * are independently known phases that owe nothing to the model being tested.
 *
 * [MoonPhase] is a mean synodic model and says so: it carries no correction for
 * the moon's elliptical orbit, and its own documentation claims about half a day
 * of error at worst. This checks that claim rather than assuming it, because a
 * wrong epoch or a sign error would look exactly like a model that is simply
 * approximate until somebody measures it.
 */
class MoonPhaseReliabilityTest {

    private data class Eclipse(val name: String, val at: String, val isNew: Boolean)

    /** Well-catalogued eclipses, and therefore well-catalogued phases. */
    private val eclipses = listOf(
        Eclipse("annular solar, Africa/Antarctica", "2026-02-17T12:12:00Z", isNew = true),
        Eclipse("total lunar", "2026-03-03T11:33:00Z", isNew = false),
        Eclipse("total solar, Iceland and Spain", "2026-08-12T17:46:00Z", isNew = true),
        Eclipse("partial lunar", "2026-08-28T04:13:00Z", isNew = false),
        Eclipse("annular solar", "2027-02-06T15:59:00Z", isNew = true),
        Eclipse("total solar, Egypt", "2027-08-02T10:07:00Z", isNew = true),
    )

    @Test
    fun `every eclipse lands on the phase it must`() {
        val wrong = mutableListOf<String>()

        eclipses.forEach { eclipse ->
            val fraction = MoonPhase.fractionAt(Instant.parse(eclipse.at))
            val target = if (eclipse.isNew) 0.0 else 0.5
            // The cycle wraps, so being at 0.99 is being close to 0.
            val error = min(abs(fraction - target), 1.0 - abs(fraction - target))
            val hours = error * MoonPhase.SYNODIC_DAYS * 24.0

            if (hours > MAX_CLAIMED_HOURS) {
                wrong += "${eclipse.name} (${eclipse.at}): fraction %.4f, out by %.1f h"
                    .format(fraction, hours)
            }
        }

        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `illumination is none at new and all at full`() {
        val newMoon = Instant.parse("2026-08-12T17:46:00Z")
        val fullMoon = Instant.parse("2026-03-03T11:33:00Z")

        assertTrue(
            "a new moon should be dark, got ${MoonPhase.illuminationAt(newMoon)}",
            MoonPhase.illuminationAt(newMoon) < 0.02,
        )
        assertTrue(
            "a full moon should be lit, got ${MoonPhase.illuminationAt(fullMoon)}",
            MoonPhase.illuminationAt(fullMoon) > 0.98,
        )
    }

    @Test
    fun `the cycle runs forwards before the reference date as well as after`() {
        // The reference new moon is in 2000 and Kotlin's rem keeps the sign of
        // the dividend, so a date before it would run the cycle backwards
        // without the correction in fractionAt. Nothing in the app asks about
        // 1998, but the widget's clock is the phone's and a phone with a wrong
        // year is not a rare thing.
        val old = Instant.parse("1998-06-15T00:00:00Z")
        val fraction = MoonPhase.fractionAt(old)
        assertTrue("fraction $fraction is outside 0..1", fraction in 0.0..1.0)
    }

    @Test
    fun `the fraction advances at the right rate and wraps exactly once`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val oneMonthOn = start.plus(
            Duration.ofSeconds((MoonPhase.SYNODIC_DAYS * 86_400.0).toLong()),
        )
        assertEquals(
            "one synodic month must return the same phase",
            MoonPhase.fractionAt(start),
            MoonPhase.fractionAt(oneMonthOn),
            0.001,
        )

        // And it must be monotonic in between, never jumping backwards except
        // at the single wrap.
        var previous = MoonPhase.fractionAt(start)
        var wraps = 0
        for (hour in 1..(MoonPhase.SYNODIC_DAYS * 24).toInt()) {
            val here = MoonPhase.fractionAt(start.plus(Duration.ofHours(hour.toLong())))
            if (here < previous) wraps++
            previous = here
        }
        assertEquals("the cycle should wrap exactly once in a synodic month", 1, wraps)
    }

    @Test
    fun `the phase names sit in the right eighths`() {
        val newMoon = Instant.parse("2026-08-12T17:46:00Z")
        assertEquals(MoonPhaseName.NEW, MoonPhase.nameAt(newMoon))
        assertEquals(
            MoonPhaseName.FULL,
            MoonPhase.nameAt(Instant.parse("2026-03-03T11:33:00Z")),
        )
        // A week after a new moon is the first quarter, and a week after that
        // is full. This catches a waxing/waning swap, which an illumination
        // check cannot because the two are symmetric.
        assertEquals(
            MoonPhaseName.FIRST_QUARTER,
            MoonPhase.nameAt(newMoon.plus(Duration.ofDays(7))),
        )
        assertEquals(
            MoonPhaseName.LAST_QUARTER,
            MoonPhase.nameAt(newMoon.plus(Duration.ofDays(22))),
        )
    }

    private companion object {
        /**
         * The error [MoonPhase] admits to: about half a day, from having no term
         * for the moon's elliptical orbit. Anything past this is not the model
         * being approximate, it is the model being wrong.
         */
        const val MAX_CLAIMED_HOURS = 14.0
    }
}
