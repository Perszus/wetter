package lv.bolwarra.wetter.data.location

import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.db.SelectedLocationDao
import lv.bolwarra.wetter.data.db.SelectedLocationEntity
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * Which place the app is showing.
 *
 * On disk rather than in memory, and that is not a nicety: the background
 * refresh runs in its own process lifetime and cannot fetch a location it is
 * unable to read. An in-memory choice would mean the worker either did nothing
 * or guessed.
 */
class SelectedLocationStore internal constructor(
    private val dao: SelectedLocationDao,
    scope: CoroutineScope,
    private val fallback: WeatherLocation = BuiltInLocations.default,
) {

    /**
     * The chosen place, or the default until one has been chosen.
     *
     * Never null, because every screen needs somewhere to show and "no location
     * at all" is a state the app has no useful rendering for.
     */
    val selected: StateFlow<WeatherLocation> = dao.observe()
        .map { it?.toDomain() ?: fallback }
        .stateIn(scope, SharingStarted.Eagerly, fallback)

    private val writeScope = scope

    fun select(location: WeatherLocation) {
        writeScope.launch { dao.write(location.toEntity()) }
    }

    /**
     * Read straight from storage.
     *
     * The background worker uses this rather than [selected]: in a freshly
     * started process the flow has not necessarily emitted yet, and a worker
     * that fetched the fallback because it asked too early would quietly refresh
     * the wrong place.
     */
    suspend fun current(): WeatherLocation = dao.read()?.toDomain() ?: fallback
}

private fun SelectedLocationEntity.toDomain() = WeatherLocation(
    name = name,
    latitude = latitude,
    longitude = longitude,
    zone = ZoneId.of(zoneId),
    region = region,
    country = country,
)

private fun WeatherLocation.toEntity() = SelectedLocationEntity(
    name = name,
    latitude = latitude,
    longitude = longitude,
    zoneId = zone.id,
    region = region,
    country = country,
)
