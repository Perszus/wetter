// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.db.WetterDatabase
import lv.bolwarra.wetter.data.location.DeviceLocation
import lv.bolwarra.wetter.data.location.OpenMeteoGeocoder
import lv.bolwarra.wetter.data.location.SavedLocationStore
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.map.MapTileSource
import lv.bolwarra.wetter.data.network.WetterHttpClient
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.data.provider.metar.MetarObservationSource
import lv.bolwarra.wetter.data.provider.metnorway.MetNorwayProvider
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoAirQuality
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoArchive
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoEnsemble
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoProvider
import lv.bolwarra.wetter.data.provider.photon.PhotonReverseGeocoder
import lv.bolwarra.wetter.data.provider.rainviewer.AndroidTileDecoder
import lv.bolwarra.wetter.data.provider.rainviewer.RainViewerRadarSource
import lv.bolwarra.wetter.data.repository.AirQualityRepository
import lv.bolwarra.wetter.data.repository.AnnouncedHazardStore
import lv.bolwarra.wetter.data.repository.ClimatologyRepository
import lv.bolwarra.wetter.data.repository.EnsembleStore
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.data.repository.PreferencesStore
import lv.bolwarra.wetter.data.repository.ProviderHealthStore
import lv.bolwarra.wetter.data.repository.RadarSeriesStore
import lv.bolwarra.wetter.data.repository.RoomForecastCache
import lv.bolwarra.wetter.data.repository.VerificationRepository
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.location.PlaceSearch
import lv.bolwarra.wetter.domain.provider.ProviderHealthRegistry
import lv.bolwarra.wetter.domain.provider.WeatherProvider

/**
 * Everything `:data` assembles for itself. The database, HTTP client, providers
 * and router stay inside; what comes out is a [WeatherRepository], a
 * [SelectedLocationStore] and a list of attributions.
 *
 * @param applicationVersion goes into the User-Agent, which MET Norway's terms
 * require to identify the application.
 */
class WeatherData(
    context: Context,
    applicationVersion: String,
    /**
     * Outlives any one screen, so a write to disk is not cancelled by whoever
     * triggered it navigating away.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val appContext = context.applicationContext

    private val database by lazy { WetterDatabase.create(appContext) }

    private val httpClient by lazy {
        WetterHttpClient.create(
            WetterHttpClient.userAgent(applicationVersion),
            cacheDirectory = java.io.File(appContext.cacheDir, "http"),
        )
    }

    /**
     * The registered providers, in no meaningful order: the router ranks them per
     * location.
     */
    val providers: List<WeatherProvider> by lazy {
        listOf(
            OpenMeteoProvider(httpClient),
            MetNorwayProvider(httpClient),
        )
    }

    /**
     * Radar observations, registered apart from the forecast providers
     * (docs/providers.md): a model says what should happen, radar what is happening.
     */
    private val radarSource by lazy {
        RainViewerRadarSource(httpClient, AndroidTileDecoder())
    }

    /**
     * Aerodrome reports, never shown: they exist so predictions can be checked
     * against something that was not itself a prediction.
     */
    private val observationSource by lazy { MetarObservationSource(httpClient) }

    /**
     * The record of what was forecast and what happened, the only data in the
     * database that cannot be fetched again.
     */
    val verification: VerificationRepository by lazy {
        VerificationRepository(database.forecastRecords(), observationSource)
    }

    val nowcasts: NowcastRepository by lazy {
        NowcastRepository(
            source = radarSource,
            ensembles = OpenMeteoEnsemble(httpClient),
            seriesStore = RadarSeriesStore(database.radarSeries(), WetterHttpClient.json),
            ensembleStore = EnsembleStore(database.ensembles(), WetterHttpClient.json),
            // So every projection is scored against the sweep that answers it.
            verification = verification,
            // So a screen answered from disk still fetches a fresher one behind itself.
            scope = scope,
        )
    }

    /**
     * A scope that outlives any one screen, for the few jobs that must finish after it
     * closes, such as keeping a place somebody just chose. Anything that only matters
     * while somebody is looking belongs on a view model.
     */
    val applicationScope: CoroutineScope get() = scope

    /** One location fix, when the reader asks for one and never otherwise. */
    val deviceLocation: DeviceLocation by lazy { DeviceLocation(appContext) }

    /** What the reader has chosen: units, and which plate to draw on. */
    val preferences: PreferencesStore by lazy { PreferencesStore(database.preferences()) }

    /** What has already been warned about, so it is not warned about twice. */
    val announcedHazards: AnnouncedHazardStore by lazy {
        AnnouncedHazardStore(database.announcedHazards())
    }

    /**
     * What each date usually does here. Separate from the providers: it answers what
     * has happened, not what will.
     */
    val climatology: ClimatologyRepository by lazy {
        ClimatologyRepository(
            archive = OpenMeteoArchive(httpClient),
            dao = database.climateNormals(),
            json = WetterHttpClient.json,
        )
    }

    /** What is in the air: its own host, models and cadence. */
    private val airQualitySource by lazy { OpenMeteoAirQuality(httpClient) }

    val airQuality: AirQualityRepository by lazy { AirQualityRepository(airQualitySource) }

    /** Every required credit, for the About section. */
    val attributions: List<String> by lazy {
        (
            providers.map { it.attribution } +
                radarSource.attribution +
                observationSource.attribution +
                placeSearch.attribution +
                PhotonReverseGeocoder.ATTRIBUTION +
                airQualitySource.attribution +
                "Map tiles ${MapTileSource.ATTRIBUTION}"
            )
            // One service is credited once.
            .distinct()
    }

    /** What is known about each provider, remembered across restarts. */
    private val healthRegistry by lazy { ProviderHealthRegistry() }

    private val healthStore by lazy { ProviderHealthStore(database.providerHealth()) }

    private val router by lazy {
        // Restored in the background: a cold start must not wait on a disk read.
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

    /** What is at a dropped pin, when anything is. */
    val reverseGeocoder: PhotonReverseGeocoder by lazy { PhotonReverseGeocoder(httpClient) }

    /** Tiles for the map a pin is dropped on. Not weather; see MapTileSource. */
    val basemap: MapTileSource by lazy { MapTileSource(httpClient) }

    /** Finding a place by name. */
    val placeSearch: PlaceSearch by lazy { OpenMeteoGeocoder(httpClient) }

    /** The places somebody has kept, so search is used once rather than daily. */
    val savedLocations: SavedLocationStore by lazy {
        SavedLocationStore(database.savedLocations())
    }
}
