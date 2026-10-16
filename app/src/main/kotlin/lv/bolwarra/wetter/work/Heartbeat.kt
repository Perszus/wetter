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
 * ### Timed to the radar, not to a round number
 *
 * The next half hour is what this app is for, and radar is what carries it. A
 * new sweep is published every ten minutes, so the right moment to wake is just
 * after the next one appears - not "every fifteen minutes", which is WorkManager's
 * floor and lands anywhere in the radar's cycle, so a sweep could sit published
 * for most of a quarter of an hour before anything here looked. [nextWake] reads
 * when the newest sweep held was taken and aims a moment past the next one; if
 * that one is late, it looks again shortly, for a while, and then goes back to
 * the plain cadence. Without radar at all - most of the world - it is simply
 * every ten minutes, the model's own refresh clock.
 *
 * ### An alarm, because nothing else keeps time
 *
 * Periodic work is a floor, not a schedule, and Doze stretches it to hours. An
 * allow-while-idle alarm is the one thing Android delivers on time to an idle
 * phone - at most every nine minutes, which a ten-minute cadence fits inside -
 * and its delivery comes with a few seconds of network even in Doze, which is
 * why [Refresh] spends them on radar first. Exact when the app may set exact
 * alarms (Android 11 and below always; 12 and later when somebody allows it in
 * Settings, see [exactAllowed]); otherwise inexact, which the system may slip by
 * a few minutes and is still far closer than a job.
 *
 * The periodic job stays, as the net under this: it re-arms the alarm on every
 * run, so an OEM battery manager that clears alarms costs one interval at worst.
 */
internal object Heartbeat {

    /** The plain cadence: the radar's, and the model's freshness window. */
    val CADENCE: Duration = Duration.ofMinutes(10)

    /**
     * How long after a sweep's own time it is usually published. The sweep is
     * stamped with when the radar scanned, and assembling and posting the
     * composite takes a couple of minutes.
     */
    val PUBLISH_LAG: Duration = Duration.ofMinutes(2)

    /** How soon to look again when the expected sweep has not appeared yet. */
    val RETRY: Duration = Duration.ofMinutes(1)

    /**
     * How long past due a missing sweep is still worth chasing every minute.
     * Beyond this the source is having a bad hour, and polling it every minute
     * would spend battery on somebody else's outage.
     */
    val GIVE_UP_AFTER: Duration = Duration.ofMinutes(8)

    /** Never sooner than this: an alarm in the past fires at once, in a loop. */
    val MIN_GAP: Duration = Duration.ofSeconds(30)

    /**
     * When to wake next, from when the newest sweep held was taken.
     *
     * Pure, so the whole policy is testable without a device.
     */
    fun nextWake(now: Instant, lastSweep: Instant?, interval: Duration): Instant {
        val plain = now.plus(CADENCE)
        if (lastSweep == null) return plain

        val due = lastSweep.plus(interval)
        val expected = due.plus(PUBLISH_LAG)
        return when {
            // The next sweep is still ahead: wake just after it lands. Capped one
            // cycle out - publication lag included, or a sweep collected a few
            // seconds after its own publication had its successor's wake pulled
            // a few seconds before *that* one existed, and paid a second wake.
            expected.isAfter(now.plus(MIN_GAP)) -> minOf(expected, plain.plus(PUBLISH_LAG))
            // It is due and has not been collected: look again shortly.
            now.isBefore(due.plus(GIVE_UP_AFTER)) -> now.plus(RETRY)
            // The radar has gone quiet. Keep the model's clock until it speaks.
            else -> plain
        }
    }

    /**
     * Make sure a wake-up is pending. Safe to call from anywhere, any number of
     * times: a pending alarm that is still in the future is left exactly where
     * it is, so this can never postpone the next wake by being called often.
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
     * Wake almost at once, for a full pass.
     *
     * For the moments when what is on disk is known to be behind: the phone has
     * just booted, the app was just updated, or the system has asked a widget to
     * draw itself and the forecast it would draw has aged out. Not called from
     * drawing in general - a widget redrawn at the end of a pass that could not
     * reach the network would otherwise ask for another pass, and another.
     */
    fun soon(context: Context) = set(context, Instant.now().plus(MIN_GAP))

    /**
     * A wake one plain cadence out, set before any work starts.
     *
     * Everything after it can be cut short - the system kills a receiver that
     * overruns, and an OEM battery manager kills processes - and the clock must
     * survive that. The precise wake replaces this one when the pass finishes;
     * if it never finishes, this one is still pending.
     */
    fun provisional(context: Context) = set(context, Instant.now().plus(CADENCE))

    private fun set(context: Context, at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val millis = at.toEpochMilli()
        // The permission can be withdrawn between the check and the call, and
        // the call then throws. Inexact is the honest fallback, never no wake.
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
     * Whether the wake can be set to the minute.
     *
     * Below Android 12 any app may. From 12 it is a permission somebody grants
     * in Settings ("Alarms & reminders"), and the settings panel offers the way
     * there - without it the wake is inexact, which costs minutes, not data.
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
 * Where the alarm lands, and where a reboot or an update re-arms it.
 *
 * Alarms do not survive either, and an app that waits to be opened before its
 * clock starts again is an app whose widget is wrong all morning after the
 * phone restarts overnight - hence the boot permission this used to do without.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // First, before anything that could hang or be killed.
        Heartbeat.provisional(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // A hard bound inside the minute a background broadcast gets.
                // Overrunning it is not a slow wake, it is the app killed.
                withTimeoutOrNull(TOTAL_MS) {
                    when (intent.action) {
                        Heartbeat.ACTION_WAKE -> wake(context)
                        else -> {
                            // Booted, updated, or the exact-alarm permission
                            // changed. The picture is redrawn from disk at once,
                            // and a full pass follows in moments: after a night
                            // switched off, what is on disk is hours behind, and
                            // the clock should not wait out a cycle to notice.
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
        // The picture first, from disk and with no network: whatever happens
        // next, the widget is right about what time it is within moments of the
        // wake rather than after a download that may never come.
        withTimeoutOrNull(DRAW_MS) { runCatching { Widgets.refresh(context) } }

        val outcome = withTimeoutOrNull(BUDGET_MS) { Refresh.run(context) }
        Heartbeat.log("wake: ${outcome ?: "over budget"}")

        // Offline is worth catching up the moment the network returns, rather
        // than at the next wake.
        if (outcome == Refresh.Outcome.OFFLINE || outcome == null) {
            ForecastRefreshWorker.catchUp(context)
        }
        Heartbeat.arm(context)
    }

    private companion object {
        /**
         * The whole receiver, against the sixty seconds a background broadcast
         * gets before the system declares the app unresponsive and kills it.
         */
        const val TOTAL_MS = 50_000L

        /**
         * The fetching pass inside that. Doze's own network grant is shorter;
         * anything that misses it is caught by [ForecastRefreshWorker.catchUp].
         */
        const val BUDGET_MS = 35_000L

        /** A redraw from disk, which should take a fraction of this. */
        const val DRAW_MS = 5_000L
    }
}
