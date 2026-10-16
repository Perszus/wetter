// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * The hourly row covering [instant], or null if the series does not reach it. A
 * row runs until the next one starts, since a stitched timeline (docs/providers.md)
 * widens to three- and six-hourly steps further out.
 */
fun List<HourlyWeather>.at(instant: Instant): HourlyWeather? {
    val hours = sortedBy { it.timestamp }
    val index = hours.indexOfLast { !it.timestamp.isAfter(instant) }
    if (index < 0) return null
    val row = hours[index]
    val until = hours.getOrNull(index + 1)?.timestamp ?: row.timestamp.plus(Duration.ofHours(1))
    return row.takeIf { instant.isBefore(until) }
}

/**
 * The current conditions, corrected to [instant].
 *
 * [WeatherForecast.current] is stamped at fetch time and nothing ages it, while
 * the rest of the screen moves with the clock. While the observation falls in the
 * same hourly slot as [instant] this is exactly that observation; once overtaken,
 * the forecast for the hour covering [instant] stands in.
 *
 * Fields the hourly row does not carry (wind direction, humidity, pressure) are
 * dropped rather than kept, so stale readings are not shown beside fresh ones; a
 * missing value is drawn as a dash.
 */
fun WeatherForecast.conditionsAt(instant: Instant): CurrentWeather {
    val row = hourly.at(instant) ?: return current
    // The observation belongs to the hour covering [instant], so it is fresh and
    // richer than the row.
    if (hourly.at(current.observedAt)?.timestamp == row.timestamp) return current

    return CurrentWeather(
        observedAt = row.timestamp,
        temperature = row.temperature,
        // Derived from temperature, wind and humidity, so it comes from the same hour as
        // the temperature.
        apparentTemperature = row.apparentTemperature,
        condition = row.appearance,
        isDay = row.isDay,
        precipitation = row.precipitation,
        windSpeed = row.windSpeed,
        windGust = row.windGust,
        windDirection = null,
        humidity = null,
        pressure = null,
    )
}
