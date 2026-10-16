// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.repository

import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.air.AirQualitySource
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * Air quality for the place on screen, asked for at most twice an hour. In memory
 * only: stale air quality answers nothing. Keyed on coordinates rounded to about a
 * kilometre, well inside the source's resolution.
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

    /**
     * Locale.ROOT, like every other key here, so a decimal comma never changes the
     * key's shape.
     */
    private fun keyOf(location: WeatherLocation): String =
        String.format(Locale.ROOT, "%.2f,%.2f", location.latitude, location.longitude)

    private companion object {
        val FRESH_FOR: Duration = Duration.ofMinutes(30)
    }
}
