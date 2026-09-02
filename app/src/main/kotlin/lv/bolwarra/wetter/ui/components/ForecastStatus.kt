package lv.bolwarra.wetter.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.ui.screens.WeatherUiState
import java.time.Duration
import java.time.Instant

/**
 * One line saying how much to trust what is on screen.
 *
 * Order matters. A refresh in flight outranks an age, and a failed refresh
 * outranks both — but none of them removes the forecast. The app keeps showing
 * the last good data and says how old it is, which is more useful than an error
 * screen and more honest than silence (docs/design-principles.md).
 *
 * The provider's name deliberately does not appear here. Someone reading a rain
 * timeline is deciding whether to take a coat; which meteorological service
 * answered belongs in About (docs/providers.md).
 */
@Composable
fun ForecastStatus(
    state: WeatherUiState,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val forecast = state.forecast ?: return

    val (text, tone) = when {
        state.isRefreshing ->
            stringResource(R.string.status_updating) to StatusTone.FRESH

        state.error != null ->
            failureText(state.error) to StatusTone.FAILED

        else -> ageText(Duration.between(forecast.fetchedAt, now))
    }

    StatusLine(text = text, tone = tone, modifier = modifier)
}

@Composable
private fun failureText(error: WeatherError): String = when (error) {
    is WeatherError.Offline -> stringResource(R.string.status_offline_cached)
    is WeatherError.NoProviderAvailable -> stringResource(R.string.status_no_provider)
    else -> stringResource(R.string.status_update_failed)
}

/**
 * Ages are rounded down and coarsen as they grow: minutes for the first hour,
 * then hours, then days. Nobody needs "1 h 47 min", and the extra precision only
 * makes the line longer than the thing it is describing.
 */
@Composable
private fun ageText(age: Duration): Pair<String, StatusTone> {
    val minutes = age.toMinutes()
    return when {
        minutes < 0 -> stringResource(R.string.status_just_now) to StatusTone.FRESH
        minutes < JUST_NOW_MINUTES -> stringResource(R.string.status_just_now) to StatusTone.FRESH
        minutes < MINUTES_PER_HOUR ->
            stringResource(R.string.status_minutes_ago, minutes) to freshness(minutes)

        age.toHours() < HOURS_PER_DAY ->
            stringResource(R.string.status_hours_ago, age.toHours()) to StatusTone.STALE

        else -> stringResource(R.string.status_days_ago, age.toDays()) to StatusTone.STALE
    }
}

/**
 * The tone flips at the same half hour the repository uses to decide a forecast
 * needs replacing. Past that point the app is showing something it would rather
 * have refreshed, and the dot says so.
 */
private fun freshness(minutes: Long): StatusTone =
    if (minutes >= STALE_AFTER_MINUTES) StatusTone.STALE else StatusTone.FRESH

private const val JUST_NOW_MINUTES = 2L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val STALE_AFTER_MINUTES = 30L
