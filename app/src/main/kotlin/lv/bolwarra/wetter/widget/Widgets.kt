package lv.bolwarra.wetter.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/** Redraws every kind of widget together. */
internal object Widgets {

    /**
     * Redraws every placed widget from disk, all from one snapshot so they agree.
     * Never fetches. Called by the heartbeat before and after each pass, and
     * when the app is left.
     *
     * Drawing directly is deliberate: broadcasting ACTION_APPWIDGET_UPDATE to
     * ourselves is refused, since only the system may send it.
     */
    suspend fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val placed = KINDS.mapNotNull { (kind, make) ->
            val ids = manager.getAppWidgetIds(ComponentName(context, kind))
            if (ids.isEmpty()) null else make() to ids
        }
        if (placed.isEmpty()) return

        val snapshot = WidgetSnapshot.read(context) ?: return
        placed.forEach { (widget, ids) -> widget.paint(context, manager, ids, snapshot) }
    }

    private val KINDS: List<Pair<Class<out PictureWidget>, () -> PictureWidget>> = listOf(
        RainWidget::class.java to ::RainWidget,
        NowWidget::class.java to ::NowWidget,
        HoursWidget::class.java to ::HoursWidget,
        WeekWidget::class.java to ::WeekWidget,
        OverviewWidget::class.java to ::OverviewWidget,
    )
}
