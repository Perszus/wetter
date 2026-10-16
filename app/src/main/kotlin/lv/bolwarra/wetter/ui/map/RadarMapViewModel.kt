package lv.bolwarra.wetter.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.map.MapTileSource
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.radar.RadarMapFrame

/** What the radar map is showing, and whether it has anything yet. */
data class RadarMapState(
    val location: WeatherLocation? = null,
    val frames: List<RadarMapFrame> = emptyList(),
    val loaded: Boolean = false,
    /** The observed sweeps are in and the projection is still being worked out. */
    val projecting: Boolean = false,
    /** How far past the newest sweep the loop reaches once it is complete. */
    val horizon: java.time.Duration = NowcastRepository.MAP_HORIZON,
)

/**
 * Holds the radar map while it is open.
 *
 * Reloaded whenever a new sweep is stored for the place, so a map left open
 * across a publication moves forward on its own rather than playing the last
 * one's hour. The fields are the ones the timeline was sampled from; nothing
 * here computes weather.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadarMapViewModel(
    private val nowcasts: NowcastRepository,
    selectedLocation: SelectedLocationStore,
    basemap: MapTileSource,
) : ViewModel() {

    /** Lives as long as the map does, so reopening it costs no tiles. */
    val tiles: TileLoader = BasemapLoader(basemap)

    /**
     * The credits the map carries on its face. Both are licence terms attached
     * to the picture - the basemap's and the radar's - rather than an account
     * of how Wetter works, which is why they are the exception to rule 8 that
     * the pin picker's map already is.
     */
    val attribution: String = MapTileSource.ATTRIBUTION + " · " + nowcasts.attribution

    val state: StateFlow<RadarMapState> = selectedLocation.selected
        .flatMapLatest { location ->
            // Reloaded whenever the chart stores a newer sweep, which is the
            // moment there is something new to fetch for the map too. The
            // repository answers what it already holds first, then the
            // observed sweeps, then the projection - each as it exists.
            nowcasts.sweeps(location)
                .distinctUntilChanged()
                .flatMapLatest {
                    nowcasts.radarMap(location)
                        .map {
                            RadarMapState(
                                location = location,
                                frames = it.frames,
                                loaded = true,
                                projecting = it.projecting,
                            )
                        }
                        .catch { emit(RadarMapState(location, loaded = true)) }
                }
                // The place is known before any sweep is, so the title and the
                // "fetching" line are there from the first frame - and only
                // then, so a sweep landing while the map is open replaces the
                // picture without blanking it.
                .onStart { emit(RadarMapState(location)) }
        }
        // Tens of megabytes of grids: let them go once the map has been shut
        // for a moment, rather than for as long as the weather screen lives.
        .onCompletion { nowcasts.releaseMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RadarMapState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
