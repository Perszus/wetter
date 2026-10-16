// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.forecast

import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * Whether today is a day to take something with you.
 *
 * At moderate, not merely wet: a mark that is up on most days in a maritime
 * climate stops being read.
 *
 * Asked of the whole calendar day, from midnight, so the mark is stable: somebody
 * decides once, on the way out, and it should not go out at eleven. It
 * moves only when the forecast does.
 *
 * The bar is lower than the one rain claims use, because the costs run the other
 * way: an umbrella on a dry day costs little, none before a storm costs a lot.
 */
object UmbrellaDay {

    /**
     * Consecutive projected steps that must be moderate before the mark goes up. The
     * model applies the rate to an hour and the radar projection to ten minutes, so a
     * single radar step would raise the mark for a burst the model averages away.
     */
    const val SUSTAINED_STEPS = 2

    /** The bar: where rain stops being something you would walk through. */
    val WORTH_CARRYING: PrecipitationIntensity = PrecipitationIntensity.MODERATE

    /**
     * @param timeline the fused projection. Radar counts: the model's hourly figure
     * averages a short downpour below moderate, and the mark must agree with the
     * curve drawn above the moderate guide.
     */
    fun isUmbrellaDay(
        forecast: WeatherForecast,
        timeline: List<FusedPrecipitation>,
        now: Instant,
    ): Boolean {
        val zone: ZoneId = forecast.location.zone
        val today = now.atZone(zone).toLocalDate()

        fun isToday(at: Instant) = at.atZone(zone).toLocalDate() == today

        val projected = timeline
            .filter { isToday(it.at) }
            .windowed(SUSTAINED_STEPS, partialWindows = false)
            .any { window ->
                window.all {
                    PrecipitationIntensity.ofRate(it.millimetresPerHour) >= WORTH_CARRYING
                }
            }

        return projected ||
            forecast.hourly.any { isToday(it.timestamp) && it.intensity >= WORTH_CARRYING }
    }
}
