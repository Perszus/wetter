package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * What the weather is *now*, as opposed to what it was when we last asked.
 *
 * [WeatherForecast.current] is an observation stamped at fetch time, and nothing
 * ages it. Everywhere else on the screen moves with the clock — the mark on the
 * dial, the rolling rain window, the sentence about when it next rains — so a
 * forecast left sitting produced a dial pointing at two in the afternoon beside
 * the temperature from eight that morning. The screen contradicted itself, and
 * the contradiction grew the longer it was open.
 *
 * The hourly series already contains the answer: it has a row for the hour we
 * are actually in. So the rule is simply that the freshest thing describing this
 * hour wins.
 */

/**
 * The hourly row covering [instant], or null if the series does not reach it.
 *
 * A row runs until the next one starts rather than for a fixed hour, because a
 * stitched timeline (docs/providers.md) widens to three- and six-hourly steps
 * further out and a hard hour would leave gaps the lookup fell through.
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
 * While the data is fresh — the observation and [instant] fall in the same
 * hourly slot, which is every ordinary refresh — this is exactly
 * [WeatherForecast.current]. It only does anything once the observation has been
 * overtaken, and then it hands back the forecast for the hour we are in.
 *
 * The fields the hourly row does not carry are dropped rather than kept. Wind
 * direction, humidity and pressure would otherwise be presented, unlabelled and
 * alongside corrected numbers, as readings for a moment they do not describe;
 * six hours is long enough for all three to be wrong. The grid renders a missing
 * value as a dash, which is the true answer to a question this forecast can no
 * longer settle.
 */
fun WeatherForecast.conditionsAt(instant: Instant): CurrentWeather {
    val row = hourly.at(instant) ?: return current
    // The observation belongs to the hour we are in, so it is both fresh and
    // richer than the row. Nothing to correct.
    if (hourly.at(current.observedAt)?.timestamp == row.timestamp) return current

    return CurrentWeather(
        observedAt = row.timestamp,
        temperature = row.temperature,
        // Derived from temperature, wind and humidity. Kept, it would be the old
        // hour's answer sitting next to the new hour's temperature.
        apparentTemperature = null,
        condition = row.condition,
        isDay = row.isDay,
        precipitation = row.precipitation,
        windSpeed = row.windSpeed,
        windGust = row.windGust,
        windDirection = null,
        humidity = null,
        pressure = null,
    )
}
