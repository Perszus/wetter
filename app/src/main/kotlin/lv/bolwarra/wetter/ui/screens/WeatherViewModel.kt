package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.repository.WeatherRepository
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
    private val selectedLocation: SelectedLocationStore,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<WeatherError?>(null)

    val state: StateFlow<WeatherUiState> = combine(
        selectedLocation.selected,
        selectedLocation.selected.flatMapLatest { repository.observe(it) },
        refreshing,
        error,
    ) { location, forecast, isRefreshing, failure ->
        WeatherUiState(
            location = location,
            forecast = forecast,
            isRefreshing = isRefreshing,
            error = failure,
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
                if (repository.needsRefresh(repository.cached(location))) refresh()
            }
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
