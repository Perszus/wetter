package lv.bolwarra.wetter.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.ui.screens.LocationsViewModel
import lv.bolwarra.wetter.ui.screens.WeatherViewModel

/**
 * The one factory, reaching into the container held by the Application.
 *
 * This is the whole of Wetter's dependency wiring for the UI layer. It is worth
 * the twenty lines to avoid a framework that would need annotations, a compiler
 * plugin and a build step to achieve the same (docs/design-principles.md).
 */
object WetterViewModels {

    val Factory = viewModelFactory {
        initializer {
            val container = application().container
            WeatherViewModel(
                container.repository,
                container.nowcasts,
                container.verification,
                container.airQuality,
                container.selectedLocation,
            )
        }
        initializer {
            val container = application().container
            LocationsViewModel(
                container.selectedLocation,
                container.placeSearch,
                container.savedLocations,
                container.repository,
                container.basemap,
            )
        }
    }
}

private fun androidx.lifecycle.viewmodel.CreationExtras.application(): WetterApplication =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WetterApplication
