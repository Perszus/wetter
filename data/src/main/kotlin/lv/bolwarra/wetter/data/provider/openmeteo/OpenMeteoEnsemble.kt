package lv.bolwarra.wetter.data.provider.openmeteo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.forecast.ModelAgreement
import lv.bolwarra.wetter.domain.forecast.ModelEnsemble
import lv.bolwarra.wetter.domain.forecast.ModelReading
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.WeatherFailure

/**
 * Seven weather models, asked the same question at once.
 *
 * Open-Meteo will run a request against named models rather than its own blend,
 * and returns them all in one response - seven independent forecasts for about
 * five kilobytes, which makes an ensemble the cheapest reliability available
 * here by a wide margin (docs/providers.md).
 *
 * The value is not a better number. It is the disagreement: several independent
 * models over the same hour measure how hard that hour is to forecast, and that
 * is the only uncertainty signal obtainable without a verification history.
 *
 * ### Why the response is parsed by hand
 *
 * The series come back under per-model keys - `temperature_2m_ecmwf_ifs025`,
 * `precipitation_icon_seamless` - so the shape of the object depends on which
 * models were asked for. A generated data class would have to name all of them
 * and would break the day a model is renamed or retired, which happens. Walking
 * the object by suffix survives that, and survives a model simply not answering
 * for a location it does not cover.
 */
internal class OpenMeteoEnsemble(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    suspend fun ensemble(location: WeatherLocation): Result<ModelEnsemble> = try {
        val payload: JsonObject = client.get(baseUrl) {
            parameter("latitude", location.latitude)
            parameter("longitude", location.longitude)
            parameter("hourly", "temperature_2m,precipitation")
            parameter("models", MODELS.joinToString(","))
            parameter("forecast_days", FORECAST_DAYS)
            parameter("timeformat", "unixtime")
            parameter("timezone", "UTC")
        }.body()

        Result.success(parse(payload))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    private fun parse(payload: JsonObject): ModelEnsemble {
        val hourly = payload["hourly"]?.jsonObject ?: return ModelEnsemble(emptyList())
        val times = (hourly["time"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.longOrNull }
            ?: return ModelEnsemble(emptyList())

        val temperatures = seriesFor(hourly, TEMPERATURE_PREFIX)
        val precipitations = seriesFor(hourly, PRECIPITATION_PREFIX)

        val readings = times.mapIndexed { index, epochSeconds ->
            val at = Instant.ofEpochSecond(epochSeconds)
            val temperatureValues = temperatures.mapNotNull { it.getOrNull(index) }
            val precipitationValues = precipitations.mapNotNull { it.getOrNull(index) }
            ModelReading(
                at = at,
                temperature = ModelAgreement.summarise(at, temperatureValues),
                precipitation = ModelAgreement.summarise(at, precipitationValues),
                chanceOfRain = ModelAgreement.probabilityOfPrecipitation(precipitationValues),
            )
        }
        return ModelEnsemble(readings)
    }

    /**
     * Every model's series for one variable.
     *
     * Nulls inside a series are kept as nulls rather than dropped, so position
     * still means the same hour in every model. Compacting them would silently
     * shift one model's afternoon onto another's morning.
     */
    private fun seriesFor(hourly: JsonObject, prefix: String): List<List<Double?>> = hourly.entries
        .filter { it.key.startsWith(prefix) && it.key != prefix }
        .map { (_, value) ->
            value.jsonArray.map { it.jsonPrimitive.doubleOrNull }
        }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://api.open-meteo.com/v1/forecast"

        /**
         * Independent models with global or wide regional coverage, chosen so
         * that agreement between them means something. Several are run by
         * different national services from different analyses, so when they
         * agree it is not because they are the same model twice.
         *
         * Models that do not cover a location simply return nothing for it,
         * which the parser handles - so this list does not have to vary by
         * where the user is.
         */
        val MODELS = listOf(
            "ecmwf_ifs025",
            "icon_seamless",
            "gfs_seamless",
            "ukmo_seamless",
            "meteofrance_seamless",
            "knmi_seamless",
            "dmi_seamless",
        )

        /**
         * Three days. The ensemble is used to qualify the near term, and asking
         * for sixteen days of seven models would multiply the payload for a part
         * of the forecast nothing currently reads.
         */
        const val FORECAST_DAYS = 3

        private const val TEMPERATURE_PREFIX = "temperature_2m_"
        private const val PRECIPITATION_PREFIX = "precipitation_"
    }
}
