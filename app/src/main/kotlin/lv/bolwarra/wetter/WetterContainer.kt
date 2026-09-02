package lv.bolwarra.wetter

import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.network.WetterHttpClient
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.data.provider.metnorway.MetNorwayProvider
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoProvider
import lv.bolwarra.wetter.data.repository.InMemoryForecastCache
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.provider.WeatherProvider

/**
 * How Wetter is assembled.
 *
 * A file of `by lazy` rather than a dependency-injection framework. The whole
 * graph is eleven lines and is read top to bottom; a framework would add a
 * compile step, an annotation vocabulary and a layer of indirection to solve a
 * problem this app does not have (docs/design-principles.md).
 *
 * Registering a new provider means adding it to [providers]. Nothing else in the
 * application changes — not the router, not the repository, not the UI
 * (docs/providers.md).
 */
class WetterContainer {

    private val httpClient by lazy { WetterHttpClient.create() }

    val providers: List<WeatherProvider> by lazy {
        listOf(
            OpenMeteoProvider(httpClient),
            MetNorwayProvider(httpClient),
        )
    }

    /** Every provider's required credit, for the About section (docs/providers.md). */
    val attributions: List<String> by lazy { providers.map { it.attribution } }

    private val router by lazy { WeatherProviderRouter(providers) }

    val repository by lazy { WeatherRepository(router, InMemoryForecastCache()) }

    val selectedLocation by lazy { SelectedLocationStore() }
}
