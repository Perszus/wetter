package lv.bolwarra.wetter.data.repository

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata

/** A plain dry forecast, so anything wet in a fused timeline came from radar. */
internal fun testForecast(location: WeatherLocation, at: Instant) = WeatherForecast(
    location = location,
    current = CurrentWeather(
        observedAt = at,
        temperature = 15.0,
        apparentTemperature = null,
        condition = WeatherCondition.OVERCAST,
        isDay = true,
        precipitation = 0.0,
        windSpeed = 4.0,
        windGust = null,
        windDirection = null,
        humidity = null,
        pressure = null,
    ),
    hourly = List(12) { hour ->
        HourlyWeather(
            timestamp = at.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .plus(Duration.ofHours(hour.toLong())),
            temperature = 15.0,
            precipitationProbability = null,
            precipitation = 0.0,
            rain = null,
            snowfall = null,
            condition = WeatherCondition.OVERCAST,
            windSpeed = 4.0,
            windGust = null,
            apparentTemperature = null,
            uvIndex = null,
            cloudCover = null,
            cloudLow = null,
            cloudMedium = null,
            cloudHigh = null,
            isDay = true,
        )
    },
    daily = emptyList(),
    fetchedAt = at,
    provider = ProviderMetadata("test", "Test", null, null, null, "Test"),
)
