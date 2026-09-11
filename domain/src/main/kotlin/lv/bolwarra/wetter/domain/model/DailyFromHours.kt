package lv.bolwarra.wetter.domain.model

import java.time.LocalDate

/**
 * Make every daily row agree with the hours it is a summary of.
 *
 * ### Why a mapper cannot do this
 *
 * Each provider builds its daily rows from its own steps, which is the only
 * thing it has — and then the forecast gets *stitched*. A day can end up with
 * its summary from one service and its hours from another, or with a summary
 * built from six-hourly blocks sitting above hours that came from somewhere
 * publishing every hour. Neither source did anything wrong; the disagreement is
 * created by joining them, so it can only be repaired after the join.
 *
 * Measured on a real stitched forecast for Rīga: Sunday held 3.2 mm with two
 * hours at or above half a millimetre, and its row said `OVERCAST` with a peak
 * of 0.27 mm/h — which is 1.6 divided by six, the rate of a six-hour block. The
 * week drew a plain cloud on a day whose own bar said rain began at eight in the
 * evening.
 *
 * ### What it fixes, and what it leaves alone
 *
 * Two things, both derived from the hours the app is actually holding:
 *
 * - **The peak rate**, which is what names a day. Recomputed from the day's
 *   hours, so it is the hardest hour the reader could scroll to rather than an
 *   average over a block they never see.
 * - **A summary that hides precipitation.** A provider's daily code is one word
 *   for twenty-four hours and evening rain loses to an overcast afternoon. Where
 *   the day contains an hour worth naming and the code says nothing is falling,
 *   the word comes from the wettest hour instead.
 *
 * It never does the reverse. A day whose code says rain keeps it even if no
 * single hour reaches the bar — the provider may be summarising a drizzle that
 * never peaks, and a code is evidence in a way an absence is not. Intensity is
 * still reconciled downwards by [WeatherCondition.atRate] through `appearance`,
 * so a day of drizzle is still drawn as drizzle.
 *
 * A day with no hours at all is left exactly as the provider sent it: beyond the
 * hourly horizon there is nothing better to say, and inventing a summary from no
 * hours would be worse than repeating one.
 */
fun WeatherForecast.withDailyReadFromHours(): WeatherForecast {
    if (hourly.isEmpty() || daily.isEmpty()) return this

    val zone = location.zone
    val hoursByDate: Map<LocalDate, List<HourlyWeather>> =
        hourly.groupBy { it.timestamp.atZone(zone).toLocalDate() }

    return copy(
        daily = daily.map { day ->
            val hours = hoursByDate[day.date] ?: return@map day
            val peak = hours.mapNotNull { it.precipitation }.maxOrNull()

            // The wettest hour of the day, and only if it is worth naming.
            val wettest = hours
                .filter { PrecipitationIntensity.ofRate(it.precipitation).isWorthNaming }
                .maxByOrNull { it.precipitation ?: 0.0 }

            val condition = when {
                wettest == null -> day.condition
                day.condition.isPrecipitating -> day.condition
                else -> wettest.appearance
            }

            day.copy(precipitationPeakRate = peak, condition = condition)
        },
    )
}
