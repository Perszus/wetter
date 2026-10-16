// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.location

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.location.CoordinateQuery
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.domain.location.PlaceSearch
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.WeatherFailure

/**
 * What the forecast service says about a bare point: its zone and its height. The
 * gazetteer matches names only; the forecast endpoint resolves any point on earth,
 * open ocean included.
 */
@Serializable
internal data class PointResponse(val timezone: String? = null, val elevation: Double? = null)

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
 * Finding places by name, through Open-Meteo's gazetteer. It returns an IANA time
 * zone, which the app cannot safely guess ("18:00" on a forecast means 18:00
 * there), and an elevation, which the observation layer uses to correct station
 * temperatures to the place's height.
 */
internal class OpenMeteoGeocoder(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : PlaceSearch {

    override val attribution: String = ATTRIBUTION

    override suspend fun search(query: String, limit: Int): Result<List<WeatherLocation>> {
        val trimmed = query.trim()
        // Shorter queries match half the world, so the request is not worth making.
        if (trimmed.length < PlaceSearch.MINIMUM_QUERY) return Result.success(emptyList())

        // A coordinate pair is answered directly: the gazetteer returns nothing for one,
        // and the point already says where.
        CoordinateQuery.parse(trimmed)?.let { return resolve(it) }

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
     * A typed point, turned into somewhere the app can show. The zone is fetched, not
     * assumed: the phone's zone is wrong for any point the phone is not at.
     */
    private suspend fun resolve(point: Coordinates): Result<List<WeatherLocation>> = try {
        val answer: PointResponse = client.get(POINT_URL) {
            parameter("latitude", point.latitude)
            parameter("longitude", point.longitude)
            parameter("timezone", "auto")
            parameter("forecast_days", 1)
        }.body()

        val zone = answer.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        Result.success(
            if (zone == null) {
                emptyList()
            } else {
                listOf(
                    WeatherLocation(
                        // Named by the point itself: the nearest settlement is a different place
                        // with a different forecast.
                        name = point.format(),
                        latitude = point.latitude,
                        longitude = point.longitude,
                        zone = zone,
                        region = null,
                        country = null,
                        elevationMetres = answer.elevation,
                    ),
                )
            },
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    /**
     * Null for a result the app cannot use. A place with no usable zone is dropped
     * rather than given the phone's: a wrong zone shifts every hour while every
     * reading still looks plausible.
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

        /** The same licence and credit as the forecast service it belongs to. */
        const val ATTRIBUTION = "Place search by Open-Meteo.com, licensed CC\u00A0BY\u00A04.0"

        /** The service's own ceiling. */
        const val MAX_LIMIT = 100

        /** The forecast endpoint, asked only for what it knows about a point. */
        const val POINT_URL = "https://api.open-meteo.com/v1/forecast"
    }
}
