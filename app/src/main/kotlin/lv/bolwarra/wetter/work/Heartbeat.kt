// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lv.bolwarra.wetter.BuildConfig
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.widget.Widgets

/**
 * The app's clock in the background: wake when there is something new to fetch.
 *
 * Timed to the radar, which publishes every ten minutes: [nextWake] aims just past
 * the next sweep, retries briefly if it is late, then falls back to the plain
 * cadence, which is all there is where no radar covers.
 *
 * An allow-while-idle alarm, because periodic work is a floor that Doze stretches
 * to hours. Its delivery carries a few seconds of network even in Doze, which
 * [Refresh] spends on radar first. Exact when allowed ([exactAllowed]), inexact
 * otherwise. The periodic job stays as a net and re-arms the alarm on every run.
 */
internal object Heartbeat {

    /** The plain cadence: the radar's, and the model's freshness window. */
    val CADENCE: Duration = Duration.ofMinutes(10)

    /**
     * How long after a sweep's scan time it is usually published: assembling and
     * posting the composite takes a couple of minutes.
     */
    val PUBLISH_LAG: Duration = Duration.ofMinutes(2)

    /** How soon to look again when the expected sweep has not appeared yet. */
    val RETRY: Duration = Duration.ofMinutes(1)

    /**
     * How long past due a missing sweep is still worth chasing every minute; beyond
     * this the source is having a bad hour.
     */
    val GIVE_UP_AFTER: Duration = Duration.ofMinutes(8)

    /** Never sooner than this: an alarm in the past fires at once, in a loop. */
    val MIN_GAP: Duration = Duration.ofSeconds(30)

    /**
     * When to wake next, from when the newest sweep held was taken. Pure, so the
     * policy is testable without a device.
     */
    fun nextWake(now: Instant, lastSweep: Instant?, interval: Duration): Instant {
        val plain = now.plus(CADENCE)
        if (lastSweep == null) return plain

        val due = lastSweep.plus(interval)
        val expected = due.plus(PUBLISH_LAG)
        return when {
            // The next sweep is still ahead: wake just after it lands. Capped one cycle out,
            // publication lag included, so a sweep collected just after publication does not
            // pull its successor's wake ahead of that one existing.
            expected.isAfter(now.plus(MIN_GAP)) -> minOf(expected, plain.plus(PUBLISH_LAG))
            // It is due and has not been collected: look again shortly.
            now.isBefore(due.plus(GIVE_UP_AFTER)) -> now.plus(RETRY)
            // The radar has gone quiet: keep the model's clock.
            else -> plain
        }
    }

    /**
     * Make sure a wake-up is pending. A pending alarm still in the future is left
     * where it is, so calling this often never postpones the next wake.
     */
    suspend fun ensure(context: Context) {
        val pending = pendingIntent(context, PendingIntent.FLAG_NO_CREATE)
        val at = scheduledAt(context)
        val now = Instant.now()
        if (pending != null && at != null && at.isAfter(now)) return
        arm(context)
    }

    /** Work out the next wake from the radar's clock, and set it. */
    suspend fun arm(context: Context) {
        val container = (context.applicationContext as WetterApplication).container
        val location = container.selectedLocation.current()
        val lastSweep = runCatching { container.nowcasts.latestSweep(location) }.getOrNull()
        val interval = runCatching { container.nowcasts.sweepInterval }.getOrDefault(CADENCE)
        set(context, nextWake(Instant.now(), lastSweep, interval))
    }

    /**
     * Wake almost at once, for a full pass: after a boot, an update, or when a widget
     * asks to draw a forecast that has aged out. Not called from drawing in general,
     * or a pass that could not reach the network would keep asking for another.
     */
    fun soon(context: Context) = set(context, Instant.now().plus(MIN_GAP))

    /**
     * A wake one plain cadence out, set before any work starts, so the clock survives
     * the pass being killed. The precise wake replaces it when the pass finishes.
     */
    fun provisional(context: Context) = set(context, Instant.now().plus(CADENCE))

    private fun set(context: Context, at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val millis = at.toEpochMilli()
        // The permission can be withdrawn between the check and the call, which then
        // throws. Inexact is the fallback, never no wake.
        val exact = exactAllowed(context) &&
            runCatching {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
            }.isSuccess
        if (!exact) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, millis)
            .apply()
        log(
            "next wake ${Duration.between(Instant.now(), at).seconds}s from now" +
                if (exactAllowed(context)) " (exact)" else " (inexact)",
        )
    }

    /**
     * Whether the wake can be set to the minute. From Android 12 this is the "Alarms &
     * reminders" permission, which settings offers the way to; without it the wake
     * is inexact, costing minutes.
     */
    fun exactAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private fun scheduledAt(context: Context): Instant? {
        val millis = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
        return if (millis > 0L) Instant.ofEpochMilli(millis) else null
    }

    /** One intent, so setting again replaces the pending wake rather than adding one. */
    private fun pendingIntent(context: Context, flag: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, HeartbeatReceiver::class.java).setAction(ACTION_WAKE),
            flag or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun log(message: String) {
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    const val ACTION_WAKE = "lv.bolwarra.wetter.action.WAKE"
    private const val PREFS = "heartbeat"
    private const val KEY_AT = "next_wake_at"
    private const val TAG = "Heartbeat"
}

/**
 * Where the alarm lands, and where a reboot or an update re-arms it: alarms
 * survive neither, and a widget should not be wrong all morning after an
 * overnight restart.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // First, before anything that could hang or be killed.
        Heartbeat.provisional(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // A hard bound inside the minute a background broadcast gets: overrunning it gets
                // the app killed.
                withTimeoutOrNull(TOTAL_MS) {
                    when (intent.action) {
                        Heartbeat.ACTION_WAKE -> wake(context)
                        else -> {
                            // Booted, updated, or the exact-alarm permission changed: redraw from
                            // disk at
                            // once, then a full pass in moments, since what is on disk may be hours
                            // behind.
                            withTimeoutOrNull(DRAW_MS) {
                                runCatching { Widgets.refresh(context) }
                            }
                            Heartbeat.soon(context)
                        }
                    }
                } ?: Heartbeat.log("receiver over its bound; provisional wake stands")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun wake(context: Context) {
        // The picture first, from disk with no network, so the widget's time is right
        // within moments of the wake.
        withTimeoutOrNull(DRAW_MS) { runCatching { Widgets.refresh(context) } }

        val outcome = withTimeoutOrNull(BUDGET_MS) { Refresh.run(context) }
        Heartbeat.log("wake: ${outcome ?: "over budget"}")

        // Offline: catch up the moment the network returns.
        if (outcome == Refresh.Outcome.OFFLINE || outcome == null) {
            ForecastRefreshWorker.catchUp(context)
        }
        Heartbeat.arm(context)
    }

    private companion object {
        /** The whole receiver, inside the sixty seconds a background broadcast gets. */
        const val TOTAL_MS = 50_000L

        /**
         * The fetching pass inside that. Anything missing Doze's shorter network grant is
         * caught by [ForecastRefreshWorker.catchUp].
         */
        const val BUDGET_MS = 35_000L

        /** A redraw from disk, which should take a fraction of this. */
        const val DRAW_MS = 5_000L
    }
}
