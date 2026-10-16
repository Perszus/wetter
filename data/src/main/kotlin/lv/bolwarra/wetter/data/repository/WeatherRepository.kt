// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.model.withDailyReadFromHours

/**
 * The one way to get weather. [observe] answers from the cache without waiting;
 * [refresh] is a separate call whose failure leaves what is on screen where it is
 * (docs/design-principles.md). Providers are chosen by the router, told which one
 * produced what is shown so a refresh does not switch source for no reason.
 */
class WeatherRepository internal constructor(
    private val router: WeatherProviderRouter,
    private val cache: ForecastCache,
    private val clock: Clock = Clock.systemUTC(),
    private val freshFor: Duration = DEFAULT_FRESH_FOR,
) {

    /** The cached forecast for a place, updated as refreshes land. Never blocks. */
    fun observe(location: WeatherLocation): Flow<WeatherForecast?> = cache.observe(location)

    suspend fun cached(location: WeatherLocation): WeatherForecast? = cache.read(location)

    /**
     * Fetches and stores a new forecast. The caller decides when; [needsRefresh] says
     * when it is worth it, so a manual refresh can ignore the window.
     */
    suspend fun refresh(location: WeatherLocation): Result<WeatherForecast> {
        val incumbentId = cache.read(location)?.provider?.id
        return router.getForecast(location, incumbentId)
            // After the router, because the router is where two services get
            // joined and a joined forecast can have a day summarised by one and
            // drawn by the other. See withDailyReadFromHours.
            .map { it.withDailyReadFromHours() }
            .onSuccess { cache.write(it) }
    }

    /**
     * Whether a forecast is old enough to be worth asking again: ten minutes, the
     * radar's clock, since models publish at a different minute for every service and
     * run. Asking costs little when nothing changed: MET Norway revalidates to "not
     * modified" and the disk cache answers inside `Expires` (WetterHttpClient).
     */
    fun needsRefresh(forecast: WeatherForecast?): Boolean {
        if (forecast == null) return true
        return age(forecast) >= freshFor
    }

    fun age(forecast: WeatherForecast): Duration =
        Duration.between(forecast.fetchedAt, Instant.now(clock))

    companion object {
        val DEFAULT_FRESH_FOR: Duration = Duration.ofMinutes(10)
    }
}
