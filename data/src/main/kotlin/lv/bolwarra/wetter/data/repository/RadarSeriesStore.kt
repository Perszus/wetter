// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.repository

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lv.bolwarra.wetter.data.db.RadarSeriesDao
import lv.bolwarra.wetter.data.db.RadarSeriesEntity
import lv.bolwarra.wetter.domain.radar.RadarSample

@Serializable
private data class StoredSample(
    val atEpochSecond: Long,
    val leadMinutes: Long,
    val millimetresPerHour: Float,
    val confidence: Float,
    /** Defaulted, so rows written before the column existed still read back. */
    val motionQuality: Float = 1f,
)

/**
 * Keeps the last radar projection for a place across a restart, so a launch
 * starts from it rather than from the model's hourly guess while new sweeps are
 * fetched.
 *
 * Only the samples under the place are kept, with absolute times: a projection
 * made twenty minutes ago is shorter, not wrong, and the fusion drops the steps
 * that have passed.
 */
internal class RadarSeriesStore(private val dao: RadarSeriesDao, private val json: Json) {

    suspend fun read(cacheKey: String): Kept? {
        val row = dao.read(cacheKey) ?: return null
        val samples = runCatching {
            json.decodeFromString<List<StoredSample>>(row.payload)
        }.getOrNull() ?: return null

        return Kept(
            sweepAt = Instant.ofEpochSecond(row.sweepAtEpochSecond),
            samples = samples.map {
                RadarSample(
                    at = Instant.ofEpochSecond(it.atEpochSecond),
                    lead = Duration.ofMinutes(it.leadMinutes),
                    millimetresPerHour = it.millimetresPerHour,
                    confidence = it.confidence,
                    motionQuality = it.motionQuality,
                )
            },
        )
    }

    /** When the kept sweep for a place changes, and nothing else. */
    fun sweeps(cacheKey: String): Flow<Instant?> = dao.observeSweep(cacheKey)
        .map { it?.let(Instant::ofEpochSecond) }
        .distinctUntilChanged()

    suspend fun write(cacheKey: String, sweepAt: Instant, samples: List<RadarSample>) {
        val payload = json.encodeToString(
            samples.map {
                StoredSample(
                    atEpochSecond = it.at.epochSecond,
                    leadMinutes = it.lead.toMinutes(),
                    millimetresPerHour = it.millimetresPerHour,
                    confidence = it.confidence,
                    motionQuality = it.motionQuality,
                )
            },
        )
        dao.write(RadarSeriesEntity(cacheKey, sweepAt.epochSecond, payload))
    }

    /** Drop projections long past being worth anything. */
    suspend fun prune(before: Instant) {
        dao.deleteOlderThan(before.epochSecond)
    }

    data class Kept(val sweepAt: Instant, val samples: List<RadarSample>)
}
