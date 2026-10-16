// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.notify

import android.content.Context
import androidx.annotation.DrawableRes
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.hazard.Hazard
import lv.bolwarra.wetter.domain.hazard.HazardKind
import lv.bolwarra.wetter.domain.hazard.Hazards
import lv.bolwarra.wetter.ui.format.DayDistance
import lv.bolwarra.wetter.ui.format.dayDistance
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.format.formatWeekday

/**
 * The dial's own words, for a notification: the same strings, read without a
 * composition.
 */
internal fun Hazard.titleRes(): Int = when {
    kind == HazardKind.DAMAGING_WIND && (peak ?: 0.0) >= Hazards.HURRICANE_MS ->
        R.string.hazard_hurricane
    else -> when (kind) {
        HazardKind.EXTREME_HEAT -> R.string.hazard_heat
        HazardKind.EXTREME_COLD -> R.string.hazard_cold
        HazardKind.DAMAGING_WIND -> R.string.hazard_wind
        HazardKind.TORRENTIAL_RAIN -> R.string.hazard_rain
        HazardKind.HEAVY_SNOW -> R.string.hazard_snow
        HazardKind.ICE -> R.string.hazard_ice
        HazardKind.THUNDERSTORM -> R.string.hazard_thunder
        HazardKind.EXTREME_UV -> R.string.hazard_uv
        HazardKind.UNBREATHABLE_AIR -> R.string.hazard_air
    }
}

/**
 * When it runs, in the fewest words that stay true.
 *
 * An open end is said as an open end: a warning reading "until 09:00" when
 * 09:00 is merely where the forecast stopped would be inventing a reprieve.
 */
internal fun hazardWindow(context: Context, hazard: Hazard, zone: ZoneId, now: Instant): String {
    if (hazard.hasBegunBy(now)) {
        return if (hazard.until != null) {
            context.getString(R.string.hazard_now_until, formatTime(hazard.until, zone))
        } else {
            context.getString(R.string.hazard_now_open)
        }
    }

    val start = when (dayDistance(hazard.from, now, zone)) {
        DayDistance.TODAY -> formatTime(hazard.from, zone)
        DayDistance.TOMORROW -> context.getString(
            R.string.hazard_tomorrow,
            formatTime(hazard.from, zone),
        )
        DayDistance.THIS_WEEK, DayDistance.LATER -> context.getString(
            R.string.hazard_on_day,
            formatWeekday(hazard.from.atZone(zone).toLocalDate()),
            formatTime(hazard.from, zone),
        )
    }

    return if (hazard.until != null) {
        context.getString(R.string.hazard_from_until, start, formatTime(hazard.until, zone))
    } else {
        context.getString(R.string.hazard_from_open, start)
    }
}

/**
 * The status bar mark, one per kind, so the shade says what without unlocking.
 * The app's own glyphs as flat silhouettes, since the system paints the alpha
 * channel in its own colour. The triangle is the fallback for kinds that never
 * notify.
 */
@DrawableRes
internal fun Hazard.iconRes(): Int = when (kind) {
    HazardKind.THUNDERSTORM -> R.drawable.ic_hazard_thunderstorm
    HazardKind.DAMAGING_WIND -> R.drawable.ic_hazard_wind
    HazardKind.TORRENTIAL_RAIN -> R.drawable.ic_hazard_rain
    HazardKind.HEAVY_SNOW -> R.drawable.ic_hazard_snow
    HazardKind.ICE -> R.drawable.ic_hazard_ice
    HazardKind.EXTREME_HEAT -> R.drawable.ic_hazard_heat
    HazardKind.EXTREME_COLD -> R.drawable.ic_hazard_cold
    HazardKind.EXTREME_UV, HazardKind.UNBREATHABLE_AIR -> R.drawable.ic_notification_hazard
}
