package lv.bolwarra.wetter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WetterTheme {
                WetterApp()
            }
        }
    }
}
