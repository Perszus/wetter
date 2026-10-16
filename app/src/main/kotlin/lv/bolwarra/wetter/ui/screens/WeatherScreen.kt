// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
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
import lv.bolwarra.wetter.domain.conditionsAt
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.settings.ThemeChoice
import lv.bolwarra.wetter.notify.HazardNotifier
import lv.bolwarra.wetter.ui.WetterViewModels
import lv.bolwarra.wetter.ui.components.DomainSwitcher
import lv.bolwarra.wetter.ui.components.EmptyState
import lv.bolwarra.wetter.ui.components.PlateMark
import lv.bolwarra.wetter.ui.components.WeatherHeader
import lv.bolwarra.wetter.ui.components.WeatherPlate
import lv.bolwarra.wetter.ui.map.RadarMapRoute
import lv.bolwarra.wetter.ui.preview.SampleWeather
import lv.bolwarra.wetter.ui.theme.Atmosphere
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Connects the weather screen to its view model, so [WeatherScreen] itself takes
 * only a state and callbacks and can be previewed without one.
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

    // While somebody is looking: every return asks at once, and the model is
    // re-checked every minute against its freshness window. What is held stays on
    // screen throughout; this only swaps it for newer. Radar keeps its own clock in
    // the view model.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshOnReturn()
            while (true) {
                delay(KEEP_FRESH_TICK_MS)
                viewModel.refreshIfStale()
            }
        }
    }

    // The page survives a rotation but not a relaunch: it is not a preference.
    var domain by rememberSaveable { mutableStateOf(WeatherDomain.Today) }

    // Over the forecast rather than a destination: it is opened to look at one thing,
    // and back returns to the chart it was opened from.
    var mapOpen by rememberSaveable { mutableStateOf(false) }

    // The forecast behind an open map, softened. Blur needs Android 12; below that
    // the scrim alone does the job.
    val blur by animateDpAsState(if (mapOpen) BACKDROP_BLUR else 0.dp, label = "backdrop")

    Box(modifier) {
        WeatherScreen(
            modifier = Modifier.blur(blur),
            state = state,
            domain = domain,
            onSelectDomain = { domain = it },
            onOpenLocations = onOpenLocations,
            onOpenSettings = onOpenSettings,
            onRetry = viewModel::refresh,
            onOpenMap = { mapOpen = true },
        )
        AnimatedVisibility(visible = mapOpen, enter = fadeIn(), exit = fadeOut()) {
            RadarMapRoute(onClose = { mapOpen = false })
        }
    }
}

/**
 * The home screen: where you are, what it is like now, which horizon you are
 * looking at, then the page for that horizon. The first two sit above the switcher
 * because they are true on every page, so switching never moves them.
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
    onOpenMap: () -> Unit = {},
) {
    val spacing = WetterTheme.spacing

    AskAboutWarningsOnce(hasForecast = state.forecast != null)

    // The whole screen is read against this instant, so it ticks with the wall
    // clock's minute. Tied to RESUMED, so a process resumed mid-delay does not first
    // show the time it was put down.
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

    // Which mark beside the dial is explaining itself. Held here because a tap
    // anywhere else on the screen dismisses it.
    var explaining by remember { mutableStateOf<PlateMark?>(null) }

    // The sky this screen is read under. Only this screen: places and settings are
    // not showing weather.
    val current = state.forecast?.conditionsAt(now)
    val sky = remember(current?.condition, current?.precipitation) {
        current?.let {
            Atmosphere.of(
                condition = it.condition,
                intensity = PrecipitationIntensity.ofRate(it.precipitation),
                isDay = it.isDay,
            )
        } ?: Atmosphere.Neutral
    }

    WetterTheme(sky = sky) {
        WeatherScreenBody(
            state = state,
            domain = domain,
            onSelectDomain = onSelectDomain,
            onOpenLocations = onOpenLocations,
            onOpenSettings = onOpenSettings,
            onRetry = onRetry,
            onOpenMap = onOpenMap,
            now = now,
            explaining = explaining,
            onExplain = { explaining = it },
            modifier = modifier,
        )
    }
}

/**
 * Ask about severe-weather notifications once, and only over a screen already
 * showing a forecast, never over an empty one.
 *
 * Asked at most once per install: `shouldShowRequestPermissionRationale` is true
 * when the permission has been refused but not for good, which is when
 * this stays quiet. A permanent refusal shows nothing anyway. The Settings switch
 * and the system's app settings remain for a change of mind.
 */
