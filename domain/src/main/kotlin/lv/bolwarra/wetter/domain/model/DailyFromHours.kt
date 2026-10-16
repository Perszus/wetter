// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.model

import java.time.LocalDate

/**
 * Make every daily row agree with the hours it summarises.
 *
 * A stitched forecast can carry one service's daily summary over another's hours,
 * or a summary built from six-hourly blocks over hourly data, so this runs after
 * the join. Two things are derived from the held hours:
 *
 * - **The peak rate**, which names a day: the hardest hour the reader could
 * scroll to, not an average over a block they never see.
 * - **A summary that hides precipitation**: where the day has an hour worth naming
 * and the daily code says nothing falls, the word comes from the wettest hour.
 *
 * Never the reverse: a code saying rain is evidence in a way an absence is not,
 * and [WeatherCondition.atRate] still reconciles intensity downwards. A day with
 * no hours is left as the provider sent it.
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
