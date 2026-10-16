// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.location.BuiltInLocations
import lv.bolwarra.wetter.data.location.DeviceLocation
import lv.bolwarra.wetter.data.location.SavedLocationStore
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.map.MapTileSource
import lv.bolwarra.wetter.data.provider.photon.PhotonReverseGeocoder
import lv.bolwarra.wetter.data.repository.PreferencesStore
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.domain.location.PlaceName
import lv.bolwarra.wetter.domain.location.PlaceSearch
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.ui.map.BasemapLoader
import lv.bolwarra.wetter.ui.map.TileLoader

/**
 * What makes one row in the locations list different from another: everything
 * the row shows. Coordinates alone are not an identity, since the geocoder returns
 * "Singapore" and "Singapore Island" at the same point. Formatted against the root
 * locale, so the key does not change shape with the phone's language.
 */
internal fun WeatherLocation.rowKey(): String = listOf(
    name,
    region.orEmpty(),
    country.orEmpty(),
    String.format(Locale.ROOT, "%.5f,%.5f", latitude, longitude),
).joinToString("|")

/** What the locations screen is showing. */
data class LocationsUiState(
    val query: String = "",
    val results: List<WeatherLocation> = emptyList(),
    /** The places kept, newest first. Falls back to the built-in set while empty. */
    val places: List<WeatherLocation> = emptyList(),
    val searching: Boolean = false,
    /** The search could not be run at all, as opposed to running and matching nothing. */
    val failed: Boolean = false,
) {
    val searchable: Boolean get() = query.trim().length >= PlaceSearch.MINIMUM_QUERY

    /** Ran, and found nothing. Distinct from not having run. */
    val foundNothing: Boolean get() = searchable && !searching && !failed && results.isEmpty()
}

/**
 * The locations screen. Search follows the typing, debounced, so a name typed at
 * a normal pace is one request rather than one per keystroke.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LocationsViewModel(
    private val selectedLocation: SelectedLocationStore,
    private val placeSearch: PlaceSearch,
    private val savedLocations: SavedLocationStore,
    private val repository: WeatherRepository,
    private val reverseGeocoder: PhotonReverseGeocoder,
    /** So the first place anybody picks can suggest which units they read in. */
    private val preferences: PreferencesStore,
    /** Asked only when somebody presses the button on the map. */
    private val deviceLocation: DeviceLocation,
    /**
     * For the one job here that must finish after this screen has gone: keeping a
     * place somebody just chose.
     */
    private val applicationScope: CoroutineScope,
    basemap: MapTileSource,
    /**
     * Told when the chosen place changes, so the home screen stops showing the last
     * one. Passed in, so the view model needs no Context.
     */
    private val onPlaceChanged: suspend () -> Unit = {},
) : ViewModel() {

    /** Tiles for the pin picker, cached for as long as this screen lives. */
    val tiles: TileLoader = BasemapLoader(basemap)

    private val query = MutableStateFlow("")
    private val searching = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)

    private val results: StateFlow<List<WeatherLocation>> = query
        .debounce { if (it.trim().length < PlaceSearch.MINIMUM_QUERY) 0L else TYPING_PAUSE_MS }
        .flatMapLatest { typed ->
            flow {
                if (typed.trim().length < PlaceSearch.MINIMUM_QUERY) {
                    failed.value = false
                    emit(emptyList())
                    return@flow
                }
                searching.value = true
                val outcome = placeSearch.search(typed)
                failed.value = outcome.isFailure
                searching.value = false
                emit(outcome.getOrDefault(emptyList()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val state: StateFlow<LocationsUiState> = combine(
        query,
        results,
        savedLocations.saved,
        searching,
        failed,
    ) { typed, found, saved, isSearching, didFail ->
        LocationsUiState(
            query = typed,
            results = found,
            places = placesFor(saved),
            searching = isSearching,
            failed = didFail,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = LocationsUiState(places = BuiltInLocations.all),
    )

    val selected: StateFlow<WeatherLocation> = selectedLocation.selected

    /**
     * The list to offer: the built-in set until somebody keeps a place, and always
     * the selected place, so the way back never needs a second search.
     */
    private fun placesFor(saved: List<WeatherLocation>): List<WeatherLocation> {
        val base = saved.ifEmpty { BuiltInLocations.all }
        val here = selectedLocation.selected.value
        val alreadyThere = base.any { it.isSamePlaceAs(here) }
        return if (alreadyThere) base else listOf(here) + base
    }

    fun onQueryChange(typed: String) {
        query.value = typed
    }

    fun clearQuery() {
        query.value = ""
    }

    /**
     * Choose a place, and keep it: somebody who searched for a place will want it
     * back.
     */
    fun select(location: WeatherLocation) {
        selectedLocation.select(location)
        viewModelScope.launch {
            savedLocations.save(location)
            // The first place anybody picks suggests which units they read in. Does nothing
            // after the first time (PreferencesStore.seedFor).
            preferences.seedFor(location)
            onPlaceChanged()
        }
    }

    /**
     * One fix from the device, for the map's "use my current location". Null for every
     * way it can fail: the reader taps the map instead either way.
     */
    suspend fun locate(): Coordinates? = runCatching { deviceLocation.current() }.getOrNull()

    /**
     * Keep the exact point somebody put a pin on.
     *
     * Written first, on the application's scope: the picker closes as soon as the
     * button is pressed, which would cancel this view model's own scope mid-request.
     *
     * A coordinate has no timezone, and "18:00" on a forecast means 18:00 there. The
     * device's zone stands in until the forecast request, which resolves the zone from
     * the coordinates, brings back the real one; the row is then rewritten. The cache
     * key is coordinates only, so the provisional request is not wasted.
     */
    fun savePin(at: Coordinates, name: PlaceName?) {
        val provisional = WeatherLocation(
            name = name?.label ?: at.format(),
            latitude = at.latitude,
            longitude = at.longitude,
            zone = ZoneId.systemDefault(),
            region = name?.region,
            country = name?.country,
        )

        applicationScope.launch {
            savedLocations.save(provisional)
            selectedLocation.select(provisional)
            preferences.seedFor(provisional)
            // The home screen is looking at the place selected a moment ago and would not
            // notice until its next tick.
            onPlaceChanged()

            // Then the zone, rewritten under the same key, so this replaces the place
            // rather than adding a second.
            val resolved = repository.refresh(provisional).getOrNull()?.location ?: return@launch
            if (resolved.zone == provisional.zone) return@launch

            val corrected = provisional.copy(zone = resolved.zone)
            savedLocations.save(corrected)
            selectedLocation.select(corrected)
            onPlaceChanged()
        }
    }

    /** What is at a point, or null. Called when the map settles, never mid-drag. */
    suspend fun nameOf(at: Coordinates): PlaceName? = reverseGeocoder.nameOf(at)

    /** Forget a kept place. Never touches which one is selected. */
    fun remove(location: WeatherLocation) {
        viewModelScope.launch { savedLocations.remove(location) }
    }

    private companion object {
        /** Coordinates decide identity; two names for one place are one place. */
        private fun WeatherLocation.isSamePlaceAs(other: WeatherLocation): Boolean =
            kotlin.math.abs(latitude - other.latitude) < SAME_PLACE_DEGREES &&
                kotlin.math.abs(longitude - other.longitude) < SAME_PLACE_DEGREES

        const val SAME_PLACE_DEGREES = 0.01

        /**
         * Long enough to cover the gap between fast keystrokes, short enough that
         * stopping to look does not feel like waiting.
         */
        const val TYPING_PAUSE_MS = 300L

        const val STOP_TIMEOUT_MS = 5_000L
    }
}
