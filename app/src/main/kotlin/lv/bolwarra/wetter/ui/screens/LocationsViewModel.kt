package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import lv.bolwarra.wetter.data.location.BuiltInLocations
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * The location list.
 *
 * Backed by the built-in places until the location phase replaces them with
 * search and saved locations. The screen above is written against a list and a
 * selection, so that swap does not reach it.
 */
class LocationsViewModel(
    private val selectedLocation: SelectedLocationStore,
) : ViewModel() {

    val locations: List<WeatherLocation> = BuiltInLocations.all

    val selected: StateFlow<WeatherLocation> = selectedLocation.selected

    fun select(location: WeatherLocation) = selectedLocation.select(location)
}
