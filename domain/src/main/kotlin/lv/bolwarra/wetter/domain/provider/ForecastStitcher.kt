package lv.bolwarra.wetter.domain.provider

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * Joins a short forecast to a longer one so the hourly timeline never runs out.
 *
 * The problem this solves is that the best provider for a place is often not the
 * one with the longest reach. MET Norway runs a 2.5 km model over the Nordics
 * and is the right source for whether it rains this afternoon — but it is hourly
 * for only about sixty hours, after which its series drops to six-hourly steps.
 * Picking it therefore used to mean a better forecast for two days and no hourly
 * timeline at all for the other five.
 *
 * So Wetter keeps the regional model where it has hours, and continues with a
 * global one beyond that. The near term, where precipitation timing is both most
 * accurate and most useful, comes from the better model; the tail, where every
 * model is guessing, comes from whoever can still speak hourly.
 *
 * Two rules keep the result honest:
 *
 *  - **Nothing is invented.** Six-hourly steps are never spread into hours. The
 *    join happens exactly where the first source's hourly data stops, so no good
 *    data is thrown away to make the seam land somewhere tidier either.
 *  - **A day's summary comes from whoever supplied that day's hours.** Otherwise
 *    the daily row could describe one model while the bars above it draw another.
 */
object ForecastStitcher {

    /**
     * How far short of [horizon] a forecast's hourly coverage falls, or zero.
     *
     * Measured from [now] rather than from the forecast's first hour, because
     * what matters is how far ahead the user can still see.
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
     * Whether extending is worth a second network request.
     *
     * A forecast is nearly always a few hours short of any round horizon — one
     * that starts at local midnight and runs seven days is already eleven hours
     * short by mid-morning. Spending a request, somebody's battery and another
     * service's quota to add those hours would be silly, so only a real gap
     * counts.
     */
    fun needsExtending(forecast: WeatherForecast, horizon: Duration, now: Instant): Boolean =
        shortfall(forecast, horizon, now) > WORTH_A_SECOND_REQUEST

    /**
     * Appends [extension]'s hourly rows to [primary], keeping [primary] wherever
     * the two overlap.
     *
     * Returns [primary] unchanged when the extension adds nothing — which is the
     * right answer, not a failure.
     */
    fun stitch(primary: WeatherForecast, extension: WeatherForecast): WeatherForecast {
        val lastPrimaryHour = primary.hourly.maxOfOrNull { it.timestamp }

        val added = extension.hourly
            .filter { lastPrimaryHour == null || it.timestamp.isAfter(lastPrimaryHour) }
            .sortedBy { it.timestamp }

        if (added.isEmpty()) return primary

        val zone = primary.location.zone
        val seam = added.first().timestamp
        val extendedDates = added.map { it.timestamp.atZone(zone).toLocalDate() }.toSet()

        val primaryDaily = primary.daily.associateBy { it.date }
        val extensionDaily = extension.daily.associateBy { it.date }

        val daily = (primaryDaily.keys + extensionDaily.keys).sorted().mapNotNull { date ->
            // A day whose hours came from the extension is described by the
            // extension, so the summary and the bars above it agree.
            if (date in extendedDates) {
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

    /**
     * Twelve hours. Long enough that the ordinary shortfall of a forecast
     * starting at local midnight never triggers a fetch, short enough that a
     * provider stopping two days early always does.
     */
    val WORTH_A_SECOND_REQUEST: Duration = Duration.ofHours(12)
}

/**
 * The second source in a stitched forecast, and where it took over.
 *
 * Null on the ordinary single-source forecast. When present it is shown in the
 * Advanced section and nowhere else: the main screen never names a provider, and
 * naming two would be worse than naming one.
 */
data class ForecastSupplement(
    val provider: ProviderMetadata,
    /** The first hourly timestamp that came from [provider]. */
    val from: Instant,
)
