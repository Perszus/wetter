// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.repository

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lv.bolwarra.wetter.data.db.EnsembleDao
import lv.bolwarra.wetter.data.db.EnsembleEntity
import lv.bolwarra.wetter.domain.forecast.ModelAgreement
import lv.bolwarra.wetter.domain.forecast.ModelEnsemble
import lv.bolwarra.wetter.domain.forecast.ModelReading

@Serializable
private data class StoredHour(
    val atEpochSecond: Long,
    /** What each model said, which is what a median has to be taken over. */
    val precipitation: List<Double> = emptyList(),
    /** By position, nulls kept, so a model can be followed across the hours. */
    val byModel: List<Double?> = emptyList(),
    val temperature: List<Double> = emptyList(),
    val chanceOfRain: Double? = null,
)

/**
 * Keeps the model ensemble for a place across a restart, so a fresh process has a
 * model median before the first fetch returns.
 *
 * The member values are stored rather than the summary: the provider joins them
 * as an extra vote, which a stored median cannot take. Spread and agreement are
 * recomputed on read.
 */
internal class EnsembleStore(private val dao: EnsembleDao, private val json: Json) {

    suspend fun read(cacheKey: String): Kept? {
        val row = dao.read(cacheKey) ?: return null
        val hours = runCatching {
            json.decodeFromString<List<StoredHour>>(row.payload)
        }.getOrNull() ?: return null

        val readings = hours.map { hour ->
            val at = Instant.ofEpochSecond(hour.atEpochSecond)
            ModelReading(
                at = at,
                temperature = ModelAgreement.summarise(at, hour.temperature),
                precipitation = ModelAgreement.summarise(at, hour.precipitation),
                chanceOfRain = hour.chanceOfRain,
                precipitationByModel = hour.byModel.ifEmpty { hour.precipitation },
            )
        }
        return Kept(
            fetchedAt = Instant.ofEpochSecond(row.fetchedAtEpochSecond),
            ensemble = ModelEnsemble(readings),
        )
    }

    suspend fun write(cacheKey: String, fetchedAt: Instant, ensemble: ModelEnsemble) {
        val hours = ensemble.readings.map { reading ->
            StoredHour(
                atEpochSecond = reading.at.epochSecond,
                precipitation = reading.precipitation?.values.orEmpty(),
                byModel = reading.precipitationByModel,
                temperature = reading.temperature?.values.orEmpty(),
                chanceOfRain = reading.chanceOfRain,
            )
        }
        dao.write(
            EnsembleEntity(
                cacheKey = cacheKey,
                fetchedAtEpochSecond = fetchedAt.epochSecond,
                payload = json.encodeToString(hours),
            ),
        )
    }

    suspend fun prune(cutoff: Instant) {
        dao.deleteOlderThan(cutoff.epochSecond)
    }

    data class Kept(val fetchedAt: Instant, val ensemble: ModelEnsemble)
}
