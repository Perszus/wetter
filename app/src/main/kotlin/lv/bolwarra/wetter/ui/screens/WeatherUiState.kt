package lv.bolwarra.wetter.ui.screens

import androidx.compose.runtime.Immutable
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
) {
    val hasForecast: Boolean get() = forecast != null
}
