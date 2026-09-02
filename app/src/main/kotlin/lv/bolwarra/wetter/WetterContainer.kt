package lv.bolwarra.wetter

import lv.bolwarra.wetter.data.WeatherData
import lv.bolwarra.wetter.data.location.SelectedLocationStore

/**
 * How Wetter is assembled.
 *
 * A file of `by lazy` rather than a dependency-injection framework. The whole
 * graph is a handful of lines and is read top to bottom; a framework would add a
 * compile step, an annotation vocabulary and a layer of indirection to solve a
 * problem this app does not have (docs/design-principles.md).
 *
 * It is short because it can be. `:data` assembles its own internals, so nothing
 * here knows what an HTTP client is or which weather services exist.
 */
class WetterContainer {

    private val weatherData by lazy { WeatherData(BuildConfig.VERSION_NAME) }

    val repository get() = weatherData.repository

    /** Each weather service's required credit, for the About section. */
    val attributions: List<String> get() = weatherData.attributions

    val selectedLocation by lazy { SelectedLocationStore() }
}
