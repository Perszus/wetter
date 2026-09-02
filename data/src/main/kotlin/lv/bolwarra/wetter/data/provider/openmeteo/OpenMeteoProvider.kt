package lv.bolwarra.wetter.data.provider.openmeteo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderCapabilities
import lv.bolwarra.wetter.domain.provider.ProviderCoverage
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import lv.bolwarra.wetter.domain.provider.WeatherFailure
import lv.bolwarra.wetter.domain.provider.WeatherProvider
import lv.bolwarra.wetter.domain.provider.WeatherVariable

/**
 * Open-Meteo: Wetter's global baseline.
 *
 * It is the provider that answers anywhere, supplies every variable the screen
 * can use, and needs no API key — which is what makes it the right fallback
 * rather than the right specialist (docs/providers.md).
 *
 * [baseUrl] is a constructor parameter so that Wetter can be pointed at a
 * self-hosted Open-Meteo instance. Nothing else about the provider changes in
 * that case.
 */
internal class OpenMeteoProvider(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.systemUTC(),
) : WeatherProvider {

    override val id: String = ID
    override val displayName: String = "Open-Meteo"

    override val attribution: String = ATTRIBUTION

    override val capabilities = ProviderCapabilities(
        // Open-Meteo serves every variable Wetter knows how to draw.
        variables = WeatherVariable.entries.toSet(),
        maximumForecastDays = 16,
        // Hourly for the whole of whatever range is asked for, which is what
        // makes it the natural source for extending a shorter forecast.
        hourlyHorizonHours = 16 * 24,
        // It blends several models and picks the best available per location, so
        // this is a conservative floor rather than a figure for one grid.
        resolutionKm = 11.0,
        updateIntervalHours = 1.0,
    )

    override val coverage = ProviderCoverage(isGlobal = true)

    override suspend fun getForecast(location: WeatherLocation): Result<WeatherForecast> = try {
        val response: OpenMeteoResponse = client.get(baseUrl) {
            parameter("latitude", location.latitude)
            parameter("longitude", location.longitude)
            parameter("current", CURRENT_VARIABLES)
            parameter("hourly", HOURLY_VARIABLES)
            parameter("daily", DAILY_VARIABLES)
            // Let the server resolve the zone from the coordinates; see the mapper.
            parameter("timezone", "auto")
            parameter("forecast_days", FORECAST_DAYS)
            // Wetter's domain is metric and SI-ish throughout; converting once here
            // is cheaper than carrying a unit with every number.
            parameter("wind_speed_unit", "ms")
            parameter("precipitation_unit", "mm")
            parameter("temperature_unit", "celsius")
        }.body()

        val now = Instant.now(clock)
        Result.success(
            OpenMeteoMapper.toForecast(
                response = response,
                location = location,
                fetchedAt = now,
                metadata = metadata(),
            ),
        )
    } catch (cancellation: CancellationException) {
        // A cancelled refresh is not a provider failure and must not be recorded
        // as one, or backing out of the app twice would rest a healthy provider.
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    private fun metadata() = ProviderMetadata(
        id = id,
        name = displayName,
        model = "Best available per location",
        resolutionKm = capabilities.resolutionKm,
        // The basic forecast response does not publish the model run time.
        forecastGeneratedAt = null,
        attribution = ATTRIBUTION,
    )

    companion object {
        const val ID = "open-meteo"
        const val DEFAULT_BASE_URL = "https://api.open-meteo.com/v1/forecast"

        /** Required by Open-Meteo's terms, shown verbatim in About. */
        const val ATTRIBUTION = "Weather data by Open-Meteo.com, licensed CC BY 4.0"

        private const val FORECAST_DAYS = 7

        private const val CURRENT_VARIABLES =
            "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation," +
                "weather_code,pressure_msl,wind_speed_10m,wind_direction_10m"

        private const val HOURLY_VARIABLES =
            "temperature_2m,precipitation_probability,precipitation,rain,snowfall,weather_code," +
                "cloud_cover,wind_speed_10m,is_day"

        private const val DAILY_VARIABLES =
            "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_sum," +
                "precipitation_probability_max,precipitation_hours,wind_speed_10m_max"
    }
}
