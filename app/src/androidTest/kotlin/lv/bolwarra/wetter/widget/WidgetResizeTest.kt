// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import lv.bolwarra.wetter.R
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resizing a placed widget redraws it at its new size, through the real widget
 * framework: the widgets are bound to a host in this process, resized the way a
 * launcher resizes them (new size options), and the picture the host receives is
 * checked against the size asked for.
 *
 * Needs permission to bind widgets and a forecast in the app's cache:
 *
 *     adb shell appwidget grantbind --package lv.bolwarra.wetter.debug
 *
 * and the app opened once. Without either the test is skipped, not failed.
 */
@RunWith(AndroidJUnit4::class)
class WidgetResizeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val manager = AppWidgetManager.getInstance(context)
    private lateinit var host: AppWidgetHost

    @Before
    fun listen() {
        assumeTrue("no forecast cached", runBlocking { WidgetSnapshot.read(context) } != null)
        instrumentation.runOnMainSync {
            host = AppWidgetHost(context, HOST_ID)
            host.startListening()
        }
    }

    @After
    fun release() {
        instrumentation.runOnMainSync {
            host.stopListening()
            host.deleteHost()
        }
    }

    @Test
    fun rainRedrawsAtEverySize() = resize(
        RainWidget::class.java,
        listOf(
            250 to 70,
            320 to 100,
            390 to 180,
        ),
    )

    @Test
    fun nowRedrawsAtEverySize() = resize(
        NowWidget::class.java,
        listOf(
            70 to 70,
            150 to 70,
            160 to 160,
            280 to 280,
        ),
    )

    @Test
    fun hoursRedrawAtEverySize() = resize(
        HoursWidget::class.java,
        listOf(
            250 to 56,
            360 to 56,
            360 to 160,
            360 to 280,
        ),
    )

    @Test
    fun weekRedrawsAtEverySize() = resize(
        WeekWidget::class.java,
        listOf(
            250 to 56,
            360 to 56,
            360 to 160,
            360 to 230,
        ),
    )

    @Test
    fun overviewRedrawsAtEverySize() =
        resize(OverviewWidget::class.java, listOf(300 to 180, 340 to 300, 380 to 420))

    private fun resize(provider: Class<out PictureWidget>, sizes: List<Pair<Int, Int>>) {
        val id = host.allocateAppWidgetId()
        try {
            assumeTrue(
                "binding widgets is not granted",
                manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider)),
            )
            lateinit var view: AppWidgetHostView
            instrumentation.runOnMainSync {
                view = host.createView(context, id, manager.getAppWidgetInfo(id))
            }
            val density = context.resources.displayMetrics.density
            sizes.forEach { (widthDp, heightDp) ->
                manager.updateAppWidgetOptions(
                    id,
                    Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                    },
                )
                val (width, height) = awaitPicture(view, widthDp * density, heightDp * density)
                // The picture keeps the widget's shape at the screen's own density:
                // sharp, not a smaller picture scaled up by the launcher.
                assertTrue(
                    "${provider.simpleName} ${widthDp}x$heightDp drawn at ${width}x$height",
                    abs(width - widthDp * density) <= 1f && abs(height - heightDp * density) <= 1f,
                )
            }
        } finally {
            host.deleteAppWidgetId(id)
        }
    }

    /**
     * The size of the picture the host shows, once it is [width] by [height] -
     * or the last size seen when the deadline passes, for the failure message.
     */
    private fun awaitPicture(view: AppWidgetHostView, width: Float, height: Float): Pair<Int, Int> {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var seen = 0 to 0
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val bitmap = (
                    view.findViewById<ImageView>(
                        R.id.widget_canvas,
                    )?.drawable as? BitmapDrawable
                    )?.bitmap
                if (bitmap != null) seen = bitmap.width to bitmap.height
            }
            if (abs(seen.first - width) <= 1f && abs(seen.second - height) <= 1f) return seen
            SystemClock.sleep(POLL_MS)
        }
        return seen
    }

    private companion object {
        const val HOST_ID = 4_711
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
