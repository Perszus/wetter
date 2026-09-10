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
 * What the reader has chosen, kept across restarts.
 *
 * ### Stored by name, and unreadable names fall back
 *
 * Each choice is written as the enum's own name rather than its position. An
 * ordinal is smaller and is the wrong thing to persist: reordering the options
 * in the list — which is a layout decision — would silently change what everyone
 * had chosen. A name only breaks if the option is removed, and then it should
 * break.
 *
 * A value that no longer parses reads back as the default rather than throwing.
 * The alternative is an app that will not start because a preference was renamed
 * in a version somebody skipped.
 */
class PreferencesStore internal constructor(private val dao: PreferencesDao) {

    /**
     * The current preferences, and every later change.
     *
     * An absent row means nobody has opened Settings, which is the ordinary case
     * for most installs forever. It reads as the defaults rather than as
     * something missing, so nothing downstream has to handle a null.
     */
    val preferences: Flow<Preferences> = dao.observe().map { it.toPreferences() }

    /** For the widget and the worker, which have no lifecycle to collect on. */
    suspend fun current(): Preferences = dao.read().toPreferences()

    /**
     * A first guess at the units, from the first place somebody picks.
     *
     * Does nothing at all once anything has been chosen - and "chosen" means a
     * row exists, not that the values differ from the defaults. Those are the
     * same thing to look at and completely different to act on: a reader in
     * Boston who deliberately set Celsius has a row that reads exactly like a
     * fresh install, and re-guessing Fahrenheit at them because they moved a pin
     * would be the app overruling a decision they had already made.
     *
     * That is also why this writes a row even when the guess is the defaults.
     * The row is the record that the question has been answered.
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
        )
    }

    private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: fallback
}
