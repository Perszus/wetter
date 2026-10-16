// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
    // Defaulted, so an older payload still decodes and falls back to the absolute
    // thresholds until the next rebuild.
    val warmTail: Double? = null,
    val coldTail: Double? = null,
    val gustTail: Double? = null,
    val warmExtreme: Double? = null,
    val coldExtreme: Double? = null,
    val gustExtreme: Double? = null,
)

/**
 * What each date usually does here, fetched about once a month.
 *
 * Normals are never written into a forecast: the Month page asks for a forecast
 * per square and falls back to a normal only where there is none, so a square
 * becomes a forecast by itself when the forecast's reach arrives. The decade
 * behind the normals rolls forward every [REBUILD_AFTER]. The raw archive is used
 * once and dropped.
 */
class ClimatologyRepository internal constructor(
    private val archive: OpenMeteoArchive,
    private val dao: ClimateNormalsDao,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * The normals for a place, from disk where fresh and from the archive where not.
     * Returns an empty [Climatology] rather than failing: every caller decorates
     * something already on screen.
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
            // Keep whatever is on disk, however old: still a decade of real weather.
            return dao.read(key)?.let { decode(it.payload) } ?: Climatology(emptyMap())
        }

        // Off the frame that asked: a few thousand rows to group and take medians over.
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
                        warmTail = it.warmTail,
                        coldTail = it.coldTail,
                        gustTail = it.gustTail,
                        warmExtreme = it.warmExtreme,
                        coldExtreme = it.coldExtreme,
                        gustExtreme = it.gustExtreme,
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
                    warmTail = row.warmTail,
                    coldTail = row.coldTail,
                    gustTail = row.gustTail,
                    warmExtreme = row.warmExtreme,
                    coldExtreme = row.coldExtreme,
                    gustExtreme = row.gustExtreme,
                )
            },
        )
    }

    /**
     * Two decimals, about 1.1 km: coarser than the forecast cache, since a climate is
     * broader than a shower, and fine enough to put a summit and its valley in
     * different rows, since these normals set the hazard thresholds. Matches the
     * verification store and the air quality cache.
     */
    private fun keyOf(location: WeatherLocation): String = String.format(
        java.util.Locale.ROOT,
        "%.2f,%.2f",
        location.latitude,
        location.longitude,
    )

    companion object {
        /**
         * How long a set of normals stands before the decade behind it is rolled
         * forward: far more often than the answer moves, and cheap.
         */
        val REBUILD_AFTER: Duration = Duration.ofDays(30)

        private const val MONTHS = 12

        /** So 29 February survives a round trip through storage. */
        private const val LEAP_YEAR = 2024
    }
}
