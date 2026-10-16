// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.repository.AirQualityRepository
import lv.bolwarra.wetter.data.repository.ClimatologyRepository
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.data.repository.VerificationRepository
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.hazard.Hazards
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.provider.asWeatherError
import lv.bolwarra.wetter.domain.verification.LearnedBias
import lv.bolwarra.wetter.domain.verification.withLocalCorrection

/**
 * Holds the weather screen's state. It knows nothing about providers: which
 * service answered is in the forecast's metadata (docs/providers.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModel(
    private val repository: WeatherRepository,
    private val nowcasts: NowcastRepository,
    private val verification: VerificationRepository,
    private val airQuality: AirQualityRepository,
    private val climate: ClimatologyRepository,
    private val selectedLocation: SelectedLocationStore,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    /**
     * Whether the near-term chart is being re-collected right now. Separate from
     * [refreshing], which is about the model; the reader sees one mark for both.
     */
    private val collecting = MutableStateFlow(false)
    private val error = MutableStateFlow<WeatherError?>(null)

    /**
     * What is derived from a forecast after it lands: radar, and what has been
     * learned about this place. Grouped to keep [combine] to five typed flows.
     */
    private data class Derived(
        val timeline: List<FusedPrecipitation> = emptyList(),
        val bias: LearnedBias? = null,
        val air: AirQuality? = null,
        val climatology: Climatology = Climatology(emptyMap()),
    )

    /**
     * The forecast for the selected place, shared so what is derived from it does
     * not open its own queries.
     */
    private val forecasts: StateFlow<Loaded<WeatherForecast?>> = selectedLocation.selected
        .flatMapLatest { repository.observe(it) }
        .map { Loaded(it, loaded = true) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            Loaded(null, loaded = false),
        )

    /** A value together with whether it has actually been produced yet. */
    private data class Loaded<T>(val value: T, val loaded: Boolean)

    /**
     * The fused timeline, rebuilt as radar arrives rather than only when a new
     * forecast lands: radar re-observes every few minutes, so the near term should
     * sharpen as rain approaches.
     */
    private val timelines: Flow<List<FusedPrecipitation>> = forecasts
        .map { it.value }
        .flatMapLatest { forecast ->
            if (forecast == null) {
                flowOf(emptyList())
            } else {
                // Rebuilt when the store receives a new sweep, and on a slow clock that keeps
                // the steps anchored to now so the chart's leading edge does not drift into the
                // past. Most ticks only re-anchor what is already held.
                merge(
                    nowcasts.sweeps(forecast.location),
                    flow {
                        while (true) {
                            emit(null)
                            delay(TIMELINE_TICK_MS)
                        }
                    },
                ).map {
                    collecting.value = true
                    try {
                        runCatching {
                            nowcasts.timeline(forecast, Instant.now())
                        }.getOrDefault(emptyList())
                    } finally {
                        collecting.value = false
                    }
                }
            }
        }

    /**
     * What has been learned about this place, re-read when the forecast changes: it
     * moves over weeks, not minutes.
     */
    private val biases: Flow<LearnedBias?> = forecasts.map { held ->
        held.value?.let { runCatching { verification.learnedBias(it.location) }.getOrNull() }
    }

    /** What is in the air, re-read when the forecast changes; published hourly. */
    private val air: Flow<AirQuality?> = forecasts.map { held ->
        held.value?.let { runCatching { airQuality.airQuality(it.location) }.getOrNull() }
    }

    /**
     * What this place usually does at this time of year. The repository answers
     * from disk for a month at a time.
     */
    private val normals: Flow<Climatology> = forecasts.map { held ->
        held.value?.let { runCatching { climate.normals(it.location) }.getOrNull() }
            ?: Climatology(emptyMap())
    }

    /**
     * The derived things, each arriving on its own. Each starts with a "not yet"
     * value, so the timeline is not held back by a slow air quality request.
     */
    private val derived: Flow<Derived> = combine(
        timelines.onStart { emit(emptyList()) },
        biases.onStart { emit(null) },
        air.onStart { emit(null) },
        normals.onStart { emit(Climatology(emptyMap())) },
    ) { timeline, bias, air, normals ->
        Derived(timeline = timeline, bias = bias, air = air, climatology = normals)
    }

    /**
     * One mark for "asking again", whichever request is out: the reader should not
     * need to know there is a model and a radar.
     */
    private val busy: Flow<Boolean> =
        combine(refreshing, collecting) { model, radar -> model || radar }

    val state: StateFlow<WeatherUiState> = combine(
        selectedLocation.selected,
        forecasts,
        busy,
        error,
        derived,
    ) { location, held, isRefreshing, failure, extra ->
        val forecast = held.value
        WeatherUiState(
            loaded = held.loaded,
            location = location,
            // Corrected once, here, so the dial and the week cannot disagree about the same
            // temperature.
            forecast = forecast?.withLocalCorrection(extra.bias),
            isRefreshing = isRefreshing,
            error = failure,
            // Only while the timeline belongs to the forecast on screen, so a change of place
            // never draws the last city's rain under the new one's name.
            timeline = if (forecast != null) extra.timeline else emptyList(),
            airQuality = if (forecast != null) extra.air else null,
            climatology = if (forecast != null) extra.climatology else Climatology(emptyMap()),
            // Read off the forecast on screen, so a warning cannot outlive the forecast that
            // raised it. The normals set the thresholds, matching what the notification
            // judged.
            hazards = if (forecast != null) {
                Hazards.scan(forecast, extra.air, Instant.now(), extra.climatology)
            } else {
                emptyList()
            },
        )
    }.stateIn(
        scope = viewModelScope,
        // Survives a rotation without a refetch.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = WeatherUiState(location = selectedLocation.selected.value),
    )

    init {
        // Changing place triggers a fetch; an already-fresh cached forecast is shown as
        // it is.
        viewModelScope.launch {
            selectedLocation.selected.collect { location ->
                // A failure belongs to the place it happened in.
                error.value = null
                if (repository.needsRefresh(repository.cached(location))) refresh()
            }
        }
    }

    /**
     * Fetches only if the cache has aged out. The periodic worker usually covers
     * this, but Doze and battery saver defer it freely.
     */
    fun refreshIfStale() {
        viewModelScope.launch {
            val location = selectedLocation.selected.value
            if (repository.needsRefresh(repository.cached(location))) refresh()
        }
    }

    /**
     * Coming back to the app: ask for the newest at once, not only if stale. The
     * HTTP cache and the radar's sweep schedule keep this cheap, and what is held
     * stays on screen until something newer replaces it. At most once a minute
     * ([RETURN_DEBOUNCE]).
     */
    fun refreshOnReturn() {
        val now = Instant.now()
        if (Duration.between(lastReturn, now) < RETURN_DEBOUNCE) {
            refreshIfStale()
            return
        }
        lastReturn = now
        refresh()
    }

    private var lastReturn: Instant = Instant.EPOCH

    /** Fetches regardless of freshness: the reader asked. */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val result = repository.refresh(selectedLocation.selected.value)
            error.value = result.exceptionOrNull()?.asWeatherError()
            refreshing.value = false
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /**
         * How often the near term is rebuilt: finer than radar publishes, so a new sweep
         * is picked up within a minute; most ticks only re-anchor.
         */
        const val TIMELINE_TICK_MS = 60_000L

        val RETURN_DEBOUNCE: Duration = Duration.ofMinutes(1)
    }
}
