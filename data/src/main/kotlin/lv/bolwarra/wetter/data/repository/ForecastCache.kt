package lv.bolwarra.wetter.data.repository

import kotlin.math.round
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * Where the last forecast for a place is kept.
 *
 * An interface with a single implementation, which is usually a smell. The
 * exception is earned here because the implementation is about to be replaced:
 * Room arrives in the persistence phase, and this is the seam it slots into.
 * Keeping it means the repository, the view model and the screen are written
 * once.
 */
internal interface ForecastCache {

    /** Emits the cached forecast for a place, and again whenever it is replaced. */
    fun observe(location: WeatherLocation): Flow<WeatherForecast?>

    suspend fun read(location: WeatherLocation): WeatherForecast?

    suspend fun write(forecast: WeatherForecast)
}

/**
 * A cache that lives as long as the process.
 *
 * Explicitly a placeholder. It gives the offline-first *flow* — cached first,
 * network second — without yet giving offline-first *behaviour*, because closing
 * the app empties it. The widget cannot read it either. Both of those are the
 * reason the persistence phase exists.
 */
internal class InMemoryForecastCache : ForecastCache {

    private val entries = MutableStateFlow<Map<String, WeatherForecast>>(emptyMap())

    override fun observe(location: WeatherLocation): Flow<WeatherForecast?> =
        entries.map { it[location.cacheKey()] }

    override suspend fun read(location: WeatherLocation): WeatherForecast? =
        entries.value[location.cacheKey()]

    override suspend fun write(forecast: WeatherForecast) {
        // update, not `value = value + …`: a read followed by a write can drop a
        // concurrent entry, and two locations refreshing at once is ordinary.
        entries.update { it + (forecast.location.cacheKey() to forecast) }
    }
}

/**
 * Coordinates to four decimals, which is about eleven metres.
 *
 * Rounding matters: a location re-derived from the device will differ in the
 * seventh decimal every time it is read, and an exact-match key would make every
 * refresh a cache miss and every cached forecast unreachable.
 */
internal fun WeatherLocation.cacheKey(): String {
    val lat = round(latitude * 10_000.0) / 10_000.0
    val lon = round(longitude * 10_000.0) / 10_000.0
    return "$lat,$lon"
}
