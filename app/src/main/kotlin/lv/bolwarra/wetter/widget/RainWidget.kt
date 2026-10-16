package lv.bolwarra.wetter.widget

import android.content.Context
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.format.NextRain

/**
 * The next four hours of rain, and nothing else: no heading and no figures, just
 * the curve against its two band lines, which is what a glance needs.
 */
internal class RainWidget : PictureWidget() {

    override val defaultWidthDp = 240f
    override val defaultHeightDp = 70f

    override fun draw(sheet: Sheet, snapshot: WidgetSnapshot) {
        val window = RainWindow.of(snapshot)
        RainStrip.drawStrip(
            canvas = sheet.ink.canvas,
            box = sheet.bounds,
            scale = sheet.scale,
            colors = sheet.colors,
            rates = window.rates,
            hourOffset = window.hourOffset,
            hourLabels = window.hourLabels,
        )
    }

    /** The Today page's next-rain sentence, which is what this chart shows. */
    override fun describe(context: Context, snapshot: WidgetSnapshot): String =
        NextRain.of(context.resources, snapshot.forecast, snapshot.timeline, snapshot.now).text
}

/** The next four hours of rain, ready to draw: the rain widget and the overview's strip. */
internal class RainWindow(
    /** Millimetres per hour, evenly spaced across the window, the first being now. */
    val rates: List<Double>,
    /**
     * Where the first whole hour falls, as a fraction of the window. The window
     * opens at now, so the hour marks are a quarter apart but offset.
     */
    val hourOffset: Float,
    /** The hour marks' labels, in 24-hour time like the app's own chart axis. */
    val hourLabels: List<String>,
) {
    companion object {

        fun of(snapshot: WidgetSnapshot): RainWindow {
            val now = snapshot.now
            val zone = snapshot.forecast.location.zone
            val rates = snapshot.timeline
                .takeIf { it.isNotEmpty() }
                ?.map { it.millimetresPerHour }
                ?: modelRates(snapshot.forecast, now)

            val first = now.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val into = Duration.between(now, first.toInstant()).toMillis().toFloat()
            val labels = (0 until HOURS_IN_WINDOW).map {
                val at = first.plusHours(it.toLong())
                "%d:%02d".format(at.hour, at.minute)
            }
            return RainWindow(rates, into / WidgetSnapshot.WINDOW.toMillis().toFloat(), labels)
        }

        /** The model's hourly rates, for when no radar projection is held. */
        private fun modelRates(forecast: WeatherForecast, now: Instant): List<Double> {
            val end = now.plus(WidgetSnapshot.WINDOW)
            return forecast.hourly
                .filter {
                    !it.timestamp.isBefore(now.minus(Duration.ofHours(1))) &&
                        it.timestamp.isBefore(end)
                }
                .sortedBy { it.timestamp }
                .map { it.precipitation ?: 0.0 }
                .ifEmpty { listOf(0.0, 0.0) }
        }

        private const val HOURS_IN_WINDOW = 4
    }
}
