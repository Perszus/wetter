package lv.bolwarra.wetter.data.repository

import kotlinx.coroutines.flow.Flow
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The one way to get weather.
 *
 * Offline-first in the literal sense: [observe] answers from the cache without
 * waiting for anything, and [refresh] is a separate call whose failure leaves
 * what is already on screen exactly where it is. Nothing above this can put the
 * UI in a state where it is waiting on a network request to show a forecast it
 * already has (docs/design-principles.md).
 *
 * The repository does not choose providers. It asks the router, and passes on
 * which provider produced what is currently shown so that a refresh does not
 * change source for no reason (docs/providers.md).
 */
class WeatherRepository(
    private val router: WeatherProviderRouter,
    private val cache: ForecastCache,
    private val clock: Clock = Clock.systemUTC(),
    private val freshFor: Duration = DEFAULT_FRESH_FOR,
) {

    /** The cached forecast for a place, updated as refreshes land. Never blocks. */
    fun observe(location: WeatherLocation): Flow<WeatherForecast?> = cache.observe(location)

    suspend fun cached(location: WeatherLocation): WeatherForecast? = cache.read(location)

    /**
     * Fetches and stores a new forecast.
     *
     * The caller decides when; [needsRefresh] says when it is worth it. Keeping
     * those apart is what lets a manual pull-to-refresh ignore the freshness
     * window while an app launch respects it.
     */
    suspend fun refresh(location: WeatherLocation): Result<WeatherForecast> {
        val incumbentId = cache.read(location)?.provider?.id
        return router.getForecast(location, incumbentId)
            .onSuccess { cache.write(it) }
    }

    /**
     * Whether a forecast is old enough to be worth replacing.
     *
     * Half an hour, because that is roughly how often the models Wetter draws on
     * publish a new run. Refreshing more often would spend battery and somebody
     * else's bandwidth to redraw the same numbers (docs/design-principles.md).
     */
    fun needsRefresh(forecast: WeatherForecast?): Boolean {
        if (forecast == null) return true
        return age(forecast) >= freshFor
    }

    fun age(forecast: WeatherForecast): Duration =
        Duration.between(forecast.fetchedAt, Instant.now(clock))

    companion object {
        val DEFAULT_FRESH_FOR: Duration = Duration.ofMinutes(30)
    }
}
