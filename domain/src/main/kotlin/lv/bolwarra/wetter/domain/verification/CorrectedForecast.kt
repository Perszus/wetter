package lv.bolwarra.wetter.domain.verification

import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * Applies what has been learned about a place to a forecast for it.
 *
 * The correction is applied to the whole forecast at once, rather than to each
 * number as it is drawn. A screen showing a corrected temperature on the dial
 * and an uncorrected one in the week below it would be contradicting itself, and
 * the contradiction would be invisible to anyone who did not know a correction
 * existed at all.
 *
 * ### It switches itself on
 *
 * There is nothing to enable. [BiasCorrection.learn] returns null until a
 * location has enough verified records to show a pattern, and its strength ramps
 * in from there, so a new location is untouched, one with a fortnight of records
 * is nudged, and one with a month is corrected in full. That is the whole
 * activation mechanism: the correction is weak because the evidence is weak, and
 * grows as the evidence does.
 *
 * ### What is corrected, and what is left alone
 *
 * Every air temperature: the current reading, the hourly series and the daily
 * range, since all three come from the same model and share its error.
 *
 * Apparent temperature is shifted with them. It is derived from air temperature
 * along with wind and humidity, so an error in the first carries into it; moving
 * it by the same amount is approximate but far closer than leaving it while the
 * temperature beside it moves.
 *
 * Precipitation is not corrected at all, even though the store records it. A
 * temperature bias is an offset - the same amount, in the same direction, hour
 * after hour - which is exactly what subtracting a constant fixes. Rain is not
 * like that: it is intermittent, and its error is far more about timing and
 * placement than about amount. Shifting every rate by a constant would add
 * drizzle to dry hours and would not move a missed shower an inch closer to when
 * it actually fell.
 */
fun WeatherForecast.withLocalCorrection(bias: LearnedBias?): WeatherForecast {
    if (bias == null || bias.variable != VerifiedVariable.TEMPERATURE) return this
    val offset = bias.effectiveOffset
    // Below a tenth of a degree the correction cannot change a displayed number,
    // so applying it would only cost a copy of the whole forecast.
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

/** Smaller than the display can show, so not worth rebuilding a forecast for. */
private const val NEGLIGIBLE_DEGREES = 0.05
