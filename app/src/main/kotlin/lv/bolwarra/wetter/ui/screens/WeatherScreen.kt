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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import lv.bolwarra.wetter.ui.components.DomainSwitcher
import lv.bolwarra.wetter.ui.components.EmptyState
import lv.bolwarra.wetter.ui.components.ForecastStatus
import lv.bolwarra.wetter.ui.components.WeatherHeader
import lv.bolwarra.wetter.ui.components.WeatherPlate
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
    // Which page you are on survives a rotation but not a relaunch: it is where
    // you are looking right now, not a preference.
    var domain by rememberSaveable { mutableStateOf(WeatherDomain.Today) }

    WeatherScreen(
        state = state,
        domain = domain,
        onSelectDomain = { domain = it },
        onOpenLocations = onOpenLocations,
        onOpenSettings = onOpenSettings,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )
}

/**
 * The home screen.
 *
 * Three fixed things down the top - where you are, what it is like now, and
 * which horizon you are looking at - and then the page for that horizon.
 *
 * The location and the current reading sit ABOVE the switcher on purpose: they
 * are true whichever page you pick, so switching pages must not move them.
 * Nothing above the switcher changes when you use it.
 */
@Composable
fun WeatherScreen(
    state: WeatherUiState,
    domain: WeatherDomain,
    onSelectDomain: (WeatherDomain) -> Unit,
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
            delay(CLOCK_TICK_MS)
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
            // With no forecast and no error there is simply nowhere selected, so
            // the useful action is to choose one; with an error, it is to retry.
            val failed = state.error != null
            val action = if (failed) R.string.action_retry else R.string.state_choose_location
            EmptyState(
                title = stringResource(emptyTitleFor(state.error)),
                detail = stringResource(emptyDetailFor(state.error)),
                actionLabel = stringResource(action),
                onAction = if (failed) onRetry else onOpenLocations,
            )
            return@Column
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(spacing.s))
            WeatherPlate(
                forecast = forecast,
                now = now,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(spacing.m))
            ForecastStatus(
                state = state,
                now = now,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(Modifier.height(spacing.xl))
            DomainSwitcher(selected = domain, onSelect = onSelectDomain)
            Spacer(Modifier.height(spacing.m))

            when (domain) {
                WeatherDomain.Today -> TodayPage(forecast, now)
                WeatherDomain.Week -> WeekPage(forecast, now)
                WeatherDomain.Month -> MonthPage(forecast, now)
            }

            Spacer(Modifier.height(spacing.xxl))
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

private const val CLOCK_TICK_MS = 60_000L

private val sampleState = WeatherUiState(
    location = SampleWeather.location,
    forecast = SampleWeather.forecast,
)

@Preview(name = "Today light", showBackground = true, heightDp = 900)
@Composable
private fun TodayLightPreview() {
    WetterTheme(darkTheme = false) {
        WeatherScreen(sampleState, WeatherDomain.Today, {}, {}, {}, {})
    }
}

@Preview(name = "Today dark", showBackground = true, heightDp = 900)
@Composable
private fun TodayDarkPreview() {
    WetterTheme(darkTheme = true) {
        WeatherScreen(sampleState, WeatherDomain.Today, {}, {}, {}, {})
    }
}

@Preview(name = "Week light", showBackground = true, heightDp = 900)
@Composable
private fun WeekLightPreview() {
    WetterTheme(darkTheme = false) {
        WeatherScreen(sampleState, WeatherDomain.Week, {}, {}, {}, {})
    }
}

@Preview(name = "Week dark", showBackground = true, heightDp = 900)
@Composable
private fun WeekDarkPreview() {
    WetterTheme(darkTheme = true) {
        WeatherScreen(sampleState, WeatherDomain.Week, {}, {}, {}, {})
    }
}

@Preview(name = "Offline cached", showBackground = true, heightDp = 900)
@Composable
private fun OfflinePreview() {
    WetterTheme(darkTheme = true) {
        WeatherScreen(
            sampleState.copy(error = WeatherError.Offline),
            WeatherDomain.Today,
            {},
            {},
            {},
            {},
        )
    }
}

@Preview(name = "No forecast", showBackground = true, heightDp = 500)
@Composable
private fun EmptyPreview() {
    WetterTheme(darkTheme = false) {
        WeatherScreen(
            WeatherUiState(location = SampleWeather.location),
            WeatherDomain.Today,
            {},
            {},
            {},
            {},
        )
    }
}
