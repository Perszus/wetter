package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos

/**
 * Where the moon is in its cycle.
 *
 * Computed, not fetched, for the same reason sunrise is ([SolarTime]): it is a
 * function of the date alone, so asking a weather service for it would mean a
 * network round trip, an API key somewhere, and a value that goes missing when
 * the network does - for a number that a few lines of arithmetic settle exactly.
 *
 * ### The accuracy this does and does not have
 *
 * This uses the mean synodic month. The real one varies by up to about six hours
 * either side, because the moon's orbit is elliptical and the sun tugs on it, so
 * the phase here can be out by roughly half a day at worst and the illumination
 * by a couple of percent. That is invisible in a phase name and irrelevant in a
 * percentage rounded to whole numbers, which is all this feeds. It would not be
 * good enough to predict an eclipse, and nothing here should start trying.
 */
object MoonPhase {

    /** The mean interval between new moons. */
    const val SYNODIC_DAYS = 29.530588853

    /**
     * A known new moon: 2000 January 6, 18:14 UTC. Any accurately dated one
     * works; this is the conventional reference and is close enough to now that
     * accumulated error in the mean month stays small.
     */
    private val REFERENCE_NEW_MOON: Instant = Instant.parse("2000-01-06T18:14:00Z")

    private const val SECONDS_PER_DAY = 86_400.0

    /**
     * How far through the cycle, 0 up to 1.
     *
     * 0 is new, 0.5 is full. Waxing below a half, waning above it.
     */
    fun fractionAt(instant: Instant): Double {
        val days = Duration.between(REFERENCE_NEW_MOON, instant).seconds / SECONDS_PER_DAY
        val cycles = days / SYNODIC_DAYS
        // Kotlin's rem keeps the sign of the dividend, which for dates before the
        // reference would run the cycle backwards.
        return ((cycles % 1.0) + 1.0) % 1.0
    }

    /** The lit share of the disc, 0 at new and 1 at full. */
    fun illuminationAt(instant: Instant): Double = (1.0 - cos(2.0 * PI * fractionAt(instant))) / 2.0

    /**
     * The name for the phase.
     *
     * Eight equal bins centred on the named points, so "full" covers the day or
     * so either side that anybody would call full rather than a single instant
     * nobody would ever see.
     */
    fun nameAt(instant: Instant): MoonPhaseName {
        val eighths = ((fractionAt(instant) * BINS) + 0.5).toInt() % BINS
        return MoonPhaseName.entries[eighths]
    }

    private const val BINS = 8
}

/** Ordered from new through full and back, one eighth of the cycle each. */
enum class MoonPhaseName {
    NEW,
    WAXING_CRESCENT,
    FIRST_QUARTER,
    WAXING_GIBBOUS,
    FULL,
    WANING_GIBBOUS,
    LAST_QUARTER,
    WANING_CRESCENT,
}
