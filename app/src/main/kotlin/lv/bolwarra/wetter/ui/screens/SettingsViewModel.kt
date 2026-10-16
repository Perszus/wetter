// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.location.SelectedLocationStore
import lv.bolwarra.wetter.data.repository.PreferencesStore
import lv.bolwarra.wetter.data.repository.WeatherRepository
import lv.bolwarra.wetter.domain.settings.Preferences

/**
 * Reads and writes the reader's choices, and says what the app is currently
 * showing them. Writes go straight to disk; a row redraws because the stored
 * value changed.
 */
class SettingsViewModel(
    private val store: PreferencesStore,
    selectedLocation: SelectedLocationStore,
    repository: WeatherRepository,
) : ViewModel() {

    val preferences: StateFlow<Preferences> = store.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = Preferences(),
    )

    /** The place on the main screen, who forecast it, and when. */
    val summary: StateFlow<Summary?> = selectedLocation.selected
        .map { location ->
            val forecast = runCatching { repository.cached(location) }.getOrNull()
            Summary(location.name, forecast?.provider?.name, forecast?.fetchedAt)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun set(preferences: Preferences) {
        viewModelScope.launch { store.set(preferences) }
    }

    data class Summary(val place: String, val source: String?, val fetchedAt: Instant?)

    private companion object {
        /** Long enough to survive a rotation, short enough to let go after. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
