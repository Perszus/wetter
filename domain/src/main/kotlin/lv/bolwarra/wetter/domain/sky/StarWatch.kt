package lv.bolwarra.wetter.domain.sky

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.SolarTime
import lv.bolwarra.wetter.domain.model.HourlyWeather

/**
 * When the sky is worth going outside for, across the coming night.
 *
 * The mark on the dial answers "right now", which is the wrong horizon for
 * this one thing: nobody decides to go and look at the sky at the instant they
 * open a weather app. They want to know whether it is worth setting an alarm,
 * and that is a question about a window some hours away.
 *
 * Everything here is scanned rather than solved. The cloud decks arrive hourly,
 * so the answer cannot be finer than an hour however it is computed, and reusing
 * [Stargazing.assess] for every hour means the drawer and the mark cannot drift
 * apart - they are the same judgement asked about different instants.
 */
object StarWatch {

    /** How far ahead to look. One turn of the Earth catches exactly one night. */
    val HORIZON: Duration = Duration.ofHours(24)

    /** The darkness window is found by sampling, and this is how finely. */
    val DARKNESS_STEP: Duration = Duration.ofMinutes(10)

    /**
     * The night ahead.
     *
     * @param darkFrom when the sky becomes dark enough, or null where it never
     *   does - which is not an edge case to be tidied away but half the year
     *   above the arctic circle.
     * @param best the longest stretch that is both dark and clear, or null when
     *   the cloud never breaks for long enough to be worth naming.
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
     * The first and last moment dark enough for stars, inside the horizon.
     *
     * Sampled rather than solved. A closed form for the sun crossing -12 degrees
     * exists, but it has to be asked separately for dusk and dawn, on two
     * different dates, and it has no answer at all where the sun never gets that
     * low - which is exactly where getting it wrong matters. Walking the window
     * gives one code path that is right everywhere and is correct to the step.
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
                // Two separate darknesses inside 24 hours means the horizon
                // caught the tail of one night and the start of the next. The
                // first is the one being asked about.
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
        // The run's last hour covers the hour after it starts, so the window
        // closes an hour later than the last row that qualified.
        return Window(first, hours[bestStart + bestLength - 1].timestamp.plus(HOUR))
    }

    private val HOUR: Duration = Duration.ofHours(1)
}
