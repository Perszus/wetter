// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.format

import android.content.res.Resources
import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.at
import lv.bolwarra.wetter.domain.conditionsAt
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.forecast.ObservedSpell
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.PrecipitationSpell
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * When it next rains, in one line, for the Today page and the widgets alike.
 *
 * @property matters whether rain is falling or on its way; "no rain" is shown
 *   in a quieter ink.
 */
internal data class NextRain(val text: String, val matters: Boolean) {

    companion object {

        fun of(
            resources: Resources,
            forecast: WeatherForecast,
            timeline: List<FusedPrecipitation>,
            now: Instant,
        ): NextRain {
            // From the same series the chart is drawn from, so the two agree.
            val spell = ObservedSpell.next(timeline, forecast.hourly, now)

            // Rain the radar sees overhead wins over an hourly model average that misses it.
            val rate = rateNow(timeline, forecast.hourly, now)
            // Rain worth naming, not merely a trace (see PrecipitationIntensity.isWorthNaming).
            val fallingNow = PrecipitationIntensity.ofRate(rate).isWorthNaming
            val modelAgrees = spell != null && !spell.start.isAfter(now)

            val text = if (fallingNow && !modelAgrees) {
                resources.getString(
                    R.string.next_rain_falling,
                    resources.getString(radarKind(spell, forecast, now).labelRes()),
                )
            } else {
                spell.describe(resources, now, forecast)
            }
            return NextRain(text, matters = fallingNow || spell != null)
        }

        /** One line, in the fewest words that are still true. */
        private fun PrecipitationSpell?.describe(
            resources: Resources,
            now: Instant,
            forecast: WeatherForecast,
        ): String {
            val zone = forecast.location.zone
            if (this == null) return resources.getString(R.string.next_rain_none)

            val kind = resources.getString(kind.labelRes())
            if (!start.isAfter(now)) {
                // Already falling: say when it stops, if the forecast knows.
                return if (isOpenEnded) {
                    resources.getString(R.string.next_rain_falling, kind)
                } else {
                    resources.getString(R.string.next_rain_until, kind, formatTime(end, zone))
                }
            }

            val date = start.atZone(zone).toLocalDate()
            val day = when (dayDistance(start, now, zone)) {
                DayDistance.TODAY -> null
                DayDistance.TOMORROW -> resources.getString(R.string.relative_tomorrow)
                DayDistance.THIS_WEEK -> formatWeekday(date)
                // A week out, a weekday name would be ambiguous.
                DayDistance.LATER -> formatDayAndMonth(date)
            }

            return if (day == null) {
                resources.getString(R.string.next_rain_starts, kind, formatTime(start, zone))
            } else {
                resources.getString(
                    R.string.next_rain_starts_on,
                    kind,
                    day,
                    formatTime(start, zone),
                )
            }
        }

        /**
         * What the radar sees, named. Radar cannot tell frozen from liquid, so
         * the kind comes from the model's next spell, or else the temperature.
         */
        private fun radarKind(
            spell: PrecipitationSpell?,
            forecast: WeatherForecast,
            now: Instant,
        ): PrecipitationKind {
            spell?.kind?.takeIf { it != PrecipitationKind.NONE }?.let { return it }
            return PrecipitationKind.likelyAt(forecast.conditionsAt(now).temperature)
        }
    }
}

/** The rate falling now: the radar's where it is current, the model's hour otherwise. */
internal fun rateNow(
    timeline: List<FusedPrecipitation>,
    ahead: List<HourlyWeather>,
    now: Instant,
): Double {
    val nearest = timeline.minByOrNull { Duration.between(it.at, now).abs() }
    if (nearest != null && Duration.between(nearest.at, now).abs() <= NOW_TOLERANCE) {
        return nearest.millimetresPerHour
    }
    return ahead.at(now)?.precipitation ?: 0.0
}

/** How far from now a projected sample may be and still count as now. */
private val NOW_TOLERANCE: Duration = Duration.ofMinutes(4)
