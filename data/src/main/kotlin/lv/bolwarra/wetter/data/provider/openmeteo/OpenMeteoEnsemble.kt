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
import lv.bolwarra.wetter.domain.forecast.EnsembleSource
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
 * The series come back under per-model keys - `temperature_2m_ecmwf_ifs`,
 * `precipitation_icon_seamless` - so the shape of the object depends on which
 * models were asked for. A generated data class would have to name all of them
 * and would break the day a model is renamed or retired, which happens. Walking
 * the object by suffix survives that, and survives a model simply not answering
 * for a location it does not cover.
 */
internal class OpenMeteoEnsemble(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : EnsembleSource {

    override suspend fun ensemble(location: WeatherLocation): Result<ModelEnsemble> = try {
        val payload: JsonObject = client.get(baseUrl) {
            parameter("latitude", location.latitude)
            parameter("longitude", location.longitude)
            parameter("hourly", "temperature_2m,precipitation")
            parameter("models", MODELS.joinToString(","))
            parameter("forecast_days", FORECAST_DAYS)
            parameter("timeformat", "unixtime")
            parameter("timezone", "UTC")
            // Stated rather than assumed. These happen to be Open-Meteo's
            // defaults today, and a default is a promise nobody made: a silent
            // switch to Fahrenheit here would not fail, it would just make every
            // ensemble spread three times too wide.
            parameter("temperature_unit", "celsius")
            parameter("precipitation_unit", "mm")
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

        val members = distinctMembers(hourly)
        val temperatures = members.map { it.first }
        val precipitations = members.map { it.second }

        val readings = times.mapIndexed { index, epochSeconds ->
            val at = Instant.ofEpochSecond(epochSeconds)
            val temperatureValues = temperatures.mapNotNull { it.getOrNull(index) }
            // Aligned, nulls and all: position is the model, and the fusion
            // follows one model across the hours to interpolate its own trace.
            val precipitationByModel = precipitations.map { it.getOrNull(index) }
            val precipitationValues = precipitationByModel.filterNotNull()
            ModelReading(
                at = at,
                temperature = ModelAgreement.summarise(at, temperatureValues),
                precipitation = ModelAgreement.summarise(at, precipitationValues),
                chanceOfRain = ModelAgreement.probabilityOfPrecipitation(precipitationValues),
                precipitationByModel = precipitationByModel,
            )
        }
        return ModelEnsemble(readings)
    }

    /**
     * Each model's temperature and precipitation, paired by model, with any
     * model that is another one under a different name removed.
     *
     * Several of the "seamless" products are a regional model inside their own
     * domain and somebody else's global model outside it. KNMI's and DMI's
     * both fall back to ECMWF, so everywhere beyond Europe - measured in Tokyo,
     * New York, Sydney, São Paulo, Delhi and Lagos - three of the votes were
     * one ECMWF run, byte for byte. A median over that is ECMWF's answer
     * wearing a consensus, which is exactly what the ensemble exists to avoid.
     * Identical series are the same model; they are counted once.
     *
     * Nulls inside a series are kept as nulls rather than dropped, so position
     * still means the same hour in every model. Compacting them would silently
     * shift one model's afternoon onto another's morning.
     */
    private fun distinctMembers(hourly: JsonObject): List<Pair<List<Double?>, List<Double?>>> {
        val temperatures = seriesByModel(hourly, TEMPERATURE_PREFIX)
        val precipitations = seriesByModel(hourly, PRECIPITATION_PREFIX)
        return precipitations.keys
            .map { model -> temperatures[model].orEmpty() to precipitations.getValue(model) }
            .distinct()
    }

    private fun seriesByModel(hourly: JsonObject, prefix: String): Map<String, List<Double?>> =
        hourly.entries
            .filter { it.key.startsWith(prefix) && it.key != prefix }
            .associate { (key, value) ->
                key.removePrefix(prefix) to value.jsonArray.map { it.jsonPrimitive.doubleOrNull }
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
         *
         * ECMWF is its native 9 km grid rather than the 0.25 degree one. The
         * coarse product is not merely coarser in space: it is three-hourly, and
         * Open-Meteo spreads each three-hour total evenly across its hours -
         * measured over a wet Riga, two thirds of its hour-to-hour steps were
         * exact repeats, against one in eight for the native grid. One of seven
         * votes was a staircase, which is exactly the "hour" that must not be
         * the app's unit (docs/design-principles.md, rule 9).
         *
         * Canada's GEM is here for the rest of the world. Once the regional
         * products that fall back to ECMWF are counted once (see
         * [distinctMembers]), everywhere outside Europe was down to five
         * independent votes; GEM is global, hourly and its own analysis.
         * Measured and left out: JMA and CMA, which are three-hourly
         * staircases away from home (48 of 55 wet steps repeated over Rīga),
         * and BOM's and KMA's globals, which the service returns empty.
         */
        val MODELS = listOf(
            "ecmwf_ifs",
            "icon_seamless",
            "gfs_seamless",
            "ukmo_seamless",
            "meteofrance_seamless",
            "gem_seamless",
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
