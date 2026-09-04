package lv.bolwarra.wetter.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.db.WetterDatabase
import lv.bolwarra.wetter.data.location.OpenMeteoGeocoder
import lv.bolwarra.wetter.data.location.SavedLocationStore
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.network.WetterHttpClient
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.data.provider.metar.MetarObservationSource
import lv.bolwarra.wetter.data.provider.metnorway.MetNorwayProvider
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoAirQuality
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoEnsemble
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoProvider
import lv.bolwarra.wetter.data.provider.rainviewer.AndroidTileDecoder
import lv.bolwarra.wetter.data.provider.rainviewer.RainViewerRadarSource
import lv.bolwarra.wetter.data.repository.AirQualityRepository
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.data.repository.ProviderHealthStore
import lv.bolwarra.wetter.data.repository.RadarSeriesStore
import lv.bolwarra.wetter.data.repository.RoomForecastCache
import lv.bolwarra.wetter.data.repository.VerificationRepository
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.location.PlaceSearch
import lv.bolwarra.wetter.domain.provider.ProviderHealthRegistry
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

    /**
     * Aerodrome reports: the only measurements of what the weather actually was
     * that this app can reach. Not shown anywhere - they exist so predictions
     * can be checked against something that was not itself a prediction.
     */
    private val observationSource by lazy { MetarObservationSource(httpClient) }

    /**
     * The record of what was forecast and what happened. The one part of this
     * app that gets better on its own, and the one thing in the database that
     * cannot be fetched again if it is lost.
     */
    val verification: VerificationRepository by lazy {
        VerificationRepository(database.forecastRecords(), observationSource)
    }

    val nowcasts: NowcastRepository by lazy {
        NowcastRepository(
            source = radarSource,
            ensembles = OpenMeteoEnsemble(httpClient),
            seriesStore = RadarSeriesStore(database.radarSeries(), WetterHttpClient.json),
            // So every projection is scored against the sweep that answers it.
            verification = verification,
            // So a screen answered from disk still goes and fetches a fresher
            // one behind itself.
            scope = scope,
        )
    }

    /**
     * What is in the air, which is not weather and is not fetched with it.
     * Its own host, its own models, its own cadence.
     */
    private val airQualitySource by lazy { OpenMeteoAirQuality(httpClient) }

    val airQuality: AirQualityRepository by lazy { AirQualityRepository(airQualitySource) }

    /** Every required credit, for the About section. */
    val attributions: List<String> by lazy {
        (
            providers.map { it.attribution } +
                radarSource.attribution +
                observationSource.attribution +
                placeSearch.attribution +
                airQualitySource.attribution
            )
            // Open-Meteo answers for the forecast, the geocoder and the air.
            // One service should be credited once.
            .distinct()
    }

    /** What is known about each provider, remembered across restarts. */
    private val healthRegistry by lazy { ProviderHealthRegistry() }

    private val healthStore by lazy { ProviderHealthStore(database.providerHealth()) }

    private val router by lazy {
        // Restored in the background: a cold start must not wait on a disk read
        // to answer, and anything learned before it lands wins anyway.
        scope.launch { runCatching { healthStore.restoreInto(healthRegistry) } }
        WeatherProviderRouter(
            providers = providers,
            health = healthRegistry,
            onHealthChanged = { healthStore.save(healthRegistry) },
        )
    }

    val repository: WeatherRepository by lazy {
        WeatherRepository(
            router = router,
            cache = RoomForecastCache(database.forecasts(), WetterHttpClient.json),
        )
    }

    val selectedLocation: SelectedLocationStore by lazy {
        SelectedLocationStore(database.selectedLocation(), scope)
    }

    /** Finding a place by name. */
    val placeSearch: PlaceSearch by lazy { OpenMeteoGeocoder(httpClient) }

    /** The places somebody has kept, so search is used once rather than daily. */
    val savedLocations: SavedLocationStore by lazy {
        SavedLocationStore(database.savedLocations())
    }
}
