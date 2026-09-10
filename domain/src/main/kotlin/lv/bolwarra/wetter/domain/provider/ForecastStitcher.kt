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
 *  - **A day's summary comes from whoever drew most of that day's hours.**
 *    Otherwise the daily row describes one model while the bars above it draw
 *    another, and a reader who opens the day is shown the disagreement.
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

        // Who actually drew each day, counted rather than assumed.
        //
        // This used to ask only whether the extension had contributed *any* hour
        // to a date, and hand it the whole day if so. The seam almost never
        // lands on midnight, so that rule gave away days the primary had drawn
        // nearly all of - and the two sources do not agree about them.
        //
        // What that looked like: a Saturday headed with a rain mark, opened, and
        // every one of its hours dry. Twenty-one of those hours were the
        // regional model's and said no rain; the last three were the global
        // one's; and the summary above them was the global model's too, which
        // had forecast drizzle in an afternoon the reader could see was clear.
        // Neither source was wrong. They were describing the same afternoon and
        // only one of them was drawn.
        //
        // So a day goes to whichever source supplied more of its hours, which is
        // what "summarised by whoever drew its hours" meant all along. A tie and
        // a day nobody drew hours for both fall to the primary, which is the
        // better source for this place.
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
