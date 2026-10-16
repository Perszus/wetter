// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.sky

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.SolarTime
import lv.bolwarra.wetter.domain.model.HourlyWeather

/**
 * When the sky is worth going outside for, across the coming night: the question
 * is whether to set an alarm, not what the sky is doing now.
 *
 * Scanned hour by hour with [Stargazing.assess], so the drawer and the dial's mark
 * are the same judgement asked about different instants.
 */
object StarWatch {

    /** How far ahead to look. One turn of the Earth catches exactly one night. */
    val HORIZON: Duration = Duration.ofHours(24)

    /** The darkness window is found by sampling, and this is how finely. */
    val DARKNESS_STEP: Duration = Duration.ofMinutes(10)

    /**
     * The night ahead.
     *
     * @param darkFrom when the sky becomes dark enough, or null where it never does,
     * which is half the year above the Arctic Circle.
     * @param best the longest stretch that is both dark and clear, or null when the
     * cloud never breaks for long enough.
     */
    data class Night(val darkFrom: Instant?, val darkUntil: Instant?, val best: Window?) {
        val hasDarkness: Boolean get() = darkFrom != null

        /** Something to say, as opposed to a group of empty rows. */
        val isWorthShowing: Boolean get() = hasDarkness || best != null
    }

    data class Window(val from: Instant, val to: Instant)

    fun tonight(
        hours: List<HourlyWeather>,
        now: Instant,
        latitude: Double,
        longitude: Double,
        moonIllumination: Double,
    ): Night {
        val until = now.plus(HORIZON)
        val dark = darkness(now, until, latitude, longitude)

        val worthIt = hours
            .filter { !it.timestamp.isBefore(now) && it.timestamp.isBefore(until) }
            .sortedBy { it.timestamp }
            .filter { hour ->
                Stargazing.assess(
                    cloudLow = hour.cloudLow,
                    cloudMedium = hour.cloudMedium,
                    cloudHigh = hour.cloudHigh,
                    sunElevationDegrees = SolarTime.elevationDegrees(
                        hour.timestamp,
                        latitude,
                        longitude,
                    ),
                    moonIllumination = moonIllumination,
                    precipitationMmPerHour = hour.precipitation,
                ).isWorthIt
            }

        return Night(darkFrom = dark?.from, darkUntil = dark?.to, best = longestRun(worthIt))
    }

    /**
     * The first and last moment dark enough for stars, inside the horizon. Sampled
     * rather than solved, so one code path is right everywhere, including where the
     * sun never gets low enough.
     */
    private fun darkness(
        from: Instant,
        to: Instant,
        latitude: Double,
        longitude: Double,
    ): Window? {
        var at = from
        var first: Instant? = null
        var last: Instant? = null

        while (at.isBefore(to)) {
            val dark = SolarTime.elevationDegrees(at, latitude, longitude) <
                Stargazing.DARK_ENOUGH_DEGREES
            if (dark) {
                if (first == null) first = at
                last = at
            } else if (first != null) {
                // Two darknesses inside 24 hours: the tail of one night and the start of the
                // next. The first is the one asked about.
                break
            }
            at = at.plus(DARKNESS_STEP)
        }

        return first?.let { Window(it, last ?: it) }
    }

    /** The longest unbroken run of good hours, as a window. */
    private fun longestRun(hours: List<HourlyWeather>): Window? {
        if (hours.isEmpty()) return null

        var bestStart = 0
        var bestLength = 1
        var start = 0

        for (index in 1..hours.lastIndex) {
            val contiguous = Duration.between(
                hours[index - 1].timestamp,
                hours[index].timestamp,
            ) <= HOUR
            if (!contiguous) start = index
            val length = index - start + 1
            if (length > bestLength) {
                bestLength = length
                bestStart = start
            }
        }

        val first = hours[bestStart].timestamp
        // The run's last hour covers the hour after it starts, so the window closes an
        // hour after the last row that qualified.
        return Window(first, hours[bestStart + bestLength - 1].timestamp.plus(HOUR))
    }

    private val HOUR: Duration = Duration.ofHours(1)
}
