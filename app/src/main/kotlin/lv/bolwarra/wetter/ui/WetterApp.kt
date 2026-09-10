package lv.bolwarra.wetter.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import lv.bolwarra.wetter.ui.navigation.Destination
import lv.bolwarra.wetter.ui.screens.LocationsRoute
import lv.bolwarra.wetter.ui.screens.SettingsOverlay
import lv.bolwarra.wetter.ui.screens.WeatherRoute
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The whole navigation graph.
 *
 * The weather screen is the app and locations is a place you visit and come back
 * from. There is no bottom bar, because a permanent tab bar would spend a fixed
 * strip of every screen advertising destinations that are opened a few times a
 * month.
 *
 * Settings is not a destination at all. It opens over whatever is showing and
 * shuts again, which is what it is: a panel you go into to change one thing. A
 * route would have put the forecast behind a back press for that, and left the
 * app in a state where the thing it exists to show is not on screen.
 *
 * Insets are applied once here rather than per screen, so no screen can forget
 * them and none has to repeat the modifier.
 */
@Composable
fun WetterApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = WetterTheme.colors.surface,
    ) {
        NavHost(
            navController = navController,
            startDestination = Destination.Weather.route,
            modifier = Modifier.safeDrawingPadding(),
        ) {
            composable(Destination.Weather.route) {
                WeatherRoute(
                    onOpenLocations = { navController.navigate(Destination.Locations.route) },
                    onOpenSettings = { settingsOpen = true },
                )
            }
            composable(Destination.Locations.route) {
                LocationsRoute(onBack = { navController.popBackStack() })
            }
        }

        if (settingsOpen) {
            SettingsOverlay(onDismiss = { settingsOpen = false })
        }
    }
}
