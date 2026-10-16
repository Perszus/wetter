// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.verification

import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * Applies what has been learned about a place to a forecast for it.
 *
 * Applied to the whole forecast at once, so the dial and the week cannot disagree.
 * There is nothing to enable: [BiasCorrection.learn] returns null until a place
 * has enough verified records, and its strength ramps in from there.
 *
 * Every air temperature is corrected (current, hourly, daily range), and apparent
 * temperature shifted with them. Precipitation is not: its error is about timing
 * and placement, and a constant offset would add drizzle to dry hours.
 */
fun WeatherForecast.withLocalCorrection(bias: LearnedBias?): WeatherForecast {
    if (bias == null || bias.variable != VerifiedVariable.TEMPERATURE) return this
    val offset = bias.effectiveOffset
    // Below this the correction cannot change a displayed number, so it would only
    // cost a copy of the forecast.
    if (kotlin.math.abs(offset) < NEGLIGIBLE_DEGREES) return this

    return copy(
        current = current.copy(
            temperature = current.temperature?.minus(offset),
            apparentTemperature = current.apparentTemperature?.minus(offset),
        ),
        hourly = hourly.map { hour ->
            val corrected = hour.temperature?.minus(offset)
            if (corrected == null) hour else hour.copy(temperature = corrected)
        },
        daily = daily.map { day ->
            day.copy(
                temperatureMin = day.temperatureMin - offset,
                temperatureMax = day.temperatureMax - offset,
            )
        },
    )
}

/** Smaller than the display can show. */
private const val NEGLIGIBLE_DEGREES = 0.05
