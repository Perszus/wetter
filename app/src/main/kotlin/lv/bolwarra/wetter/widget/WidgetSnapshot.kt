package lv.bolwarra.wetter.widget

import android.content.Context
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.withTimeoutOrNull
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.domain.FaceReading
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.hazard.Hazard
import lv.bolwarra.wetter.domain.hazard.Hazards
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.verification.withLocalCorrection

/**
 * Everything the widgets draw from, read once from disk and never from the
 * network. Prepared the way the app's screen is: the forecast carries the
 * learned local correction and the hazards are the notification's scan, so the
 * home screen and the app agree.
 */
internal class WidgetSnapshot(
    val forecast: WeatherForecast,
    val now: Instant,
    /** The held radar projection, re-anchored to now; empty where none is held. */
    val timeline: List<FusedPrecipitation>,
    /** Whatever in the next day could hurt somebody, worst first. */
    val hazards: List<Hazard>,
    val preferences: Preferences,
) {
    /** What a face says this minute: the reading, the word and the glass. */
    val face: FaceReading by lazy { FaceReading.of(forecast, timeline, hazards, now) }

    companion object {

        /** Null before the first forecast has been fetched. */
        suspend fun read(context: Context, now: Instant = Instant.now()): WidgetSnapshot? {
            val container =
                (context.applicationContext as? WetterApplication)?.container ?: return null
            val location = container.selectedLocation.current()
            val raw = container.repository.cached(location) ?: return null

            // Each read is bounded; past the bound the widget draws without it.
            val bias = held { container.verification.learnedBias(location) }
            val forecast = raw.withLocalCorrection(bias)
            // The held projection; a fresh one would go to the network.
            val timeline = held { container.nowcasts.timeline(forecast, now, STEP, STEPS) }
                .orEmpty()
            val climate = held { container.climatology.normals(location) }
            // Air quality is a network read, so it is left out, as in the notification.
            val hazards = Hazards.scan(forecast, air = null, now = now, climate = climate)
            val preferences = held { container.preferences.current() } ?: Preferences()

            return WidgetSnapshot(forecast, now, timeline, hazards, preferences)
        }

        private suspend fun <T> held(read: suspend () -> T): T? =
            withTimeoutOrNull(HELD_MS) { runCatching { read() }.getOrNull() }

        /** Four hours at the timeline's ten-minute spacing. */
        val STEP: Duration = Duration.ofMinutes(10)
        const val STEPS = 24
        val WINDOW: Duration = STEP.multipliedBy(STEPS.toLong())

        private const val HELD_MS = 2_000L
    }
}
