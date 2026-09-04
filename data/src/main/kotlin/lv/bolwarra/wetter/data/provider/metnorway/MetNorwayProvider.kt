package lv.bolwarra.wetter.data.provider.metnorway

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.GeoBox
import lv.bolwarra.wetter.domain.provider.ProviderCapabilities
import lv.bolwarra.wetter.domain.provider.ProviderCoverage
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import lv.bolwarra.wetter.domain.provider.ProviderRegion
import lv.bolwarra.wetter.domain.provider.WeatherFailure
import lv.bolwarra.wetter.domain.provider.WeatherProvider
import lv.bolwarra.wetter.domain.provider.WeatherVariable

/**
 * MET Norway: Wetter's regional specialist.
 *
 * It runs a 2.5 km model over the Nordic area and falls back to a global model
 * elsewhere, which is exactly the shape the selector is built to reward — a
 * strong claim where the fine grid runs, an ordinary one everywhere else. It is
 * not presented as globally better than Open-Meteo, because it is not
 * (docs/providers.md).
 *
 * Two obligations come with the free service, and both are met here rather than
 * left as documentation: an identifying User-Agent on every request (installed
 * once on the shared client), and coordinates truncated to four decimals so that
 * cache keys are shared between users rather than unique per person. The second
 * is a privacy benefit as much as a courtesy — it also caps how precisely a
 * request describes where somebody is.
 */
internal class MetNorwayProvider(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.systemUTC(),
) : WeatherProvider {

    override val id: String = ID
    override val displayName: String = "MET Norway"

    override val attribution: String = ATTRIBUTION

    override val capabilities = ProviderCapabilities(
        variables = setOf(
            WeatherVariable.HOURLY,
            WeatherVariable.DAILY,
            WeatherVariable.PRECIPITATION,
            WeatherVariable.PRECIPITATION_PROBABILITY,
            WeatherVariable.WIND,
            WeatherVariable.CLOUD_COVER,
            WeatherVariable.HUMIDITY,
            WeatherVariable.PRESSURE,
            // Not published, but computed from the sun's position in the mapper,
            // which is why it is claimed here.
            WeatherVariable.SUNRISE_SUNSET,
        ),
        // Snow depth is absent: precipitation arrives as one liquid-equivalent
        // figure, so Wetter cannot say how many centimetres will lie.
        maximumForecastDays = 9,
        // The series is hourly for roughly two and a half days and six-hourly
        // after that. Sixty is the conservative reading of "roughly".
        hourlyHorizonHours = 60,
        resolutionKm = GLOBAL_RESOLUTION_KM,
        updateIntervalHours = 1.0,
    )

    override val coverage = ProviderCoverage(
        isGlobal = true,
        preferredRegions = listOf(
            // The MEPS domain, approximately. A rectangle over the Nordic
            // countries and the seas around them, where the 2.5 km model runs.
            ProviderRegion(
                name = "the Nordic region",
                box = GeoBox(south = 54.0, north = 72.0, west = 4.0, east = 32.0),
                strength = 1.0,
                resolutionKm = NORDIC_RESOLUTION_KM,
            ),
            // Svalbard, which the mainland rectangle stops well short of - it
            // sits between 74 and 81 north. MET Norway is the national service
            // for the archipelago and MEPS reaches it, so leaving it out handed
            // Longyearbyen to a global model by default. Measured on the same
            // hour, the two disagreed about whether it was raining or snowing.
            ProviderRegion(
                name = "Svalbard",
                box = GeoBox(south = 72.0, north = 82.0, west = 4.0, east = 36.0),
                strength = 1.0,
                resolutionKm = NORDIC_RESOLUTION_KM,
            ),
            // Beyond the fine grid MET Norway is still a well-regarded European
            // source, but only enough to break a tie.
            ProviderRegion(
                name = "north-western Europe",
                box = GeoBox(south = 48.0, north = 62.0, west = -11.0, east = 20.0),
                strength = 0.55,
            ),
        ),
    )

    override suspend fun getForecast(location: WeatherLocation): Result<WeatherForecast> = try {
        val response: MetNorwayResponse = client.get(baseUrl) {
            parameter("lat", location.latitude.toFourDecimals())
            parameter("lon", location.longitude.toFourDecimals())
        }.body()

        Result.success(
            MetNorwayMapper.toForecast(
                response = response,
                location = location,
                fetchedAt = Instant.now(clock),
                metadata = metadata(response),
            ),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    private fun metadata(response: MetNorwayResponse) = ProviderMetadata(
        id = id,
        name = displayName,
        model = "MEPS in the Nordic region, ECMWF elsewhere",
        resolutionKm = capabilities.resolutionKm,
        forecastGeneratedAt = response.properties.meta.updatedAt?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                null
            }
        },
        attribution = ATTRIBUTION,
    )

    /**
     * MET Norway's terms ask for no more than four decimals — about eleven
     * metres — so that requests land on shared cache entries instead of one per
     * user.
     */
    private fun Double.toFourDecimals(): Double = round(this * 10_000.0) / 10_000.0

    companion object {
        const val ID = "met-norway"
        const val DEFAULT_BASE_URL = "https://api.met.no/weatherapi/locationforecast/2.0/complete"

        /** Required by MET Norway's terms, shown verbatim in About. */
        const val ATTRIBUTION = "Weather data from MET Norway (met.no), licensed CC BY 4.0"

        private const val NORDIC_RESOLUTION_KM = 2.5
        private const val GLOBAL_RESOLUTION_KM = 9.0
    }
}
