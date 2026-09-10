package lv.bolwarra.wetter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lv.bolwarra.wetter.domain.settings.Preferences
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

            WetterTheme(units = units) {
                WetterApp()
            }
        }
    }
}
