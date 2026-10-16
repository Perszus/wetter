// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.provider

import java.time.Duration

/**
 * The things a forecast can contain, as a closed set: "which of the variables
 * Wetter needs can this provider supply?" is a set operation.
 */
enum class WeatherVariable {
    HOURLY,
    DAILY,
    PRECIPITATION,
    PRECIPITATION_PROBABILITY,
    SNOWFALL,
    WIND,
    CLOUD_COVER,
    HUMIDITY,
    PRESSURE,
    SUNRISE_SUNSET,
}

/**
 * What Wetter asks of a provider. Missing something [required] rules a provider
 * out; missing something [desired] only scores lower. Hourly precipitation is
 * required; probability is only desired, or MET Norway would be excluded across
 * most of its coverage.
 */
data class ForecastRequirements(
    val required: Set<WeatherVariable> = setOf(
        WeatherVariable.HOURLY,
        WeatherVariable.PRECIPITATION,
    ),
    val desired: Set<WeatherVariable> = setOf(
        WeatherVariable.DAILY,
        WeatherVariable.PRECIPITATION_PROBABILITY,
        WeatherVariable.SNOWFALL,
        WeatherVariable.WIND,
        WeatherVariable.CLOUD_COVER,
        WeatherVariable.SUNRISE_SUNSET,
    ),
    val minimumForecastDays: Int = 3,
    /**
     * How far ahead there should be an hourly timeline: six days, so every day of the
     * week bar the tail of the last has hourly detail. Not a filter: a provider with
     * a shorter reach is extended, not passed over (see ForecastStitcher).
     */
    val hourlyHorizon: Duration = Duration.ofDays(6),
) {
    companion object {
        /** What the weather screen needs to draw itself. */
        val Default = ForecastRequirements()
    }
}
