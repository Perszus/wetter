package lv.bolwarra.wetter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.MainActivity
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.ui.theme.Atmosphere
import lv.bolwarra.wetter.ui.theme.WetterColors
import lv.bolwarra.wetter.ui.theme.darkPlate
import lv.bolwarra.wetter.ui.theme.lightPlate
import lv.bolwarra.wetter.work.Heartbeat

/**
 * A home-screen widget drawn as one bitmap.
 *
 * RemoteViews cannot draw arbitrary shapes, so each widget renders its picture
 * here and hands the launcher the result. Subclasses supply [draw]; this class
 * handles the snapshot, the plate, sizing and the update itself.
 */
internal abstract class PictureWidget : AppWidgetProvider() {

    /** Draws this widget's contents onto a [sheet] whose plate is already down. */
    abstract fun draw(sheet: Sheet, snapshot: WidgetSnapshot)

    /** The spoken description of the picture. */
    abstract fun describe(context: Context, snapshot: WidgetSnapshot): String

    /** Size in dp before the launcher reports one. */
    abstract val defaultWidthDp: Float
    abstract val defaultHeightDp: Float

    /** A clock or time-zone change makes every time label wrong; redraw. */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action !in CLOCK_CHANGED) return
        later { Widgets.refresh(context) }
    }

    /**
     * Placement, reboot and the system's half-hourly backstop. Draws from disk
     * immediately, and asks the heartbeat to fetch if the data has aged out.
     */
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        later {
            if (appWidgetIds.isNotEmpty()) {
                paint(
                    context,
                    manager,
                    appWidgetIds,
                    WidgetSnapshot.read(context),
                )
            }
            val container = (context.applicationContext as? WetterApplication)?.container
            val stale = container?.repository?.needsRefresh(
                container.repository.cached(container.selectedLocation.current()),
            ) ?: false
            if (stale) Heartbeat.soon(context) else Heartbeat.ensure(context)
        }
    }

    /** The picture is sized in pixels, so a resize needs a new one. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = later { paint(context, manager, intArrayOf(appWidgetId), WidgetSnapshot.read(context)) }

    /**
     * Draws [ids] from [snapshot]. Without a snapshot (no forecast yet) each
     * widget keeps its last picture rather than going blank.
     */
    fun paint(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        snapshot: WidgetSnapshot?,
    ) {
        if (snapshot == null) return
        val budgets = WidgetPlate.budgets(context.resources.displayMetrics)
        ids.forEach { id ->
            budgets.firstOrNull { push(context, manager, id, snapshot, it) }
        }
    }

    private fun push(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        snapshot: WidgetSnapshot,
        maxBytes: Int,
    ): Boolean {
        val (widthDp, heightDp) = sizeOf(manager, id)
        val views = RemoteViews(context.packageName, R.layout.widget_picture)
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
        views.setImageViewBitmap(
            R.id.widget_canvas,
            picture(context, widthDp, heightDp, snapshot, maxBytes),
        )
        views.setContentDescription(R.id.widget_canvas, describe(context, snapshot))

        return try {
            manager.updateAppWidget(id, views)
            true
        } catch (refused: RuntimeException) {
            // An oversized bitmap or Binder transaction both surface here.
            Log.w(TAG, "widget $id refused a $maxBytes byte update", refused)
            false
        }
    }

    /** The full picture at one size. Separate from [push] so tests can draw it. */
    fun picture(
        context: Context,
        widthDp: Float,
        heightDp: Float,
        snapshot: WidgetSnapshot,
        maxBytes: Int = WidgetPlate.budgets(context.resources.displayMetrics).first(),
        colors: WetterColors = paletteFor(context),
        placed: MutableList<WidgetInk.Placed>? = null,
    ): Bitmap {
        val scale = WidgetPlate.renderScale(
            widthDp,
            heightDp,
            context.resources.displayMetrics.density,
            maxBytes,
        )
        val width = max(1, (widthDp * scale).toInt())
        val height = max(1, (heightDp * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = cornerRadiusDp(context) * scale
        val current = snapshot.face.current
        WidgetPlate.drawPlate(canvas, bounds, radius, colors)
        WidgetPlate.drawRim(
            canvas,
            bounds,
            radius,
            scale,
            colors,
            current.windSpeed,
            current.windDirection,
        )

        val sheet =
            Sheet(
                context,
                WidgetInk(canvas, scale, colors, placed),
                bounds,
                widthDp,
                heightDp,
                radius,
            )
        draw(sheet, snapshot)
        return bitmap
    }

    /** The portrait size (narrow width, tall height), which is how widgets are mostly seen. */
    private fun sizeOf(manager: AppWidgetManager, id: Int): Pair<Float, Float> {
        val options = manager.getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return Pair(
            if (width > 0) width.toFloat() else defaultWidthDp,
            if (height > 0) height.toFloat() else defaultHeightDp,
        )
    }

    /** Runs [work] off the main thread, keeping the receiver alive until it finishes. */
    private fun later(work: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                work()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        private const val TAG = "Widget"

        private val CLOCK_CHANGED = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )

        private const val FALLBACK_RADIUS_DP = 16f

        /** Matches the launcher's own clip radius on Android 12+. */
        fun cornerRadiusDp(context: Context): Float =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.resources.getDimension(
                    android.R.dimen.system_app_widget_background_radius,
                ) /
                    context.resources.displayMetrics.density
            } else {
                FALLBACK_RADIUS_DP
            }

        /**
         * The app's palette under a neutral sky: a widget sits on an unknown
         * wallpaper, so its ground stays fixed and the weather goes on the glass.
         */
        fun paletteFor(context: Context): WetterColors {
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
    }
}

/** A widget's picture with the plate already drawn; [bounds] is the whole plate in pixels. */
internal class Sheet(
    val context: Context,
    val ink: WidgetInk,
    val bounds: RectF,
    val widthDp: Float,
    val heightDp: Float,
    /** The plate's corner radius, in pixels. */
    val radius: Float,
) {
    val scale: Float get() = ink.scale
    val colors: WetterColors get() = ink.colors
    fun px(dp: Float): Float = ink.px(dp)

    /**
     * The plate inset by [padXDp] and [padYDp], with the sides pushed in as far
     * as the corner curve needs to keep the rectangle's corners on the plate.
     */
    fun inner(padXDp: Float, padYDp: Float = padXDp): RectF {
        val r = cornerRadius()
        val y = px(padYDp)
        val curve = if (y >= r) 0f else r - sqrt(r * r - (r - y) * (r - y))
        val x = max(px(padXDp), curve)
        return RectF(bounds).apply { inset(x, y) }
    }

    /** The radius actually drawn: a corner cannot be rounder than half the plate. */
    fun cornerRadius(): Float = minOf(radius, bounds.width() / 2f, bounds.height() / 2f)
}