@Composable
private fun AskAboutWarningsOnce(hasForecast: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val wanted = WetterTheme.units.warnings
    var askedThisScreen by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(hasForecast, wanted, askedThisScreen) {
        if (!hasForecast || !wanted || askedThisScreen) return@LaunchedEffect
        if (HazardNotifier(context).isAllowed()) return@LaunchedEffect

        val activity = context.findActivity() ?: return@LaunchedEffect
        val refusedBefore = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        if (refusedBefore) return@LaunchedEffect

        askedThisScreen = true
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** The activity behind a composable's context, through however many wrappers. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun WeatherScreenBody(
    state: WeatherUiState,
    domain: WeatherDomain,
    onSelectDomain: (WeatherDomain) -> Unit,
    onOpenLocations: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onOpenMap: () -> Unit,
    now: Instant,
    explaining: PlateMark?,
    onExplain: (PlateMark?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            // The screen paints its own ground: the sky has moved it since the shell was
            // themed.
            .background(WetterTheme.colors.surface)
            .padding(horizontal = spacing.screen)
            .dismissOnOutsideTap(active = explaining != null) { onExplain(null) },
    ) {
        WeatherHeader(
            locationName = state.location?.name,
            onOpenLocations = onOpenLocations,
            onOpenSettings = onOpenSettings,
        )

        val forecast = state.forecast
        if (forecast == null && !state.loaded) {
            // The store has not answered yet; anything said here would be a guess.
            return@Column
        }
        if (forecast == null) {
            // No forecast and no error means nowhere is selected, so the action is to choose
            // one; with an error, it is to retry.
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
                hazards = state.hazards,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                explaining = explaining,
                onExplain = onExplain,
                hazyAir = state.airQuality?.band?.isNotable == true,
            )
            Spacer(Modifier.height(spacing.xl))
            DomainSwitcher(selected = domain, onSelect = onSelectDomain)
            Spacer(Modifier.height(spacing.m))

            AnimatedContent(
                targetState = domain,
                transitionSpec = {
                    // The pages sit in a row, so a page to the right arrives from the right.
                    val forward = targetState.ordinal > initialState.ordinal
                    val enter = slideInHorizontally { width ->
                        if (forward) width else -width
                    } + fadeIn()
                    val exit = slideOutHorizontally { width ->
                        if (forward) -width else width
                    } + fadeOut()
                    // Height is not animated: watching the page below stretch is worse than having
                    // it settle at once.
                    enter togetherWith exit using SizeTransform(clip = false)
                },
                label = "domain",
            ) { page ->
                when (page) {
                    WeatherDomain.Today ->
                        TodayPage(
                            forecast,
                            now,
                            state.timeline,
                            state.airQuality,
                            state.isRefreshing,
                            onOpenMap = onOpenMap,
                        )
                    WeatherDomain.Week -> WeekPage(forecast, now)
                    WeatherDomain.Month -> MonthPage(forecast, now, state.climatology)
                }
            }

            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/**
 * What to say when there is nothing to show. Being offline and having no place
 * yet are the reader's business; anything else is Wetter failing, and gets one
 * sentence. Which supplier failed is not the reader's problem
 * (docs/design-principles.md, rule 8).
 */
private fun emptyTitleFor(error: WeatherError?) = when (error) {
    null -> R.string.state_no_forecast
    is WeatherError.Offline -> R.string.state_offline
    else -> R.string.state_could_not_fetch
}

private fun emptyDetailFor(error: WeatherError?) = when (error) {
    null -> R.string.state_no_forecast_detail
    is WeatherError.Offline -> R.string.state_offline_detail
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
    WetterTheme(units = Preferences(theme = ThemeChoice.PURE_WHITE)) {
        WeatherScreen(sampleState, WeatherDomain.Today, {}, {}, {}, {})
    }
}

@Preview(name = "Today dark", showBackground = true, heightDp = 900)
@Composable
private fun TodayDarkPreview() {
    WetterTheme(units = Preferences(theme = ThemeChoice.PURE_BLACK)) {
        WeatherScreen(sampleState, WeatherDomain.Today, {}, {}, {}, {})
    }
}

@Preview(name = "Week light", showBackground = true, heightDp = 900)
@Composable
private fun WeekLightPreview() {
    WetterTheme(units = Preferences(theme = ThemeChoice.PURE_WHITE)) {
        WeatherScreen(sampleState, WeatherDomain.Week, {}, {}, {}, {})
    }
}

@Preview(name = "Week dark", showBackground = true, heightDp = 900)
@Composable
private fun WeekDarkPreview() {
    WetterTheme(units = Preferences(theme = ThemeChoice.PURE_BLACK)) {
        WeatherScreen(sampleState, WeatherDomain.Week, {}, {}, {}, {})
    }
}

@Preview(name = "Offline cached", showBackground = true, heightDp = 900)
@Composable
private fun OfflinePreview() {
    WetterTheme(units = Preferences(theme = ThemeChoice.PURE_BLACK)) {
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
    WetterTheme(units = Preferences(theme = ThemeChoice.PURE_WHITE)) {
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
 * Watches the final pass for unconsumed presses: the card and the dial's marks
 * consume their own, so whatever is left landed somewhere else. Never consumes
 * anything itself, so the tap still reaches a scroll or a button. Inert while
 * nothing is open.
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

/** How far the forecast behind an open map is softened. */
private val BACKDROP_BLUR = 12.dp

/** How often an open screen re-checks the model's freshness. */
private const val KEEP_FRESH_TICK_MS = 60_000L
