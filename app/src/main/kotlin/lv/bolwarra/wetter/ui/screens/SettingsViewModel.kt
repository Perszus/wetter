package lv.bolwarra.wetter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.data.repository.PreferencesStore
import lv.bolwarra.wetter.domain.settings.Preferences

/**
 * Reads and writes the reader's choices, and does nothing else.
 *
 * Writes go straight to disk with no in-memory copy in between. A preference
 * screen that keeps its own state and saves on the way out has two answers to
 * every question and has to reconcile them; here the row redraws because the
 * stored value changed, which is also the proof that it was stored.
 */
class SettingsViewModel(private val store: PreferencesStore) : ViewModel() {

    val preferences: StateFlow<Preferences> = store.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = Preferences(),
    )

    fun set(preferences: Preferences) {
        viewModelScope.launch { store.set(preferences) }
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to let go after. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
