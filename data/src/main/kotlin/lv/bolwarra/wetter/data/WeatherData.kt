package lv.bolwarra.wetter.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import lv.bolwarra.wetter.data.db.WetterDatabase
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.network.WetterHttpClient
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.data.provider.metnorway.MetNorwayProvider
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoProvider
import lv.bolwarra.wetter.data.provider.rainviewer.AndroidTileDecoder
import lv.bolwarra.wetter.data.provider.rainviewer.RainViewerRadarSource
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.data.repository.RoomForecastCache
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.provider.WeatherProvider

/**
 * Everything `:data` assembles for itself.
 *
 * The database, the HTTP client, the provider implementations and the router are
 * all details of how weather is obtained and kept, and none of them is any of
 * the application's business. What comes back out is a [WeatherRepository], a
 * [SelectedLocationStore] and a list of attributions.
 *
 * This is what the module split is for. Before it, the application held a Ktor
 * client it had no reason to know about; now it cannot, because it cannot see
 * the type.
 *
 * @param applicationVersion goes into the User-Agent, which MET Norway's terms
 *   require to identify the application. `:data` cannot read it for itself and
 *   should not try.
 */
class WeatherData(
    context: Context,
    applicationVersion: String,
    /**
     * Outlives any one screen, because the location store writes to disk and a
     * write must not be cancelled by whoever happened to trigger it navigating
     * away. Owned by the process, like the container that holds it.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val appContext = context.applicationContext

    private val database by lazy { WetterDatabase.create(appContext) }

    private val httpClient by lazy {
        WetterHttpClient.create(WetterHttpClient.userAgent(applicationVersion))
    }

    /**
     * The registered providers. The order here means nothing to the router,
     * which ranks them per location.
     *
     * Adding a provider is this line and nothing else.
     */
    val providers: List<WeatherProvider> by lazy {
        listOf(
            OpenMeteoProvider(httpClient),
            MetNorwayProvider(httpClient),
        )
    }

    /**
     * Radar observations, which answer a different question from the forecast
     * providers and so are registered separately (docs/providers.md). A model
     * says what should happen; radar says what is happening.
     */
    private val radarSource by lazy {
        RainViewerRadarSource(httpClient, AndroidTileDecoder())
    }

    val nowcasts: NowcastRepository by lazy { NowcastRepository(radarSource) }

    /** Every required credit, for the About section. Radar included. */
    val attributions: List<String> by lazy {
        providers.map { it.attribution } + radarSource.attribution
    }

    private val router by lazy { WeatherProviderRouter(providers) }

    val repository: WeatherRepository by lazy {
        WeatherRepository(
            router = router,
            cache = RoomForecastCache(database.forecasts(), WetterHttpClient.json),
        )
    }

    val selectedLocation: SelectedLocationStore by lazy {
        SelectedLocationStore(database.selectedLocation(), scope)
    }
}
