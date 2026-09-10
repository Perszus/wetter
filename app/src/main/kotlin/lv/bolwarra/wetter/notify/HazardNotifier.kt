package lv.bolwarra.wetter.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.MainActivity
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.hazard.Hazard
import lv.bolwarra.wetter.domain.hazard.HazardAnnouncement
import lv.bolwarra.wetter.domain.hazard.HazardSeverity

/**
 * Puts a severe-weather warning on the phone.
 *
 * The only thing in Wetter that speaks without being opened, and it is held to
 * that: what reaches here has already passed the thresholds in
 * `domain/hazard/Hazard.kt` and the filter in `HazardAnnouncements`, so a
 * notification means a storm, a gale, torrential rain, heavy snow, ice, or a
 * temperature that is dangerous - never a shower, and never a sunny afternoon.
 *
 * ### One channel, not seven
 *
 * A channel per hazard kind would let somebody silence snow and keep storms,
 * which sounds like a kindness and is actually a way to be caught out by the
 * one they turned off in April. The switch in Settings is the honest control:
 * warnings, or no warnings. Android's own channel settings then sit behind it
 * for anyone who wants to change the sound.
 *
 * ### Said once, and said plainly
 *
 * The title is what it is, the text is when, and the icon is which - a bolt for
 * a storm, wind lines for a gale, a flake for cold. No explanation of how it was
 * arrived at, no provider, no confidence: a person reading a lock screen at
 * seven in the morning needs the thing and the hour.
 */
class HazardNotifier(private val context: Context) {

    /**
     * Post everything in the list.
     *
     * Silent about permission, on purpose: the caller is a background worker
     * with nobody to tell. A phone that has not granted notifications simply
     * does not get them, and the record of what was said is written either way
     * so turning the permission on later does not release a backlog of warnings
     * about weather that has already passed.
     */
    fun post(announcements: List<HazardAnnouncement>, zone: ZoneId, now: Instant) {
        if (announcements.isEmpty() || !isAllowed()) return
        // Said again, in the method that posts, and deliberately not factored
        // out. It is the check the platform actually requires at the call site,
        // and a helper two frames up satisfies a reader without satisfying the
        // tools - or the race, since a permission can be dropped from the shade
        // between one line and the next.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()

        val manager = NotificationManagerCompat.from(context)
        announcements.forEach { announcement ->
            // Caught rather than assumed away. [isAllowed] was true a moment
            // ago, and a permission can be revoked between that line and this
            // one - from the shade, without the app being touched. A warning
            // that cannot be posted is not worth taking the process down for.
            runCatching {
                manager.notify(
                    idFor(announcement),
                    build(announcement.hazard, zone, now),
                )
            }.onFailure { if (it !is SecurityException) throw it }
        }
    }

    /** Whether a warning posted right now would actually appear. */
    fun isAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun build(hazard: Hazard, zone: ZoneId, now: Instant) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(hazard.iconRes())
            .setContentTitle(context.getString(hazard.titleRes()))
            .setContentText(hazardWindow(context, hazard, zone, now))
            // Danger goes past a quiet phone's do-not-disturb the way a heads-up
            // notification does; a warning waits its turn. This is the only
            // place the two severities behave differently, and it is the right
            // place: it is the difference between "take a coat" and "do not
            // drive tonight".
            .setPriority(
                if (hazard.severity == HazardSeverity.DANGER) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setGroup(GROUP_KEY)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Created on demand rather than at startup.
     *
     * Creating it is idempotent and cheap, and doing it here means a phone that
     * never sees severe weather never grows a channel in its settings for
     * something it has not had.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_hazards),
                // High rather than default, because everything that reaches
                // this channel has already cleared a bar set at "change the
                // plan". A channel that never makes a sound would make the one
                // interruption this app is prepared to make into a silent row
                // in a shade nobody scrolled.
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /**
     * One live notification per kind of hazard.
     *
     * Keyed on the kind so a warning that later becomes a danger *replaces* the
     * one already on the lock screen rather than sitting beside it. Two rows
     * about the same storm disagreeing about how bad it is would be the app
     * arguing with itself.
     */
    private fun idFor(announcement: HazardAnnouncement) = BASE_ID + announcement.hazard.kind.ordinal

    private companion object {
        const val CHANNEL_ID = "severe-weather"
        const val GROUP_KEY = "lv.bolwarra.wetter.HAZARDS"

        /** Arbitrary, and only has to not collide with anything else posted. */
        const val BASE_ID = 4100
    }
}
