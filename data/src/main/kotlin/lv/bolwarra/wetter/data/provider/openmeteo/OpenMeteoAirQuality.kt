package lv.bolwarra.wetter.data.provider.openmeteo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.air.AirQualitySource
import lv.bolwarra.wetter.domain.provider.WeatherFailure

@Serializable
internal data class AirQualityResponse(
    val current: AirQualityCurrent? = null,
    val hourly: AirQualityHourly? = null,
)

@Serializable
internal data class AirQualityCurrent(
    val time: Long? = null,
    @SerialName("pm2_5") val pm25: Double? = null,
    val pm10: Double? = null,
    val ozone: Double? = null,
    @SerialName("nitrogen_dioxide") val nitrogenDioxide: Double? = null,
)

@Serializable
internal data class AirQualityHourly(
    val time: List<Long> = emptyList(),
    @SerialName("pm2_5") val pm25: List<Double?> = emptyList(),
)

/**
 * Air quality from Open-Meteo's CAMS-backed service.
 *
 * A different host and a different set of models from the forecast endpoint, so
 * it is a source of its own rather than another field on the weather provider.
 * Coverage is genuinely global - CAMS runs a 40 km global model, refined to
 * 11 km over Europe - which was checked rather than assumed: Nairobi, Delhi,
 * Sao Paulo, Sydney and Svalbard all answer.
 *
 * ### Why the past 24 hours are fetched
 *
 * The WHO threshold this is banded against is a 24-hour mean, so a 24-hour mean
 * is what gets computed. It costs one extra parameter on a request already being
 * made, and it is the difference between "the air here is bad" and "there was a
 * bonfire an hour ago".
 */
internal class OpenMeteoAirQuality(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AirQualitySource {

    override val attribution: String = OpenMeteoProvider.ATTRIBUTION

    override suspend fun airQuality(latitude: Double, longitude: Double): Result<AirQuality> = try {
        val payload: AirQualityResponse = client.get(baseUrl) {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter("current", CURRENT_VARIABLES)
            parameter("hourly", "pm2_5")
            parameter("past_hours", TRAILING_HOURS)
            parameter("forecast_hours", 0)
            parameter("timeformat", "unixtime")
            parameter("timezone", "UTC")
        }.body()

        Result.success(payload.toAirQuality())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    private fun AirQualityResponse.toAirQuality(): AirQuality {
        val readings = hourly?.pm25?.filterNotNull().orEmpty()
        return AirQuality(
            observedAt = current?.time?.let(Instant::ofEpochSecond) ?: Instant.now(),
            pm25 = current?.pm25,
            // A mean of three hours is not a daily mean, and calling it one
            // would put a confident word on a reading that has not earned it.
            pm25Average = if (readings.size >= MINIMUM_HOURS) readings.average() else null,
            pm10 = current?.pm10,
            ozone = current?.ozone,
            nitrogenDioxide = current?.nitrogenDioxide,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"

        private const val CURRENT_VARIABLES = "pm2_5,pm10,ozone,nitrogen_dioxide"

        private const val TRAILING_HOURS = 24

        /** Fewer than this and the average says more about the gap than the air. */
        private const val MINIMUM_HOURS = 18
    }
}
