package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity

/** What leaving at a particular moment would mean. */
data class ExposureWindow(
    val leaveAt: Instant,
    val outdoorFor: Duration,
    /** 0..1, the chance of meeting measurable precipitation during the window. */
    val chanceOfRain: Double,
    /** 0..1, how much the answer deserves to be believed. */
    val confidence: Double,
    /** The worst rate met in the window, mm/h. */
    val peakRate: Double,
    /** When the worst of it falls, if any does. */
    val peakAt: Instant?,
    /** Total accumulation across the window, mm. */
    val millimetres: Double,
) {
    val intensity: PrecipitationIntensity get() = PrecipitationIntensity.ofRate(peakRate)
}

/**
 * Whether you will get wet.
 *
 * This is the question the app exists to answer, and it is not the same question
 * as "what is the weather". A weather icon for the afternoon cannot tell you
 * whether to leave now or in twenty minutes; that needs the precipitation field
 * read across the particular stretch of time you will be outside in.
 *
 * ### Why the chance is the worst moment, not the sum of the moments
 *
 * Treating each step as an independent trial and multiplying the dry
 * probabilities together is the obvious approach and it is badly wrong.
 * Precipitation is strongly correlated in time - if it is raining at ten past,
 * it is very probably raining at quarter past - so independence compounds one
 * shower into near-certainty. Over a half-hour walk through a steady drizzle it
 * would report 99% where the honest answer is the chance that the drizzle is
 * there at all.
 *
 * So the chance is taken from the wettest moment in the window. Being outside
 * longer still raises it, because a longer window has more chances to catch
 * something, but through the extra moments it covers rather than by compounding
 * the same shower against itself.
 */
object ExposureRisk {

    /**
     * The rate at which rain is as good as certain to be noticed.
     *
     * A rate of one of these gives about a 63% chance of the walk being a wet
     * one; two, 86%. Set near the boundary between a trace and light rain,
     * because the question is whether you get wet, not whether a gauge would
     * register it.
     */
    const val NOTICEABLE_MM_PER_HOUR = 0.5

    /** Confidence below this and the honest answer is that we do not know. */
    const val USABLE_CONFIDENCE = 0.15

    /**
     * Assess one departure.
     *
     * @param leaveAt when you would set off.
     * @param outdoorFor how long you would be exposed.
     */
    fun assess(
        timeline: List<FusedPrecipitation>,
        leaveAt: Instant,
        outdoorFor: Duration,
    ): ExposureWindow {
        val until = leaveAt.plus(outdoorFor)
        val window = timeline.filter { !it.at.isBefore(leaveAt) && !it.at.isAfter(until) }
        if (window.isEmpty()) {
            return ExposureWindow(leaveAt, outdoorFor, 0.0, 0.0, 0.0, null, 0.0)
        }

        val worst = window.maxBy { it.millimetresPerHour }
        val chance = chanceOf(worst.millimetresPerHour)

        val millimetres = accumulation(window, outdoorFor)

        return ExposureWindow(
            leaveAt = leaveAt,
            outdoorFor = outdoorFor,
            chanceOfRain = chance,
            // The weakest link: a window is only as trustworthy as its least
            // trustworthy moment, because that is where a surprise comes from.
            confidence = window.minOf { it.confidence },
            peakRate = worst.millimetresPerHour,
            peakAt = worst.at.takeIf {
                worst.millimetresPerHour >=
                    PrecipitationIntensity.TRACE_MM_PER_HOUR
            },
            millimetres = millimetres,
        )
    }

    /**
     * The same question asked of every departure in the next stretch of time -
     * the table that answers "should I go now, or wait".
     */
    fun departures(
        timeline: List<FusedPrecipitation>,
        from: Instant,
        outdoorFor: Duration,
        within: Duration,
        every: Duration,
    ): List<ExposureWindow> {
        if (every.isZero || every.isNegative || within.isNegative) return emptyList()
        val count = (within.toMillis() / every.toMillis()).toInt()
        return (0..count).map { index ->
            assess(timeline, from.plus(every.multipliedBy(index.toLong())), outdoorFor)
        }
    }

    /**
     * The driest departure in the next stretch, if waiting actually helps.
     *
     * Returns null when leaving now is as good as anything - there is no point
     * telling somebody to wait forty minutes to save four percent.
     */
    fun bestDeparture(
        timeline: List<FusedPrecipitation>,
        from: Instant,
        outdoorFor: Duration,
        within: Duration,
        every: Duration,
        worthWaitingFor: Double = WORTH_WAITING_FOR,
    ): ExposureWindow? {
        val options = departures(timeline, from, outdoorFor, within, every)
        val now = options.firstOrNull() ?: return null
        val best = options.minByOrNull { it.chanceOfRain } ?: return null
        return best.takeIf {
            it.leaveAt != now.leaveAt &&
                now.chanceOfRain - it.chanceOfRain >= worthWaitingFor
        }
    }

    /**
     * How much falls across the window, in millimetres.
     *
     * The trapezoid rule over the actual timestamps, which is not fussiness. The
     * obvious version - multiply every sample by the step and add them up -
     * counts one interval too many, because n samples bound n-1 intervals. Seven
     * ten-minute samples of a steady 6 mm/h came out as 7 mm of rain in an hour.
     * Reading the gaps rather than the samples also survives a timeline whose
     * spacing is not uniform, which a stitched one is not.
     */
    private fun accumulation(window: List<FusedPrecipitation>, outdoorFor: Duration): Double {
        if (window.size < 2) {
            val hours = outdoorFor.toMillis().toDouble() / MILLIS_PER_HOUR
            return (window.firstOrNull()?.millimetresPerHour ?: 0.0) * hours
        }
        return window.zipWithNext().sumOf { (earlier, later) ->
            val hours =
                Duration.between(earlier.at, later.at).toMillis().toDouble() / MILLIS_PER_HOUR
            (earlier.millimetresPerHour + later.millimetresPerHour) / 2.0 * hours
        }
    }

    /**
     * The chance of noticing rain at a given rate.
     *
     * Saturating rather than linear: the difference between nothing and a light
     * shower is nearly all of the answer, and the difference between heavy and
     * torrential is none of it - you are equally wet either way.
     */
    fun chanceOf(millimetresPerHour: Double): Double {
        if (millimetresPerHour <= 0.0) return 0.0
        return 1.0 - exp(-millimetresPerHour / NOTICEABLE_MM_PER_HOUR)
    }

    /** Below this improvement, waiting is not advice worth giving. */
    private const val WORTH_WAITING_FOR = 0.25

    private const val MILLIS_PER_HOUR = 3_600_000.0
}
