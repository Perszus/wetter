package lv.bolwarra.wetter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lv.bolwarra.wetter.MainActivity
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.domain.conditionsAt
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.theme.Atmosphere
import lv.bolwarra.wetter.ui.theme.WetterColors
import lv.bolwarra.wetter.ui.theme.darkPlate
import lv.bolwarra.wetter.ui.theme.lightPlate

/**
 * The home-screen widget: the next four hours of rain, and nothing else.
 *
 * What is deliberately absent is most of it. There is no heading saying "Rain" -
 * a blue curve climbing out of a flat line is not something anybody needs told
 * the name of - and no millimetre figure, because the number was never the
 * question. What survives is the shape and the level, which is what a glance is
 * for (docs/design-principles.md, rule 8).
 *
 * That leaves the whole plate for the chart, which is the point. The curve gets
 * roughly two thirds of the height instead of competing with a label row, and
 * the two band lines behind it carry the vertical axis without a single word or
 * number on it.
 *
 * ### Why it draws itself in our process
 *
 * [RemoteViews] is a description of views, inflated in the launcher. No code of
 * ours runs there, so the curve arrives as a finished bitmap from [RainStrip].
 * Only the hour labels are real views, so they keep the reader's font scale.
 */
class RainWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) =
        render(context, manager, appWidgetIds)

    /**
     * Resizing changes the picture, because the picture is sized in pixels.
     *
     * Without this the bitmap keeps whatever dimensions it had when it was first
     * placed, and the launcher stretches it - which shows up as a curve that
     * gets steadily softer every time somebody drags a corner.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = render(context, manager, intArrayOf(appWidgetId))

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return

        // A BroadcastReceiver is considered non-responsive after ten seconds, so
        // the work goes to a coroutine and the receiver is kept alive explicitly
        // until it finishes.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                paint(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    /** Read once, drawn for every widget on the screen. */
    internal suspend fun paint(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val frame = frameFor(context)
        ids.forEach { id -> draw(context, manager, id, frame) }
    }

    /**
     * Everything the picture needs, read once for all the widgets on screen.
     *
     * Null when there is no forecast yet - a fresh install before the first
     * refresh. The widget then keeps whatever it last drew rather than blanking,
     * because a widget that empties itself looks broken in a way that a slightly
     * old curve does not.
     */
    private suspend fun frameFor(context: Context): Frame? {
        val container = (context.applicationContext as? WetterApplication)?.container ?: return null
        val location = container.selectedLocation.current()
        val forecast = container.repository.cached(location) ?: return null
        val now = Instant.now()

        // The fused timeline is radar-led in the first hour, which is exactly
        // the part of a four-hour window worth being right about. It can reach
        // the network, so it is given a budget well inside the receiver's, and
        // the model's own hours stand in if it overruns.
        val fused = withTimeoutOrNull(FUSION_BUDGET_MS) {
            runCatching {
                container.nowcasts.timeline(forecast, now, STEP, STEPS)
            }.getOrNull()
        }

        val rates = fused
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.millimetresPerHour }
            ?: modelRates(forecast, now)

        val conditions = forecast.conditionsAt(now)
        return Frame(
            rates = rates,
            windSpeed = conditions.windSpeed,
            windFrom = conditions.windDirection,
            zone = forecast.location.zone,
            now = now,
        )
    }

    /**
     * The model's own hours, when radar could not be reached in time.
     *
     * Five points across four hours rather than twenty-five, so the curve is
     * blockier - which is honest, because that is genuinely all the model has.
     */
    private fun modelRates(forecast: WeatherForecast, now: Instant): List<Double> {
        val end = now.plus(WINDOW)
        return forecast.hourly
            .filter {
                !it.timestamp.isBefore(now.minus(Duration.ofHours(1))) &&
                    it.timestamp.isBefore(end)
            }
            .sortedBy { it.timestamp }
            .map { it.precipitation ?: 0.0 }
            .ifEmpty { listOf(0.0, 0.0) }
    }

    private fun draw(context: Context, manager: AppWidgetManager, id: Int, frame: Frame?) {
        val views = RemoteViews(context.packageName, R.layout.widget_rain)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))

        val colors = paletteFor(context)
        val size = sizeOf(manager, id)

        if (frame != null) {
            views.setImageViewBitmap(
                R.id.widget_canvas,
                RainStrip.render(
                    widthDp = size.first,
                    heightDp = size.second,
                    density = context.resources.displayMetrics.density,
                    cornerRadiusDp = cornerRadiusDp(context),
                    colors = colors,
                    rates = frame.rates,
                    windSpeed = frame.windSpeed,
                    windFrom = frame.windFrom,
                    hourOffset = hourOffset(frame),
                    hourLabels = hourLabels(context, frame),
                ),
            )
        }
        manager.updateAppWidget(id, views)
    }

    /**
     * How far along the window the first whole hour falls, as a fraction of it.
     *
     * The window opens at *now*, so the hour boundaries inside it are not at
     * quarters of the width - they are a quarter apart but offset by however far
     * away the next one is. The labels used to be drawn at fixed quarters, which
     * put midnight wherever 23:47 plus an hour happened to land, and read as
     * text that had slipped sideways.
     */
    private fun hourOffset(frame: Frame): Float {
        val next = frame.now.atZone(frame.zone)
            .truncatedTo(ChronoUnit.HOURS)
            .plusHours(1)
            .toInstant()
        val into = Duration.between(frame.now, next).toMillis().toFloat()
        return into / WINDOW.toMillis().toFloat()
    }

    /**
     * The clock times of those boundaries, in the reader's own 12- or 24-hour
     * preference. Whether each one has room to be drawn is decided where it is
     * drawn, against the measured width of the text.
     */
    private fun hourLabels(context: Context, frame: Frame): List<String> {
        val pattern = if (DateFormat.is24HourFormat(context)) HOUR_24 else HOUR_12
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            .withZone(frame.zone)
        val first = frame.now.atZone(frame.zone)
            .truncatedTo(ChronoUnit.HOURS)
            .plusHours(1)
            .toInstant()
        return (0 until HOURS_IN_WINDOW).map {
            formatter.format(first.plus(Duration.ofHours(it.toLong())))
        }
    }

    /**
     * The widget's own size in dp, which is not knowable from the layout.
     *
     * The launcher reports a range because the same widget occupies different
     * space in portrait and landscape. The narrow width and the tall height are
     * the portrait pair, which is the one it is nearly always seen in.
     */
    private fun sizeOf(manager: AppWidgetManager, id: Int): Pair<Float, Float> {
        val options = manager.getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return Pair(
            if (width > 0) width.toFloat() else DEFAULT_WIDTH_DP,
            if (height > 0) height.toFloat() else DEFAULT_HEIGHT_DP,
        )
    }

    /**
     * The launcher clips the widget to its own radius on Android 12 and later,
     * so the plate is drawn to match rather than guessed at. Below that nobody
     * clips anything and the corner is ours to choose.
     */
    private fun cornerRadiusDp(context: Context): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.resources.getDimension(android.R.dimen.system_app_widget_background_radius) /
                context.resources.displayMetrics.density
        } else {
            FALLBACK_RADIUS_DP
        }

    /**
     * The same palette as the app, under the same sky.
     *
     * [Atmosphere.Neutral] rather than the sky of the moment: on the home screen
     * the plate sits against a wallpaper the app knows nothing about, and a
     * ground that shifts with the weather would read as the widget failing to
     * settle rather than as the weather changing.
     */
    private fun paletteFor(context: Context): WetterColors {
        val night = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (night) darkPlate(Atmosphere.Neutral) else lightPlate(Atmosphere.Neutral)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private data class Frame(
        val rates: List<Double>,
        val windSpeed: Double?,
        val windFrom: Int?,
        val zone: ZoneId,
        val now: Instant,
    )

    companion object {

        /**
         * Redraws every placed widget.
         *
         * Called after a refresh writes a new forecast, which is the only thing
         * that changes what the widget should say. There is no periodic update
         * of its own - `updatePeriodMillis` is zero - because a schedule that
         * woke the device to redraw the same curve would be spending battery to
         * change nothing.
         *
         * It draws directly rather than asking the system to. The first version
         * broadcast ACTION_APPWIDGET_UPDATE at itself, which looks like the
         * obvious way to do this and silently does nothing: that action is
         * protected, so the broadcast is refused with a permission denial the
         * sender never sees. Nothing in the app can send it - only the system
         * can - and an app that wants its widget redrawn simply hands
         * AppWidgetManager the new views.
         */
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, RainWidget::class.java))
            if (ids.isEmpty()) return

            // An instance purely to reach the drawing, which is a member because
            // onUpdate needs it too. A provider is an ordinary BroadcastReceiver
            // and nothing here touches its receiver half.
            RainWidget().paint(context, manager, ids)
        }

        /** Four hours, as asked for, at the timeline's own ten-minute spacing. */
        private val WINDOW: Duration = Duration.ofHours(4)
        private val STEP: Duration = Duration.ofMinutes(10)
        private const val STEPS = 24

        /** Comfortably inside the receiver's ten seconds. */
        private const val FUSION_BUDGET_MS = 6_000L

        private const val HOURS_IN_WINDOW = 4

        private const val HOUR_24 = "HH"
        private const val HOUR_12 = "h a"

        /** Three cells by one, before anybody resizes it. */
        private const val DEFAULT_WIDTH_DP = 240f
        private const val DEFAULT_HEIGHT_DP = 70f

        private const val FALLBACK_RADIUS_DP = 16f
    }
}
