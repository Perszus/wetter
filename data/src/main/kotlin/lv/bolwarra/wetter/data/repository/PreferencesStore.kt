// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lv.bolwarra.wetter.data.db.PreferencesDao
import lv.bolwarra.wetter.data.db.PreferencesEntity
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.settings.PrecipitationUnit
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.settings.RegionalUnits
import lv.bolwarra.wetter.domain.settings.TemperatureUnit
import lv.bolwarra.wetter.domain.settings.ThemeChoice
import lv.bolwarra.wetter.domain.settings.WindUnit

/**
 * What the reader has chosen, kept across restarts. Each choice is stored by the
 * enum's name, not its ordinal, so reordering options cannot change anybody's
 * choice; a name that no longer parses reads back as the default.
 */
class PreferencesStore internal constructor(private val dao: PreferencesDao) {

    /**
     * The current preferences, and every later change. An absent row reads as the
     * defaults.
     */
    val preferences: Flow<Preferences> = dao.observe().map { it.toPreferences() }

    /** For the widget and the worker, which have no lifecycle to collect on. */
    suspend fun current(): Preferences = dao.read().toPreferences()

    /**
     * A first guess at the units, from the first place somebody picks. Does nothing
     * once a row exists, even one equal to the defaults: a deliberate choice must not
     * be re-guessed when a pin moves. Writes a row even when the guess is the
     * defaults, as the record that the question was answered.
     */
    suspend fun seedFor(location: WeatherLocation) {
        if (dao.read() != null) return
        set(RegionalUnits.forPoint(location.latitude, location.longitude))
    }

    suspend fun set(preferences: Preferences) {
        dao.write(
            PreferencesEntity(
                temperatureUnit = preferences.temperature.name,
                windUnit = preferences.wind.name,
                precipitationUnit = preferences.precipitation.name,
                theme = preferences.theme.name,
                warnings = preferences.warnings,
            ),
        )
    }

    private fun PreferencesEntity?.toPreferences(): Preferences {
        if (this == null) return Preferences()
        return Preferences(
            temperature = temperatureUnit.toEnumOr(TemperatureUnit.CELSIUS),
            wind = windUnit.toEnumOr(WindUnit.METRES_PER_SECOND),
            precipitation = precipitationUnit.toEnumOr(PrecipitationUnit.MILLIMETRES),
            theme = theme.toEnumOr(ThemeChoice.PURE_BLACK),
            warnings = warnings,
        )
    }

    private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback
}
