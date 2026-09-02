package lv.bolwarra.wetter.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import lv.bolwarra.wetter.ui.navigation.Destination
import lv.bolwarra.wetter.ui.screens.LocationsRoute
import lv.bolwarra.wetter.ui.screens.SettingsRoute
import lv.bolwarra.wetter.ui.screens.WeatherRoute
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The whole navigation graph.
 *
 * The weather screen is the app; locations and settings are places you visit and
 * come back from. There is no bottom bar, because a permanent three-tab bar
 * would spend a fixed strip of every screen advertising two destinations that
 * are opened a few times a month.
 *
 * Insets are applied once here rather than per screen, so no screen can forget
 * them and none has to repeat the modifier.
 */
@Composable
fun WetterApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

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
                    onOpenSettings = { navController.navigate(Destination.Settings.route) },
                )
            }
            composable(Destination.Locations.route) {
                LocationsRoute(onBack = { navController.popBackStack() })
            }
            composable(Destination.Settings.route) {
                SettingsRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}
