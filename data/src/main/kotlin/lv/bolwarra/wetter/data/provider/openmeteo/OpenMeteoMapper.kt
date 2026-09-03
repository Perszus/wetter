package lv.bolwarra.wetter.data.provider.openmeteo

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import lv.bolwarra.wetter.data.provider.weatherConditionFromWmoCode
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata

/**
 * Open-Meteo's response, turned into Wetter's models.
 *
 * The whole file is pure: response in, forecast out, no clock and no network. It
 * is where the awkward parts of the API are absorbed — parallel arrays that can
 * disagree in length, missing blocks, and a timezone that is resolved by the
 * server rather than known by the caller — so that nothing above it has to.
 */
object OpenMeteoMapper {

    fun toForecast(
        response: OpenMeteoResponse,
        location: WeatherLocation,
        fetchedAt: Instant,
        metadata: ProviderMetadata,
    ): WeatherForecast {
        // timezone=auto means the server resolves the zone from the coordinates,
        // and it is more likely to be right than a zone stored months ago against
        // a hand-entered location. Adopt it.
        val zone =
            response.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: location.zone
        val resolved = if (zone == location.zone) location else location.copy(zone = zone)

        val hourly = response.hourly?.let { toHourly(it, zone) }.orEmpty()
        val daily = response.daily?.let { toDaily(it, zone) }.orEmpty()

        return WeatherForecast(
            location = resolved,
            current = toCurrent(response.current, zone, hourly, fetchedAt),
            hourly = hourly,
            daily = daily,
            fetchedAt = fetchedAt,
            provider = metadata,
        )
    }

    private fun toCurrent(
        current: OpenMeteoCurrent?,
        zone: ZoneId,
        hourly: List<HourlyWeather>,
        fetchedAt: Instant,
    ): CurrentWeather {
        // Open-Meteo can omit the current block entirely. Falling back to the
        // first hourly row is better than refusing the whole forecast over the
        // one value the screen shows largest.
        val fallback = hourly.firstOrNull()

        return CurrentWeather(
            observedAt = current?.time?.toInstant(zone) ?: fallback?.timestamp ?: fetchedAt,
            temperature = current?.temperature ?: fallback?.temperature,
            apparentTemperature = current?.apparentTemperature,
            condition = current?.weatherCode
                ?.let { weatherConditionFromWmoCode(it) }
                ?: fallback?.condition
                ?: WeatherCondition.UNKNOWN,
            isDay = current?.isDay?.let { it == 1 } ?: fallback?.isDay ?: true,
            precipitation = current?.precipitation,
            windSpeed = current?.windSpeed,
            windGust = current?.windGust,
            windDirection = current?.windDirection,
            humidity = current?.humidity,
            pressure = current?.pressureMsl,
        )
    }

    private fun toHourly(hourly: OpenMeteoHourly, zone: ZoneId): List<HourlyWeather> =
        hourly.time.mapIndexedNotNull { index, stamp ->
            val timestamp = stamp.toInstant(zone) ?: return@mapIndexedNotNull null
            HourlyWeather(
                timestamp = timestamp,
                // A row with no temperature is still a usable precipitation row,
                // so it is kept and the temperature is left absent.
                temperature = hourly.temperature.at(index),
                precipitationProbability = hourly.precipitationProbability.at(index),
                precipitation = hourly.precipitation.at(index),
                rain = hourly.rain.at(index),
                snowfall = hourly.snowfall.at(index),
                condition = weatherConditionFromWmoCode(hourly.weatherCode.at(index)),
                windSpeed = hourly.windSpeed.at(index),
                windGust = hourly.windGust.at(index),
                cloudCover = hourly.cloudCover.at(index),
                isDay = hourly.isDay.at(index)?.let { it == 1 } ?: true,
            )
        }

    private fun toDaily(daily: OpenMeteoDaily, zone: ZoneId): List<DailyWeather> =
        daily.time.mapIndexedNotNull { index, stamp ->
            val date = stamp.toLocalDate() ?: return@mapIndexedNotNull null
            val max = daily.temperatureMax.at(index)
            val min = daily.temperatureMin.at(index)
            // A day with no temperature range is not a day worth drawing a row for.
            if (max == null || min == null) return@mapIndexedNotNull null

            DailyWeather(
                date = date,
                temperatureMin = min,
                temperatureMax = max,
                condition = weatherConditionFromWmoCode(daily.weatherCode.at(index)),
                precipitationTotal = daily.precipitationSum.at(index),
                precipitationProbabilityMax = daily.precipitationProbabilityMax.at(index),
                precipitationHours = daily.precipitationHours.at(index),
                sunrise = daily.sunrise.at(index)?.toInstant(zone),
                sunset = daily.sunset.at(index)?.toInstant(zone),
                windSpeedMax = daily.windSpeedMax.at(index),
            )
        }

    /**
     * Parallel arrays are only parallel when the provider says so. Any of them
     * can be shorter than `time`, and reading past the end would take down a
     * refresh over a variable the screen may not even use.
     */
    private fun <T> List<T?>.at(index: Int): T? = getOrNull(index)

    private fun String.toInstant(zone: ZoneId): Instant? = try {
        LocalDateTime.parse(this).atZone(zone).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun String.toLocalDate(): LocalDate? = try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
}
