package lv.bolwarra.wetter.domain.provider

import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
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
        ) = ProviderCapabilities(
            variables = WeatherVariable.entries.toSet(),
            maximumForecastDays = maximumForecastDays,
            resolutionKm = resolutionKm,
            updateIntervalHours = updateIntervalHours,
        )

        /** A provider that always answers, with a forecast stamped as its own. */
        fun succeeding(
            id: String,
            coverage: ProviderCoverage = ProviderCoverage(isGlobal = true),
            at: Instant = DEFAULT_INSTANT,
        ) = FakeWeatherProvider(
            id = id,
            coverage = coverage,
            respond = { location -> Result.success(forecastFrom(id, location, at)) },
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

        fun forecastFrom(
            providerId: String,
            location: WeatherLocation,
            at: Instant = DEFAULT_INSTANT,
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
            hourly = emptyList(),
            daily = emptyList(),
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
    }
}
