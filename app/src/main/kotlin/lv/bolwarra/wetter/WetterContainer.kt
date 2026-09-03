package lv.bolwarra.wetter

import android.content.Context
import lv.bolwarra.wetter.data.WeatherData
import lv.bolwarra.wetter.data.location.SavedLocationStore
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.data.repository.VerificationRepository
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.location.PlaceSearch

/**
 * How Wetter is assembled.
 *
 * A file of `by lazy` rather than a dependency-injection framework. The whole
 * graph is a handful of lines and is read top to bottom; a framework would add a
 * compile step, an annotation vocabulary and a layer of indirection to solve a
 * problem this app does not have (docs/design-principles.md).
 *
 * It is short because it can be. `:data` assembles its own internals, so nothing
 * here knows what an HTTP client or a database is.
 */
class WetterContainer(context: Context) {

    private val weatherData by lazy { WeatherData(context, BuildConfig.VERSION_NAME) }

    val repository: WeatherRepository get() = weatherData.repository

    /** Radar, folded into the near-term precipitation timeline. */
    val nowcasts: NowcastRepository get() = weatherData.nowcasts

    /** The record of what was forecast against what actually happened. */
    val verification: VerificationRepository get() = weatherData.verification

    val selectedLocation: SelectedLocationStore get() = weatherData.selectedLocation

    /** Finding a place by name, and the places already kept. */
    val placeSearch: PlaceSearch get() = weatherData.placeSearch

    val savedLocations: SavedLocationStore get() = weatherData.savedLocations

    /** Each weather service's required credit, for the About section. */
    val attributions: List<String> get() = weatherData.attributions
}
