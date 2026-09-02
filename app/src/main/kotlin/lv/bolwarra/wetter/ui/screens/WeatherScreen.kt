package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import kotlinx.coroutines.delay
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.ui.WetterViewModels
import lv.bolwarra.wetter.ui.components.CurrentConditions
import lv.bolwarra.wetter.ui.components.EmptyState
import lv.bolwarra.wetter.ui.components.ForecastStatus
import lv.bolwarra.wetter.ui.components.WeatherHeader
import lv.bolwarra.wetter.ui.preview.SampleWeather
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Connects the weather screen to its view model. Kept separate from
 * [WeatherScreen] so the screen itself takes only a state and callbacks, and can
 * be previewed and tested without a view model.
 */
@Composable
fun WeatherRoute(
    onOpenLocations: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeatherViewModel = viewModel(factory = WetterViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WeatherScreen(
        state = state,
        onOpenLocations = onOpenLocations,
        onOpenSettings = onOpenSettings,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )
}

/**
 * The home screen.
 *
 * This phase draws the frame, the present reading and the freshness line. The
 * precipitation timeline, the temperature curve, next rain and the daily
 * forecast land here in the visual phase, now that there is real data to draw —
 * a placeholder chart would only have taught us that fake data looks good
 * (docs/design-principles.md).
 */
@Composable
fun WeatherScreen(
    state: WeatherUiState,
    onOpenLocations: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing

    // The age on screen has to keep counting, or "2 min ago" is still on display
    // an hour later. A minute is the finest the line ever reads, so a minute is
    // as often as this needs to wake up.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(AGE_TICK_MS)
            now = Instant.now()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen),
    ) {
        WeatherHeader(
            locationName = state.location?.name,
            onOpenLocations = onOpenLocations,
            onOpenSettings = onOpenSettings,
        )

        val forecast = state.forecast
        if (forecast == null) {
            EmptyState(
                title = stringResource(emptyTitleFor(state.error)),
                detail = stringResource(emptyDetailFor(state.error)),
                actionLabel = stringResource(
                    if (state.error ==
                        null
                    ) {
                        R.string.state_choose_location
                    } else {
                        R.string.action_retry
                    },
                ),
                onAction = if (state.error == null) onOpenLocations else onRetry,
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(spacing.l))
                CurrentConditions(forecast.current)
                Spacer(Modifier.height(spacing.m))
                ForecastStatus(state = state, now = now)
                Spacer(Modifier.height(spacing.section))
            }
        }
    }
}

private fun emptyTitleFor(error: WeatherError?) = when (error) {
    null -> R.string.state_no_forecast
    is WeatherError.Offline -> R.string.state_offline
    is WeatherError.NoProviderAvailable -> R.string.state_no_provider
    else -> R.string.state_could_not_fetch
}

private fun emptyDetailFor(error: WeatherError?) = when (error) {
    null -> R.string.state_no_forecast_detail
    is WeatherError.Offline -> R.string.state_offline_detail
    is WeatherError.NoProviderAvailable -> R.string.state_no_provider_detail
    else -> R.string.state_could_not_fetch_detail
}

private const val AGE_TICK_MS = 60_000L

@Preview(name = "Weather · empty · light", showBackground = true)
@Composable
private fun WeatherScreenEmptyPreview() {
    WetterTheme(darkTheme = false) {
        WeatherScreen(WeatherUiState(location = SampleWeather.location), {}, {}, {})
    }
}

@Preview(name = "Weather · forecast · light", showBackground = true)
@Composable
private fun WeatherScreenLightPreview() {
    WetterTheme(darkTheme = false) {
        WeatherScreen(sampleState, {}, {}, {})
    }
}

@Preview(name = "Weather · forecast · dark", showBackground = true)
@Composable
private fun WeatherScreenDarkPreview() {
    WetterTheme(darkTheme = true) {
        WeatherScreen(sampleState, {}, {}, {})
    }
}

@Preview(name = "Weather · stale, offline · dark", showBackground = true)
@Composable
private fun WeatherScreenOfflinePreview() {
    WetterTheme(darkTheme = true) {
        WeatherScreen(sampleState.copy(error = WeatherError.Offline), {}, {}, {})
    }
}

private val sampleState = WeatherUiState(
    location = SampleWeather.location,
    forecast = SampleWeather.forecast,
)
