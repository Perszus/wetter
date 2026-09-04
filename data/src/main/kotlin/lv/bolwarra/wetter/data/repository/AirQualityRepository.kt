package lv.bolwarra.wetter.data.repository

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.air.AirQualitySource
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * Air quality for the place on screen, asked for at most twice an hour.
 *
 * Held in memory only. The source publishes hourly values, so anything kept
 * across a restart would be worth minutes at best, and unlike a forecast there
 * is nothing here worth showing stale - "the air was clean when you last opened
 * this" is not an answer to "is the air clean".
 *
 * Keyed on coordinates rounded to about a kilometre. CAMS resolves 11 km at
 * best, so a finer key would only make identical requests look different.
 */
class AirQualityRepository(private val source: AirQualitySource) {

    private data class Held(val at: Instant, val value: AirQuality)

    private val lock = Mutex()
    private val held = mutableMapOf<String, Held>()

    suspend fun airQuality(location: WeatherLocation, now: Instant = Instant.now()): AirQuality? {
        val key = keyOf(location)
        lock.withLock {
            held[key]?.takeIf { Duration.between(it.at, now) < FRESH_FOR }?.let { return it.value }
        }

        // Fetched outside the lock: a slow request should not stop another place
        // reading its own cached value.
        val fetched = source.airQuality(location.latitude, location.longitude).getOrNull()
            ?: return null

        lock.withLock { held[key] = Held(now, fetched) }
        return fetched
    }

    private fun keyOf(location: WeatherLocation): String =
        "%.2f,%.2f".format(location.latitude, location.longitude)

    private companion object {
        val FRESH_FOR: Duration = Duration.ofMinutes(30)
    }
}
