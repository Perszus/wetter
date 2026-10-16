// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * Several weather models, asked the same question at once (docs/providers.md).
 * The value is the disagreement: independent models over the same hour measure
 * how hard that hour is to forecast.
 *
 * Parsed by hand because series come back under per-model keys
 * (`temperature_2m_ecmwf_ifs`), so the object's shape depends on the models asked
 * for, and a model may be renamed, retired, or silent for a location.
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
            // Stated rather than assumed: a silent switch of default units would not fail,
            // it would make every spread wrong.
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
            // Aligned, nulls and all: position is the model, and the fusion follows one
            // model across the hours.
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
     * Each model's temperature and precipitation, paired by model, with duplicates
     * removed. Several "seamless" regional products fall back to ECMWF outside their
     * domain, so beyond Europe they return ECMWF's run byte for byte; identical series
     * are counted once.
     *
     * Nulls are kept, so position means the same hour in every model.
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
         * Independent models with global or wide regional coverage, from different
         * services and analyses, so agreement means something. Models that do not cover a
         * location return nothing, which the parser handles.
         *
         * ECMWF is its native 9 km grid: the 0.25° product is three-hourly, spread evenly
         * across the hours (docs/design-principles.md, rule 9). GEM is here for the rest of
         * the world, where the duplicates leave few independent votes. JMA and CMA are
         * three-hourly away from home; BOM's and KMA's globals return empty.
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
         * Three days: the ensemble qualifies the near term, and more would multiply the
         * payload for nothing read.
         */
        const val FORECAST_DAYS = 3

        private const val TEMPERATURE_PREFIX = "temperature_2m_"
        private const val PRECIPITATION_PREFIX = "precipitation_"
    }
}
