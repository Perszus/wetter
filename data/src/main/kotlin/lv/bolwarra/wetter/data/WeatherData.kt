package lv.bolwarra.wetter.data

import lv.bolwarra.wetter.data.network.WetterHttpClient
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.data.provider.metnorway.MetNorwayProvider
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoProvider
import lv.bolwarra.wetter.data.repository.InMemoryForecastCache
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.provider.WeatherProvider

/**
 * Everything `:data` assembles for itself.
 *
 * The HTTP client, the provider implementations and the router are all details
 * of how weather is fetched, and none of them is any of the application's
 * business. What comes back out is a [WeatherRepository] and a list of
 * attributions — one thing to call and one obligation to honour.
 *
 * This is what the module split is for. Before it, the application held a Ktor
 * client it had no reason to know about; now it cannot, because it cannot see
 * the type.
 *
 * @param applicationVersion goes into the User-Agent, which MET Norway's terms
 *   require to identify the application. `:data` cannot read it for itself and
 *   should not try.
 */
class WeatherData(applicationVersion: String) {

    private val httpClient by lazy {
        WetterHttpClient.create(WetterHttpClient.userAgent(applicationVersion))
    }

    /**
     * The registered providers, highest-level first only by convention — the
     * router ranks them per location and this order means nothing to it.
     *
     * Adding a provider is this line and nothing else.
     */
    val providers: List<WeatherProvider> by lazy {
        listOf(
            OpenMeteoProvider(httpClient),
            MetNorwayProvider(httpClient),
        )
    }

    /** Each provider's required credit, for the About section. */
    val attributions: List<String> by lazy { providers.map { it.attribution } }

    private val router by lazy { WeatherProviderRouter(providers) }

    val repository: WeatherRepository by lazy {
        WeatherRepository(router, InMemoryForecastCache())
    }
}
