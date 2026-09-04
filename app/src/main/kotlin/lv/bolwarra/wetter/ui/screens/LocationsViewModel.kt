package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
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
import lv.bolwarra.wetter.data.location.SavedLocationStore
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.domain.location.PlaceSearch
import lv.bolwarra.wetter.domain.model.WeatherLocation

/** What the locations screen is showing. */
/**
 * What makes one row in the locations list different from another.
 *
 * Coordinates alone were used for this and are not an identity. The geocoder
 * returns "Singapore" and "Singapore Island" at exactly 1.36667, 103.8 - two
 * different places by name, one point on the earth - and a list keyed on the
 * point alone crashed outright the moment somebody searched for it.
 *
 * So the key is everything the row actually shows. Two rows identical in all of
 * it are the same row to whoever is reading, and showing one of them is the
 * right answer rather than a workaround.
 *
 * Coordinates are formatted against the root locale on purpose: a decimal comma
 * would still be deterministic, but a key that changes shape with the phone's
 * language is a bug waiting for somebody else's device.
 */
internal fun WeatherLocation.rowKey(): String = listOf(
    name,
    region.orEmpty(),
    country.orEmpty(),
    String.format(Locale.ROOT, "%.5f,%.5f", latitude, longitude),
).joinToString("|")

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
 * The locations screen.
 *
 * Search runs from the typing rather than from a button, so the list follows the
 * query without anybody having to commit to it - but it is debounced, because a
 * request per keystroke would spend somebody else's service to answer questions
 * nobody asked. "Riga" typed at a normal pace is one request, not four.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LocationsViewModel(
    private val selectedLocation: SelectedLocationStore,
    private val placeSearch: PlaceSearch,
    private val savedLocations: SavedLocationStore,
) : ViewModel() {

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
     * The list to offer, which always contains wherever you currently are.
     *
     * Until somebody has kept a place of their own the built-in set stands in,
     * because an empty list under a search box is a worse first impression than
     * a short list of somewhere to start.
     *
     * The subtle part is the selected place. It is only saved when it is chosen
     * *here*, so the one the app starts on has never been through that - and the
     * moment a first real place was kept, the built-in list stopped standing in
     * and the starting place vanished from the screen with it. Whatever is
     * selected is always listed, so the way back is never somewhere you have to
     * search for again.
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
     * Choose a place, and keep it.
     *
     * Selecting from a search result saves it too: somebody who went to the
     * trouble of finding a place will want it back without finding it again,
     * and asking them to press a second button to say so is a question with an
     * obvious answer.
     */
    fun select(location: WeatherLocation) {
        selectedLocation.select(location)
        viewModelScope.launch { savedLocations.save(location) }
    }

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
         * Long enough to cover the gap between keystrokes at speed, short enough
         * that stopping to look at the list does not feel like waiting.
         */
        const val TYPING_PAUSE_MS = 300L

        const val STOP_TIMEOUT_MS = 5_000L
    }
}
