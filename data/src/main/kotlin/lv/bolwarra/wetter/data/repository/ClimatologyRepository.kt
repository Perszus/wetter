package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lv.bolwarra.wetter.data.db.ClimateNormalsDao
import lv.bolwarra.wetter.data.db.ClimateNormalsEntity
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoArchive
import lv.bolwarra.wetter.domain.climate.ClimateNormals
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.climate.DayNormal
import lv.bolwarra.wetter.domain.model.WeatherLocation

@Serializable
private data class StoredNormal(
    val month: Int,
    val day: Int,
    val medianHigh: Double? = null,
    val medianLow: Double? = null,
    val wetShare: Double = 0.0,
    val samples: Int = 0,
)

/**
 * What each date usually does here, kept so it is asked for about once a month.
 *
 * ### Two rolling windows, and only one of them is this class's problem
 *
 * The one people mean first is the boundary between forecast and normal, and it
 * is deliberately not stored anywhere. Nothing here writes a normal into a
 * forecast, or marks a date as "the climatology one". The Month page asks for a
 * forecast for each square and falls back to a normal only where there is none —
 * so the morning Open-Meteo's sixteen days first reach the 3rd of October, that
 * square stops being a normal and starts being a forecast, by itself, with
 * nothing to invalidate and nothing that can be left stale. A normal that had
 * been saved into the forecast would need exactly that invalidation, and would
 * be wrong the first time it was missed.
 *
 * The other window is the decade itself, and it does have to move: normals built
 * from 2015-2024 slowly stop describing the place. They are rebuilt when they
 * pass [REBUILD_AFTER], which is far more often than the answer meaningfully
 * changes and still rare enough to cost nothing.
 *
 * ### Why the raw archive is not kept
 *
 * The fetch is about eighty kilobytes of daily rows. The normals computed from
 * it are a few hundred small numbers, and there is no question anybody asks that
 * needs the rows back. So the rows are used once and dropped.
 */
class ClimatologyRepository internal constructor(
    private val archive: OpenMeteoArchive,
    private val dao: ClimateNormalsDao,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * The normals for a place, from disk where they are fresh and from the
     * archive where they are not.
     *
     * Returns an empty [Climatology] rather than failing. Every caller of this is
     * decorating something that is already on screen.
     */
    suspend fun normals(location: WeatherLocation): Climatology {
        val key = keyOf(location)
        val now = Instant.now(clock)

        dao.read(key)?.let { row ->
            val age = Duration.between(Instant.ofEpochSecond(row.builtAtEpochSecond), now)
            if (age < REBUILD_AFTER) {
                decode(row.payload)?.let { return it }
            }
        }

        val history = archive.history(location, LocalDate.now(clock))
        if (history.isEmpty()) {
            // Keep whatever is on disk, however old. A decade-old normal is
            // still a decade of real weather, and the alternative is a blank
            // square.
            return dao.read(key)?.let { decode(it.payload) } ?: Climatology(emptyMap())
        }

        // A few thousand rows to group, window and take medians over. Small, but
        // not small enough to do on the frame that asked for it.
        val built = withContext(Dispatchers.Default) { ClimateNormals.build(history) }
        dao.write(
            ClimateNormalsEntity(
                cacheKey = key,
                builtAtEpochSecond = now.epochSecond,
                payload = encode(built),
            ),
        )
        return built
    }

    private fun encode(climatology: Climatology): String {
        val stored = (1..MONTHS).flatMap { month ->
            (1..MonthDay.of(month, 1).month.maxLength()).mapNotNull { day ->
                climatology.at(LocalDate.of(LEAP_YEAR, month, day))?.let {
                    StoredNormal(
                        month = month,
                        day = day,
                        medianHigh = it.medianHigh,
                        medianLow = it.medianLow,
                        wetShare = it.wetShare,
                        samples = it.samples,
                    )
                }
            }
        }
        return json.encodeToString(stored)
    }

    private fun decode(payload: String): Climatology? {
        val stored = runCatching {
            json.decodeFromString<List<StoredNormal>>(payload)
        }.getOrNull() ?: return null
        if (stored.isEmpty()) return null

        return Climatology(
            stored.associate { row ->
                val monthDay = MonthDay.of(row.month, row.day)
                monthDay to DayNormal(
                    monthDay = monthDay,
                    medianHigh = row.medianHigh,
                    medianLow = row.medianLow,
                    wetShare = row.wetShare,
                    samples = row.samples,
                )
            },
        )
    }

    /**
     * Coarser than the forecast cache's key on purpose.
     *
     * A forecast is keyed to four decimals because a shower is smaller than a
     * suburb. A climate is not: two points ten kilometres apart have the same
     * decade of Septembers, and keying this as finely would refetch eighty
     * kilobytes for every pin nudged across a map.
     */
    private fun keyOf(location: WeatherLocation): String = String.format(
        java.util.Locale.ROOT,
        "%.1f,%.1f",
        location.latitude,
        location.longitude,
    )

    companion object {
        /**
         * How long a set of normals stands before the decade behind it is rolled
         * forward.
         *
         * A month. The answer moves by a hair in a year, so this is far more
         * often than it needs to be - and one eighty-kilobyte request a month is
         * cheap enough that there is no reason to be cleverer about it.
         */
        val REBUILD_AFTER: Duration = Duration.ofDays(30)

        private const val MONTHS = 12

        /** So 29 February survives a round trip through storage. */
        private const val LEAP_YEAR = 2024
    }
}
