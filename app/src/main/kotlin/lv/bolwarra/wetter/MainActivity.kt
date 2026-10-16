package lv.bolwarra.wetter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.ui.WetterApp
import lv.bolwarra.wetter.ui.theme.WetterTheme
import lv.bolwarra.wetter.widget.Widgets

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

    /**
     * Leaving the app is the moment the home screen comes back, so the widget
     * is redrawn from what the app has just been showing.
     *
     * Nothing is fetched for this: the app has spent the visit collecting the
     * newest sweep and forecast, and the widget reads the same store. Without
     * it the widget kept whatever the last background run drew - up to a
     * quarter of an hour behind a screen the reader had just looked at.
     */
    override fun onStop() {
        super.onStop()
        val app = application as WetterApplication
        app.container.applicationScope.launch { runCatching { Widgets.refresh(app) } }
    }
}
