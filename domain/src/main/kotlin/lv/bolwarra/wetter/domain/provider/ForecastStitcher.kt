// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.provider

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * Joins a short forecast to a longer one so the hourly timeline never runs out.
 *
 * The best provider for a place is often not the one with the longest reach: MET
 * Norway's 2.5 km model is hourly for about sixty hours, then six-hourly. So the
 * regional model is kept where it has hours and a global one continues beyond.
 *
 * - **Nothing is invented.** Six-hourly steps are never spread into hours, and
 * the join is exactly where the first source's hourly data stops.
 * - **A day's summary comes from whoever drew most of that day's hours**, so the
 * daily row describes the same model as the bars under it.
 */
object ForecastStitcher {

    /**
     * How far short of [horizon] a forecast's hourly coverage falls, or zero,
     * measured from [now].
     */
    fun shortfall(forecast: WeatherForecast, horizon: Duration, now: Instant): Duration {
        val covered = hourlyCoverage(forecast, now)
        val gap = horizon.minus(covered)
        return if (gap.isNegative) Duration.ZERO else gap
    }

    /** How far ahead of [now] the hourly rows reach. Zero when there are none. */
    fun hourlyCoverage(forecast: WeatherForecast, now: Instant): Duration {
        val last = forecast.hourly.maxOfOrNull { it.timestamp } ?: return Duration.ZERO
        val covered = Duration.between(now, last)
        return if (covered.isNegative) Duration.ZERO else covered
    }

    /**
     * Whether extending is worth a second network request: a forecast is nearly always
     * a few hours short of a round horizon, so only a real gap counts.
     */
    fun needsExtending(forecast: WeatherForecast, horizon: Duration, now: Instant): Boolean =
        shortfall(forecast, horizon, now) > WORTH_A_SECOND_REQUEST

    /**
     * Appends [extension]'s hourly rows to [primary], keeping [primary] wherever the
     * two overlap. Returns [primary] unchanged when the extension adds nothing.
     */
    fun stitch(primary: WeatherForecast, extension: WeatherForecast): WeatherForecast {
        val lastPrimaryHour = primary.hourly.maxOfOrNull { it.timestamp }

        val added = extension.hourly
            .filter { lastPrimaryHour == null || it.timestamp.isAfter(lastPrimaryHour) }
            .sortedBy { it.timestamp }

        if (added.isEmpty()) return primary

        val zone = primary.location.zone
        val seam = added.first().timestamp

        // Who drew each day, counted: the seam rarely lands on midnight, so a day goes to
        // whichever source supplied more of its hours. Ties, and days with no hours, go
        // to the primary.
        val drawnByPrimary = primary.hourly.hoursPerDay(zone)
        val drawnByExtension = added.hoursPerDay(zone)

        val primaryDaily = primary.daily.associateBy { it.date }
        val extensionDaily = extension.daily.associateBy { it.date }

        val daily = (primaryDaily.keys + extensionDaily.keys).sorted().mapNotNull { date ->
            val theirs = drawnByExtension[date] ?: 0
            val ours = drawnByPrimary[date] ?: 0
            if (theirs > ours) {
                extensionDaily[date] ?: primaryDaily[date]
            } else {
                primaryDaily[date] ?: extensionDaily[date]
            }
        }

        return primary.copy(
            hourly = primary.hourly + added,
            daily = daily,
            supplement = ForecastSupplement(provider = extension.provider, from = seam),
        )
    }

    /** How many hourly rows each local date got, for deciding who describes it. */
    private fun List<HourlyWeather>.hoursPerDay(zone: ZoneId): Map<LocalDate, Int> =
        groupingBy { it.timestamp.atZone(zone).toLocalDate() }.eachCount()

    /**
     * Long enough that the ordinary shortfall of a forecast starting at local
     * midnight never triggers a fetch, short enough that a provider stopping two days
     * early always does.
     */
    val WORTH_A_SECOND_REQUEST: Duration = Duration.ofHours(12)
}

/**
 * The second source in a stitched forecast, and where it took over. Null on an
 * ordinary single-source forecast. The main screen never names a provider.
 */
data class ForecastSupplement(
    val provider: ProviderMetadata,
    /** The first hourly timestamp that came from [provider]. */
    val from: Instant,
)
