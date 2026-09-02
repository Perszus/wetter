package lv.bolwarra.wetter.domain.provider

import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import java.time.Duration
import java.time.Instant

/**
 * A provider that exists to be ranked and called.
 *
 * The selector and the router must be testable without any of the real
 * providers - otherwise a change to Open-Meteo's capability list quietly
 * rewrites the expectations of every routing test, and those tests stop being
 * about routing at all.
 */
class FakeWeatherProvider(
    override val id: String,
    override val capabilities: ProviderCapabilities = everyVariable(),
    override val coverage: ProviderCoverage = ProviderCoverage(isGlobal = true),
    override val displayName: String = id,
    override val attribution: String = "Test data",
    private val respond: (WeatherLocation) -> Result<WeatherForecast> = {
        Result.failure(WeatherFailure(WeatherError.Unknown()))
    },
) : WeatherProvider {

    var calls: Int = 0
        private set

    override suspend fun getForecast(location: WeatherLocation): Result<WeatherForecast> {
        calls++
        return respond(location)
    }

    companion object {

        fun everyVariable(
            maximumForecastDays: Int = 7,
            resolutionKm: Double? = 11.0,
            updateIntervalHours: Double? = 1.0,
            hourlyHorizonHours: Int = 7 * 24,
        ) = ProviderCapabilities(
            variables = WeatherVariable.entries.toSet(),
            maximumForecastDays = maximumForecastDays,
            hourlyHorizonHours = hourlyHorizonHours,
            resolutionKm = resolutionKm,
            updateIntervalHours = updateIntervalHours,
        )

        /** A provider that always answers, with a forecast stamped as its own. */
        fun succeeding(
            id: String,
            coverage: ProviderCoverage = ProviderCoverage(isGlobal = true),
            at: Instant = DEFAULT_INSTANT,
            hourlyHours: Int = FULL_HOURLY,
            capabilities: ProviderCapabilities = everyVariable(hourlyHorizonHours = hourlyHours),
        ) = FakeWeatherProvider(
            id = id,
            coverage = coverage,
            capabilities = capabilities,
            respond = { location -> Result.success(forecastFrom(id, location, at, hourlyHours)) },
        )

        /** A provider that always fails with the given error. */
        fun failing(
            id: String,
            error: WeatherError,
            coverage: ProviderCoverage = ProviderCoverage(isGlobal = true),
        ) = FakeWeatherProvider(
            id = id,
            coverage = coverage,
            respond = { Result.failure(WeatherFailure(error)) },
        )

        /**
         * @param hourlyHours how many hourly rows to generate, starting at [at].
         *   The default is a full week, so a test that is not about the hourly
         *   horizon never accidentally triggers the router into extending.
         */
        fun forecastFrom(
            providerId: String,
            location: WeatherLocation,
            at: Instant = DEFAULT_INSTANT,
            hourlyHours: Int = FULL_HOURLY,
        ) = WeatherForecast(
            location = location,
            current = CurrentWeather(
                observedAt = at,
                temperature = 4.2,
                apparentTemperature = null,
                condition = WeatherCondition.OVERCAST,
                isDay = true,
                precipitation = 0.0,
                windSpeed = null,
                windDirection = null,
                humidity = null,
                pressure = null,
            ),
            hourly = List(hourlyHours) { hour ->
                HourlyWeather(
                    timestamp = at.plus(Duration.ofHours(hour.toLong())),
                    temperature = 4.0,
                    precipitationProbability = null,
                    precipitation = 0.0,
                    rain = null,
                    snowfall = null,
                    condition = WeatherCondition.OVERCAST,
                    windSpeed = null,
                    cloudCover = null,
                    isDay = true,
                )
            },
            daily = List((hourlyHours + 23) / 24) { day ->
                DailyWeather(
                    date = at.plus(Duration.ofDays(day.toLong())).atZone(location.zone).toLocalDate(),
                    temperatureMin = 0.0,
                    temperatureMax = 8.0,
                    condition = WeatherCondition.OVERCAST,
                    precipitationTotal = 0.0,
                    precipitationProbabilityMax = null,
                    precipitationHours = null,
                    sunrise = null,
                    sunset = null,
                    windSpeedMax = null,
                )
            },
            fetchedAt = at,
            provider = ProviderMetadata(
                id = providerId,
                name = providerId,
                model = null,
                resolutionKm = null,
                forecastGeneratedAt = null,
                attribution = "Test data",
            ),
        )

        private val DEFAULT_INSTANT: Instant = Instant.parse("2026-03-14T09:00:00Z")

        /** A week of hourly rows: past any horizon Wetter asks for. */
        const val FULL_HOURLY = 7 * 24
    }
}
