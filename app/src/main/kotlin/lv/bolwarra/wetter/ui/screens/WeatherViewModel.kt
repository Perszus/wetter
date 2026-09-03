package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.repository.NowcastRepository
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.provider.asWeatherError

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
    private val selectedLocation: SelectedLocationStore,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<WeatherError?>(null)
    private val timeline = MutableStateFlow<List<FusedPrecipitation>>(emptyList())

    val state: StateFlow<WeatherUiState> = combine(
        selectedLocation.selected,
        selectedLocation.selected.flatMapLatest { repository.observe(it) },
        refreshing,
        error,
        timeline,
    ) { location, forecast, isRefreshing, failure, fused ->
        WeatherUiState(
            location = location,
            forecast = forecast,
            isRefreshing = isRefreshing,
            error = failure,
            // Only offer the timeline while it still belongs to the forecast on
            // screen. Changing place clears it rather than briefly drawing the
            // last city's rain over the new one's name.
            timeline = if (forecast != null) fused else emptyList(),
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
                timeline.value = emptyList()
                if (repository.needsRefresh(repository.cached(location))) refresh()
            }
        }

        // Radar follows the forecast rather than being fetched alongside it. It
        // is a second network call that fails independently and often has
        // nothing to say, so the screen must never be waiting on it: the model
        // draws immediately and radar sharpens the near term when it arrives.
        viewModelScope.launch {
            selectedLocation.selected
                .flatMapLatest { repository.observe(it) }
                .collect { forecast ->
                    timeline.value = if (forecast == null) {
                        emptyList()
                    } else {
                        runCatching {
                            nowcasts.timeline(forecast, Instant.now())
                        }.getOrDefault(emptyList())
                    }
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
    }
}
