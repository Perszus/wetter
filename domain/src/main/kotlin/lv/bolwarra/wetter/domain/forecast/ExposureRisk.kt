// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * Whether you will get wet, read across the particular stretch of time you will
 * be outside.
 *
 * The chance is taken from the wettest moment in the window, not by multiplying
 * each step's dry probability: precipitation is strongly correlated in time, and
 * independence would compound one drizzle into near-certainty. A longer window
 * still raises the chance through the extra moments it covers.
 */
object ExposureRisk {

    /**
     * The rate at which rain is as good as certain to be noticed: one of these gives
     * about a 63% chance of a wet walk, two 86%. Near the boundary between a trace and
     * light rain, since the question is getting wet, not what a gauge registers.
     */
    const val NOTICEABLE_MM_PER_HOUR = 0.5

    /** Confidence below this and the honest answer is that nobody knows. */
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
            // The weakest link: a window is only as trustworthy as its least trustworthy
            // moment.
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
     * The same question asked of every departure in the next stretch: should I go
     * now, or wait.
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
     * The driest departure in the next stretch, or null when waiting does not
     * meaningfully help.
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
     * How much falls across the window, in millimetres, by the trapezoid rule over the
     * actual timestamps: n samples bound n-1 intervals, and a stitched timeline is not
     * evenly spaced.
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
     * The chance of noticing rain at a given rate. Saturating: nothing against a light
     * shower is nearly all of the answer, heavy against torrential none of it.
     */
    fun chanceOf(millimetresPerHour: Double): Double {
        if (millimetresPerHour <= 0.0) return 0.0
        return 1.0 - exp(-millimetresPerHour / NOTICEABLE_MM_PER_HOUR)
    }

    /** Below this improvement, waiting is not advice worth giving. */
    private const val WORTH_WAITING_FOR = 0.25

    private const val MILLIS_PER_HOUR = 3_600_000.0
}
