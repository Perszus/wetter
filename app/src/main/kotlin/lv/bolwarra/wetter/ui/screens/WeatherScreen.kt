package lv.bolwarra.wetter.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.ui.WetterViewModels
import lv.bolwarra.wetter.ui.components.DomainSwitcher
import lv.bolwarra.wetter.ui.components.EmptyState
import lv.bolwarra.wetter.ui.components.PlateMark
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
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Every return to the foreground asks whether the data has aged out.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshIfStale() }
    }

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

    // The whole screen is read against this instant - the mark on the dial, the
    // rolling rain window, which day "tomorrow" means - so it has to keep up
    // with the clock rather than with when the screen happened to open.
    //
    // Aligned to the minute rather than every sixty seconds from launch, because
    // this drives a clock face and a clock face should move when the minute
    // does. Tied to RESUMED for the same reason it is re-read on arrival: a
    // frozen process resumes mid-delay, and the first thing shown would
    // otherwise be the time it was put down.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = Instant.now()
                delay(millisUntilNextMinute(now))
            }
        }
    }

    // Which mark beside the dial is explaining itself. Held here rather than in
    // the dial because putting it away is a screen-wide gesture - a tap on the
    // chart, the switcher or bare background should dismiss it, and none of
    // those are things the dial can see.
    var explaining by remember { mutableStateOf<PlateMark?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen)
            .dismissOnOutsideTap(active = explaining != null) { explaining = null },
    ) {
        WeatherHeader(
            locationName = state.location?.name,
            onOpenLocations = onOpenLocations,
            onOpenSettings = onOpenSettings,
        )

        val forecast = state.forecast
        if (forecast == null && !state.loaded) {
            // The store has not answered yet. Saying anything here would be
            // guessing, and the guess that used to be made - that there is no
            // location - was both alarming and usually wrong.
            return@Column
        }
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
                timeline = state.timeline,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                explaining = explaining,
                onExplain = { explaining = it },
            )
            Spacer(Modifier.height(spacing.xl))
            DomainSwitcher(selected = domain, onSelect = onSelectDomain)
            Spacer(Modifier.height(spacing.m))

            AnimatedContent(
                targetState = domain,
                transitionSpec = {
                    // The pages sit in a row, so moving between them should look
                    // like moving along that row: pick Week from Today and the
                    // new page arrives from the right, because it is to the
                    // right. A cut gives no sense of where you have gone.
                    val forward = targetState.ordinal > initialState.ordinal
                    val enter = slideInHorizontally { width ->
                        if (forward) width else -width
                    } + fadeIn()
                    val exit = slideOutHorizontally { width ->
                        if (forward) -width else width
                    } + fadeOut()
                    // Height is not animated: the pages differ a lot in length
                    // and watching the page below stretch is worse than having
                    // it settle at once.
                    enter togetherWith exit using SizeTransform(clip = false)
                },
                label = "domain",
            ) { page ->
                when (page) {
                    WeatherDomain.Today ->
                        TodayPage(forecast, now, state.timeline, state.bias, state.airQuality)
                    WeatherDomain.Week -> WeekPage(forecast, now)
                    WeatherDomain.Month -> MonthPage(forecast, now)
                }
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

/** Time until the wall clock rolls over to the next minute. */
private fun millisUntilNextMinute(now: Instant): Long =
    Duration.between(now, now.plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MINUTES))
        .toMillis()
        .coerceAtLeast(1L)

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

/**
 * Puts an open panel away when a press lands anywhere it does not own.
 *
 * Only unconsumed presses count, and that is the whole mechanism: the card and
 * the marks beside the dial take their own taps, so a press that is still
 * unclaimed by the time it reaches here is by definition a press on something
 * else. That covers the chart, the switcher, the header and bare background
 * without any of them having to know a panel exists.
 *
 * It watches the *final* pass, after children have taken what is theirs, which
 * is the whole trick. Watching the initial pass sees every press including the
 * ones landing on the card and the marks, so tapping a mark would dismiss the
 * card and then immediately reopen it - the marks would never close. By the
 * final pass a press on anything that handles presses is already consumed.
 *
 * It never consumes anything itself: dismissing must not also swallow the tap a
 * scroll or a button was about to receive.
 *
 * Inert while nothing is open, so the common case adds no gesture handling at all.
 */
private fun Modifier.dismissOnOutsideTap(active: Boolean, onDismiss: () -> Unit): Modifier =
    if (!active) {
        this
    } else {
        pointerInput(Unit) {
            awaitEachGesture {
                val press = awaitPointerEvent(PointerEventPass.Final)
                if (press.changes.any { it.pressed && !it.isConsumed }) onDismiss()
            }
        }
    }
