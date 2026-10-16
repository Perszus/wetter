// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.provider.metar

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.observation.ObservationSource
import lv.bolwarra.wetter.domain.observation.ObservationStation
import lv.bolwarra.wetter.domain.observation.WeatherObservation
import lv.bolwarra.wetter.domain.provider.WeatherFailure

@Serializable
internal data class MetarDto(
    val icaoId: String? = null,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val elev: Double? = null,
    /** Seconds since the epoch, when the observation was taken. */
    val obsTime: Long? = null,
    val temp: Double? = null,
    val dewp: Double? = null,
    /** Degrees. Can arrive as the string "VRB" for a variable wind. */
    val wdir: Int? = null,
    /** Knots. */
    val wspd: Double? = null,
    /** Altimeter setting in hectopascals. */
    val altim: Double? = null,
    /** Statute miles, sometimes with a trailing "+". */
    val visib: String? = null,
    /** The present-weather group, when there is one. */
    val wxString: String? = null,
    /**
     * The report as filed: it tells "the observer saw nothing falling" from "nobody
     * reported" (see [PresentWeather]).
     */
    val rawOb: String? = null,
)

/**
 * Aerodrome observations: free, standardised, calibrated measurements of the
 * actual weather, worldwide and without a key.
 *
 * Not for display. They exist so the app can find out whether its predictions
 * were right. Reports carry present weather but no accumulation, so precipitation
 * is verified as an event: whether the rain arrived when forecast.
 */
internal class MetarObservationSource(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : ObservationSource {

    override val id: String = ID

    override val attribution: String = ATTRIBUTION

    override suspend fun near(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
    ): Result<List<WeatherObservation>> = fetch(latitude, longitude, radiusKm, hours = null)

    override suspend fun history(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        hours: Int,
    ): Result<List<WeatherObservation>> =
        fetch(latitude, longitude, radiusKm, hours.coerceIn(1, MAX_HISTORY_HOURS))

    private suspend fun fetch(
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        hours: Int?,
    ): Result<List<WeatherObservation>> = try {
        val reports: List<MetarDto> = client.get(baseUrl) {
            parameter("bbox", boundingBox(latitude, longitude, radiusKm))
            parameter("format", "json")
            if (hours != null) parameter("hours", hours)
        }.body()

        Result.success(reports.mapNotNull { it.toObservation() })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    /**
     * A box around a point, in the order the service expects. A degree of longitude
     * shrinks towards the poles, so the box widens to hold the same ground distance.
     */
    private fun boundingBox(latitude: Double, longitude: Double, radiusKm: Double): String {
        val latSpan = radiusKm / KM_PER_DEGREE_LATITUDE
        val cosine = kotlin.math.cos(Math.toRadians(latitude)).coerceAtLeast(MIN_COSINE)
        val lonSpan = latSpan / cosine
        val south = (latitude - latSpan).coerceIn(-90.0, 90.0)
        val north = (latitude + latSpan).coerceIn(-90.0, 90.0)
        val west = longitude - lonSpan
        val east = longitude + lonSpan
        return "$south,$west,$north,$east"
    }

    private fun MetarDto.toObservation(): WeatherObservation? {
        val id = icaoId ?: return null
        val latitude = lat ?: return null
        val longitude = lon ?: return null
        val observedAt = obsTime ?: return null

        return WeatherObservation(
            station = ObservationStation(
                id = id,
                name = name ?: id,
                latitude = latitude,
                longitude = longitude,
                elevationMetres = elev ?: 0.0,
            ),
            at = Instant.ofEpochSecond(observedAt),
            temperature = temp,
            dewPoint = dewp,
            windSpeed = wspd?.let { it * METRES_PER_SECOND_PER_KNOT },
            windDirection = wdir,
            pressure = altim,
            visibilityMetres = visibilityMetresOf(visib),
            precipitating = PresentWeather.precipitationFrom(
                group = wxString,
                reported = !rawOb.isNullOrBlank(),
            ),
            intensity = PresentWeather.intensityFrom(wxString),
        )
    }

    /**
     * Visibility in metres. Reported in statute miles, often as "10+"; the plus is
     * dropped and the number taken as the floor it is.
     */
    private fun visibilityMetresOf(raw: String?): Double? {
        val text = raw?.trim()?.removeSuffix("+") ?: return null
        return text.toDoubleOrNull()?.times(METRES_PER_STATUTE_MILE)
    }

    internal companion object {
        const val ID = "metar"

        /** A public product of the United States weather service. */
        const val ATTRIBUTION = "Aerodrome observations from NOAA/NWS Aviation Weather Center"

        const val DEFAULT_BASE_URL = "https://aviationweather.gov/api/data/metar"

        /** Two days is what the service retains, and more than verification needs. */
        const val MAX_HISTORY_HOURS = 48

        private const val METRES_PER_SECOND_PER_KNOT = 0.514444
        private const val METRES_PER_STATUTE_MILE = 1609.344
        private const val KM_PER_DEGREE_LATITUDE = 111.32

        /** Stops the box widening without limit approaching the poles. */
        private const val MIN_COSINE = 0.1
    }
}
