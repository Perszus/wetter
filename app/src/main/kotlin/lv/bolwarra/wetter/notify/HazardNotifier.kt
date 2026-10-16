// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * Puts a severe-weather warning on the phone: the only thing in Wetter that speaks
 * without being opened. What arrives here has passed `Hazards` and
 * `HazardAnnouncements`, so it is never a shower or a sunny afternoon.
 *
 * One channel: the Settings switch is the control, with Android's channel settings
 * behind it. The title says what, the text when, the icon which; no provider and
 * no confidence.
 */
class HazardNotifier(private val context: Context) {

    /**
     * Post everything in the list. Silent about a missing permission: the record is
     * written either way, so granting it later does not release a backlog.
     */
    fun post(announcements: List<HazardAnnouncement>, zone: ZoneId, now: Instant) {
        if (announcements.isEmpty() || !isAllowed()) return
        // Checked here, at the call site, as the platform requires: the permission can be
        // dropped between one line and the next.
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
            // Danger is high priority, a warning default: the only place the two severities
            // behave differently.
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
     * Created on demand, so a phone that never sees severe weather never grows a
     * channel for it.
     */
    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_hazards),
                // High importance: everything that reaches this channel has cleared a bar set at
                // "change the plan".
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /**
     * One live notification per kind of hazard, so a warning that becomes a danger
     * replaces the one already shown.
     */
    private fun idFor(announcement: HazardAnnouncement) = BASE_ID + announcement.hazard.kind.ordinal

    private companion object {
        const val CHANNEL_ID = "severe-weather"
        const val GROUP_KEY = "lv.bolwarra.wetter.HAZARDS"

        /** Arbitrary, and only has to not collide with anything else posted. */
        const val BASE_ID = 4100
    }
}
