package lv.bolwarra.wetter.data.location

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lv.bolwarra.wetter.data.db.SavedLocationDao
import lv.bolwarra.wetter.data.db.SavedLocationEntity
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * The places somebody has chosen to keep.
 *
 * Search without this would be a chore rather than a feature: every glance at
 * the weather somewhere else would mean typing its name again. Keeping a handful
 * is what turns one into the other.
 */
class SavedLocationStore internal constructor(
    private val dao: SavedLocationDao,
    private val clock: Clock = Clock.systemUTC(),
) {

    val saved: Flow<List<WeatherLocation>> =
        dao.observe().map { rows -> rows.map { it.toDomain() } }

    suspend fun save(location: WeatherLocation) {
        dao.save(location.toEntity(Instant.now(clock)))
    }

    suspend fun remove(location: WeatherLocation) {
        dao.delete(keyOf(location))
    }

    companion object {
        /**
         * Two coordinates a few decimals apart are the same place.
         *
         * Rounded rather than taken from the gazetteer's own identifier, so the
         * same town found under a different spelling is kept once and the table
         * does not depend on somebody else's ids staying stable.
         */
        internal fun keyOf(location: WeatherLocation): String = String.format(
            Locale.ROOT,
            "%.3f,%.3f",
            location.latitude,
            location.longitude,
        )
    }
}

private fun SavedLocationEntity.toDomain() = WeatherLocation(
    name = name,
    latitude = latitude,
    longitude = longitude,
    zone = ZoneId.of(zoneId),
    region = region,
    country = country,
    elevationMetres = elevationMetres,
)

private fun WeatherLocation.toEntity(at: Instant) = SavedLocationEntity(
    id = SavedLocationStore.keyOf(this),
    name = name,
    latitude = latitude,
    longitude = longitude,
    zoneId = zone.id,
    region = region,
    country = country,
    elevationMetres = elevationMetres,
    addedAtEpochSecond = at.epochSecond,
)
