package lv.bolwarra.wetter.ui.screens

import androidx.compose.runtime.Immutable
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.hazard.Hazard
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * Everything the weather screen renders from.
 *
 * There is deliberately no `lastUpdated` field: [WeatherForecast.fetchedAt]
 * already is that value, and two fields that must agree eventually stop
 * agreeing. The screen computes the age against a
 * clock that ticks, rather than against a timestamp captured when the state was
 * built — otherwise "2 min ago" would still say so an hour later.
 *
 * A forecast and an error are not mutually exclusive: the normal offline case is
 * a cached forecast *and* a failed refresh, and the screen must show both.
 */
@Immutable
data class WeatherUiState(
    /** The place being shown. Known before any forecast is, so the header fills in first. */
    val location: WeatherLocation? = null,
    val forecast: WeatherForecast? = null,
    /** True only while a refresh is in flight. Never blocks rendering the cache. */
    val isRefreshing: Boolean = false,
    /** The most recent failure, cleared by a successful refresh. */
    val error: WeatherError? = null,
    /**
     * The precipitation timeline with radar folded in, at ten-minute steps.
     *
     * Separate from [forecast] because it arrives later and can fail on its own:
     * radar is a second network call that is often unavailable, and a screen
     * that waited for it would be blank for the many places it has nothing to
     * say about. Empty simply means the model is on its own.
     */
    val timeline: List<FusedPrecipitation> = emptyList(),
    /**
     * What is in the air here. Null where the service has nothing for this
     * place, or has not answered yet - which is a different thing from clean
     * air, and is shown as nothing rather than as a reassuring word.
     */
    val airQuality: AirQuality? = null,
    /**
     * What each date usually does here. Empty until it has been built, and
     * empty for good where the archive could not be reached - both of which the
     * Month page draws as squares it has nothing to say about.
     */
    val climatology: Climatology = Climatology(emptyMap()),
    /** Whatever in the next day could hurt somebody, worst first. */
    val hazards: List<Hazard> = emptyList(),
    /**
     * Whether the cache has answered yet, in either direction.
     *
     * Without it a null forecast means two different things - the store has not
     * spoken, or it has nothing - and the screen was treating both as the second.
     * For the first second of every launch it told people the app needed a
     * location, over a cached forecast that was about to appear.
     */
    val loaded: Boolean = false,
) {
    val hasForecast: Boolean get() = forecast != null
}
