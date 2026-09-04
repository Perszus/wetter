package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.repository.AirQualityRepository
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.data.repository.VerificationRepository
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.hazard.Hazards
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.provider.asWeatherError
import lv.bolwarra.wetter.domain.verification.LearnedBias
import lv.bolwarra.wetter.domain.verification.withLocalCorrection

/**
 * Holds the weather screen's state.
 *
 * It knows nothing about providers. It asks the repository for a place and gets
 * a forecast; which service answered is visible in the forecast's metadata and
 * is never a decision made here (docs/providers.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModel(
    private val repository: WeatherRepository,
    private val nowcasts: NowcastRepository,
    private val verification: VerificationRepository,
    private val airQuality: AirQualityRepository,
    private val selectedLocation: SelectedLocationStore,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<WeatherError?>(null)

    /**
     * The things derived from a forecast after it lands: radar, and whatever has
     * been learned about this place. Held together because they are produced by
     * the same background pass, and because five is as many flows as combine
     * takes before it starts handing back untyped arrays.
     */
    private data class Derived(
        val timeline: List<FusedPrecipitation> = emptyList(),
        val bias: LearnedBias? = null,
        val air: AirQuality? = null,
    )

    /**
     * The forecast for the selected place, shared so the three things derived
     * from it do not each open their own query.
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
     * The fused timeline, rebuilt on a clock rather than only when a new
     * forecast lands.
     *
     * This is the whole point of carrying radar. A model hands over a value for
     * the next hour and that value does not improve as the hour approaches - it
     * is simply what was sent. Radar re-observes every ten minutes, so a shower
     * that was a vague possibility half an hour ago is a measured object with a
     * measured heading by the time it is close, and the answer should sharpen
     * accordingly. Recomputing only when the model refreshes threw that away and
     * left the near term as stale as the thing it was supposed to improve on.
     *
     * It also has to be rebuilt simply to stay honest: the steps are anchored at
     * the instant they were built, so a timeline left alone has its leading edge
     * drift into the past and the chart quietly loses the minutes nearest now -
     * which are the ones worth having.
     *
     * The loop is a cold flow, so it stops with the last subscriber rather than
     * ticking away behind a screen nobody is looking at. Network is rate-limited
     * by the repository's own cache, not by this cadence: most of these ticks
     * only re-anchor what is already held.
     */
    private val timelines: Flow<List<FusedPrecipitation>> = forecasts
        .map { it.value }
        .flatMapLatest { forecast ->
            if (forecast == null) {
                flowOf(emptyList())
            } else {
                flow {
                    while (true) {
                        emit(
                            runCatching {
                                nowcasts.timeline(forecast, Instant.now())
                            }.getOrDefault(emptyList()),
                        )
                        delay(TIMELINE_TICK_MS)
                    }
                }
            }
        }

    /**
     * What has been learned about this place. Re-read when the forecast changes
     * rather than on the timeline's cadence: it is a database query whose answer
     * moves over weeks, not minutes.
     */
    private val biases: Flow<LearnedBias?> = forecasts.map { held ->
        held.value?.let { runCatching { verification.learnedBias(it.location) }.getOrNull() }
    }

    /**
     * What is in the air. Re-read when the forecast changes rather than on
     * the timeline's cadence: the source publishes hourly values, and the
     * repository would answer from its own cache either way.
     */
    private val air: Flow<AirQuality?> = forecasts.map { held ->
        held.value?.let { runCatching { airQuality.airQuality(it.location) }.getOrNull() }
    }

    private val derived: Flow<Derived> = combine(timelines, biases, air) { timeline, bias, air ->
        Derived(timeline = timeline, bias = bias, air = air)
    }

    val state: StateFlow<WeatherUiState> = combine(
        selectedLocation.selected,
        forecasts,
        refreshing,
        error,
        derived,
    ) { location, held, isRefreshing, failure, extra ->
        val forecast = held.value
        WeatherUiState(
            loaded = held.loaded,
            location = location,
            // Corrected here, once, rather than at each place a temperature is
            // drawn. A corrected dial above an uncorrected week would contradict
            // itself, and invisibly.
            forecast = forecast?.withLocalCorrection(extra.bias),
            isRefreshing = isRefreshing,
            error = failure,
            // Only offer the timeline while it still belongs to the forecast on
            // screen. Changing place clears it rather than briefly drawing the
            // last city's rain over the new one's name.
            timeline = if (forecast != null) extra.timeline else emptyList(),
            bias = if (forecast != null) extra.bias else null,
            airQuality = if (forecast != null) extra.air else null,
            // Read off the forecast on screen and the air beside it, so a
            // warning cannot outlive the forecast that raised it.
            hazards = if (forecast != null) {
                Hazards.scan(forecast, extra.air, Instant.now())
            } else {
                emptyList()
            },
        )
    }.stateIn(
        scope = viewModelScope,
        // Survives a rotation without a refetch, and lets go shortly after the
        // screen does.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = WeatherUiState(location = selectedLocation.selected.value),
    )

    init {
        // Changing place is what triggers a fetch, not the screen appearing. An
        // already-fresh cached forecast is shown as it is.
        viewModelScope.launch {
            selectedLocation.selected.collect { location ->
                // A failure belongs to the place it happened in. Carrying it to
                // the next one would tell somebody their new location is broken.
                error.value = null
                if (repository.needsRefresh(repository.cached(location))) refresh()
            }
        }
    }

    /**
     * Fetches only if the cache has aged out.
     *
     * Called when the screen comes back to the foreground. Freshness was
     * otherwise settled once, when this view model was built, and never asked
     * again: an app left in the background for an afternoon came back to
     * whatever it was showing when it left. The periodic worker usually covers
     * that, but "usually" is doing real work in that sentence - Doze and battery
     * saver defer it freely, and those are exactly the conditions of a phone
     * that has been in a pocket for three hours.
     */
    fun refreshIfStale() {
        viewModelScope.launch {
            val location = selectedLocation.selected.value
            if (repository.needsRefresh(repository.cached(location))) refresh()
        }
    }

    /**
     * Fetches regardless of freshness. A person who asks for a refresh has a
     * reason, and telling them the data is recent enough would be arguing with
     * them about their own window.
     */
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
         * How often the near term is rebuilt.
         *
         * A minute, which is finer than the radar publishes at. That is
         * deliberate: most ticks fetch nothing and exist to keep the leading
         * edge of the chart anchored to now, and the one that lands after a new
         * sweep picks it up within a minute of it existing.
         */
        const val TIMELINE_TICK_MS = 60_000L
    }
}
