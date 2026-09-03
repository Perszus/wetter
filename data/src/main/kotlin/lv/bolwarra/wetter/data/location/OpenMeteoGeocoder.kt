package lv.bolwarra.wetter.data.location

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.location.PlaceSearch
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.WeatherFailure

@Serializable
internal data class GeocodingResponse(
    /** Absent entirely when nothing matched, rather than an empty array. */
    val results: List<GeocodedPlace> = emptyList(),
)

@Serializable
internal data class GeocodedPlace(
    val id: Long = 0,
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val elevation: Double? = null,
    /** IANA zone, which is why this service is worth using over a plain gazetteer. */
    val timezone: String? = null,
    val country: String? = null,
    /** First-level admin area: the state, county or municipality. */
    val admin1: String? = null,
    val population: Long? = null,
)

/**
 * Finding places by name, through Open-Meteo's gazetteer.
 *
 * Chosen for two reasons beyond being keyless and already attributed here. It
 * returns an **IANA time zone**, which the app needs and cannot safely guess -
 * a forecast's "18:00" means 18:00 *there*, so a wrong zone silently shifts
 * every hour on the timeline. And it returns **elevation**, which the
 * observation layer needs to correct a nearby station's temperature to the
 * height of the place being asked about.
 *
 * A general-purpose geocoder would give coordinates and leave both of those to
 * be inferred.
 */
internal class OpenMeteoGeocoder(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : PlaceSearch {

    override val attribution: String = ATTRIBUTION

    override suspend fun search(query: String, limit: Int): Result<List<WeatherLocation>> {
        val trimmed = query.trim()
        // Short of this the service matches half the world, so the request is
        // not worth making and the empty answer is not worth showing as one.
        if (trimmed.length < PlaceSearch.MINIMUM_QUERY) return Result.success(emptyList())

        return try {
            val response: GeocodingResponse = client.get(baseUrl) {
                parameter("name", trimmed)
                parameter("count", limit.coerceIn(1, MAX_LIMIT))
                parameter("language", "en")
                parameter("format", "json")
            }.body()

            Result.success(response.results.mapNotNull { it.toLocation() })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Result.failure(WeatherFailure(failure.toWeatherError()))
        }
    }

    /**
     * Null for a result the app cannot actually use.
     *
     * A place with no usable zone is dropped rather than defaulted to the
     * phone's, because a forecast shown in the wrong zone is wrong in a way
     * nobody can see: every hour on the timeline is shifted and every reading
     * still looks perfectly plausible.
     */
    private fun GeocodedPlace.toLocation(): WeatherLocation? {
        if (name.isBlank()) return null
        val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        return WeatherLocation(
            name = name,
            latitude = latitude,
            longitude = longitude,
            zone = zone,
            region = admin1?.takeIf { it.isNotBlank() && it != name },
            country = country?.takeIf { it.isNotBlank() },
            elevationMetres = elevation,
        )
    }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://geocoding-api.open-meteo.com/v1/search"

        /** Same licence and credit as the forecast service it belongs to. */
        const val ATTRIBUTION = "Place search by Open-Meteo.com, licensed CC BY 4.0"

        /** The service's own ceiling. */
        const val MAX_LIMIT = 100
    }
}
