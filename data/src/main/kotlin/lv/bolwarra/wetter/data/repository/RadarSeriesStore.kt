package lv.bolwarra.wetter.data.repository

import java.time.Duration
import java.time.Instant
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
)

/**
 * Keeps the last radar projection for a place across a restart.
 *
 * The in-memory cache alone was the whole of it, on the reasoning that a nowcast
 * is worthless within the hour so there is nothing worth keeping. That is true
 * about the hour and wrong about the ten minutes that matter: those are exactly
 * the gap between the app being closed and opened again, and losing them meant
 * every launch began with the model's smooth hourly guess and only sharpened
 * once twenty-odd tiles had been fetched and matched.
 *
 * Only the sampled series is kept. The projection itself is a grid of rates
 * covering hundreds of kilometres, none of which the screen reads - it wants the
 * dozen values under one set of coordinates.
 *
 * The samples carry absolute times, which is what lets a stale row still earn
 * its place. A projection made twenty minutes ago is not wrong, it is merely
 * shorter: the part still in the future is as good as it ever was, and the
 * fusion drops the rest for being nowhere near the step it is filling.
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
                )
            },
        )
    }

    suspend fun write(cacheKey: String, sweepAt: Instant, samples: List<RadarSample>) {
        val payload = json.encodeToString(
            samples.map {
                StoredSample(
                    atEpochSecond = it.at.epochSecond,
                    leadMinutes = it.lead.toMinutes(),
                    millimetresPerHour = it.millimetresPerHour,
                    confidence = it.confidence,
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
