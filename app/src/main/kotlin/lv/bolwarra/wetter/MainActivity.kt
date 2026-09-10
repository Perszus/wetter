package lv.bolwarra.wetter

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.notify.HazardNotifier
import lv.bolwarra.wetter.ui.WetterApp
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The only activity.
 *
 * Edge to edge is on deliberately: the forecast reads as one continuous column
 * running the full height of the display, and the system bars sit over the app's
 * own ground rather than over a strip of a different colour. Insets are consumed
 * inside WetterApp.
 */
class MainActivity : ComponentActivity() {

    private val container: WetterContainer
        get() = (application as WetterApplication).container

    /**
     * Asked for once, over a screen that is already showing the weather.
     *
     * There is no callback because there is nothing to do with the answer. A
     * phone that says no simply never gets a warning; the switch in Settings
     * stays on, meaning "yes, warn me", and starts working the day the
     * permission is granted in the system settings.
     */
    private val askToNotify =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Read here rather than inside the theme, so there is exactly one
            // place the choice enters the app and everything below - including
            // the second WetterTheme the weather screen applies for the sky -
            // inherits it.
            val units by container.preferences.preferences
                .collectAsStateWithLifecycle(initialValue = Preferences())

            // The forecast is drawn from cache on the first frame, so by the
            // time this runs there is a screen with actual weather on it behind
            // the dialog - which is the difference between being asked about
            // storm warnings and being asked about notifications. Android stops
            // showing it after two refusals, so this cannot become a nag.
            LaunchedEffect(units.warnings) {
                if (units.warnings) requestNotificationsIfNeeded()
            }

            WetterTheme(units = units) {
                WetterApp()
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (HazardNotifier(this).isAllowed()) return
        askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
