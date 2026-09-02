package lv.bolwarra.wetter.data.repository

import android.util.Log
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import lv.bolwarra.wetter.data.db.ForecastDao
import lv.bolwarra.wetter.data.db.ForecastEntity
import lv.bolwarra.wetter.data.db.StoredForecast
import lv.bolwarra.wetter.data.db.toDomain
import lv.bolwarra.wetter.data.db.toStored
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * The forecast cache, on disk.
 *
 * This is what makes the app offline-first in behaviour rather than only in
 * shape: a forecast fetched yesterday is on screen before the network is even
 * asked, and the background refresh has somewhere to put its work that outlives
 * its own process.
 */
internal class RoomForecastCache(
    private val dao: ForecastDao,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) : ForecastCache {

    override fun observe(location: WeatherLocation): Flow<WeatherForecast?> =
        dao.observe(location.cacheKey()).map { it?.decode() }

    override suspend fun read(location: WeatherLocation): WeatherForecast? =
        dao.read(location.cacheKey())?.decode()

    override suspend fun write(forecast: WeatherForecast) {
        dao.write(
            ForecastEntity(
                cacheKey = forecast.location.cacheKey(),
                fetchedAtEpochSecond = forecast.fetchedAt.epochSecond,
                providerId = forecast.provider.id,
                payload = json.encodeToString(forecast.toStored()),
            ),
        )
        dao.deleteOlderThan(Instant.now(clock).minus(KEEP_FOR).epochSecond)
    }

    /**
     * A payload written by a different version of the app may no longer parse.
     * That is a cache miss, not a crash: the cost is one network request, and
     * the row is left for the next write to replace.
     */
    private fun ForecastEntity.decode(): WeatherForecast? = try {
        json.decodeFromString<StoredForecast>(payload).toDomain()
    } catch (failure: Exception) {
        Log.w(TAG, "discarding an unreadable cached forecast for $cacheKey", failure)
        null
    }

    private companion object {
        const val TAG = "ForecastCache"

        /**
         * A week. Long past being useful as a forecast, but a cache that only
         * ever grows is a leak with a nicer name, and this is the cheapest place
         * to bound it.
         */
        val KEEP_FOR: Duration = Duration.ofDays(7)
    }
}
