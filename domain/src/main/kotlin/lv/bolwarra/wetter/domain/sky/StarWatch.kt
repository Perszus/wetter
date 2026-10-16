// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.sky

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
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
     * The night's dark window, each edge to the minute. A scan finds where darkness
     * starts and stops and each crossing is then narrowed down, so the times do not
     * move with the moment they are asked about. Sampled rather than solved, so one
     * code path is right everywhere, including where the sun never gets low enough.
     * A night already under way is reported from its dusk.
     */
    private fun darkness(
        from: Instant,
        to: Instant,
        latitude: Double,
        longitude: Double,
    ): Window? {
        val dark = { at: Instant ->
            SolarTime.elevationDegrees(at, latitude, longitude) < Stargazing.DARK_ENOUGH_DEGREES
        }
        var at = from
        var first: Instant? = null
        var last: Instant? = null
        var after: Instant? = null

        while (at.isBefore(to)) {
            if (dark(at)) {
                if (first == null) first = at
                last = at
            } else if (first != null) {
                // Two darknesses inside 24 hours: the tail of one night and the start of the
                // next. The first is the one asked about.
                after = at
                break
            }
            at = at.plus(DARKNESS_STEP)
        }
        if (first == null || last == null) return null

        val start = when (first) {
            from -> dusk(from, dark)
            else -> crossing(first.minus(DARKNESS_STEP), first, dark)
        }
        val end = after?.let { crossing(last, it, dark) } ?: last
        return Window(start, end)
    }

    /** When the darkness [now] is inside began, or [now] itself where it has lasted a whole day. */
    private fun dusk(now: Instant, dark: (Instant) -> Boolean): Instant {
        var at = now
        val limit = now.minus(HORIZON)
        while (at.isAfter(limit)) {
            val before = at.minus(DARKNESS_STEP)
            if (!dark(before)) return crossing(before, at, dark)
            at = before
        }
        return now
    }

    /**
     * The dark side of the edge between [a] and [b], which disagree, to the nearest
     * minute.
     */
    private fun crossing(a: Instant, b: Instant, dark: (Instant) -> Boolean): Instant {
        var lo = a
        var hi = b
        val loDark = dark(lo)
        while (Duration.between(lo, hi) > EDGE_PRECISION) {
            val mid = lo.plus(Duration.between(lo, hi).dividedBy(2))
            if (dark(mid) == loDark) lo = mid else hi = mid
        }
        val edge = if (loDark) lo else hi
        return edge.plusSeconds(HALF_MINUTE).truncatedTo(ChronoUnit.MINUTES)
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
    private val EDGE_PRECISION: Duration = Duration.ofSeconds(1)
    private const val HALF_MINUTE = 30L
}
