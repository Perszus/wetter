// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain

import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherCondition

/**
 * The condition, corrected by what was actually seen.
 *
 * The provider's symbol summarises a whole hour, computed in advance; the chart
 * and rate beneath it follow the radar. Where the two disagree about now, the one
 * that looked wins (docs/design-principles.md).
 *
 * Upgrading is easy: radar sees rain the model missed. Downgrading drops the
 * precipitation but cannot name the sky, since radar does not see cloud, so the
 * sky is named from the model's cloud cover. Thunder, fog and freezing rain are
 * invisible to radar and keep the provider's word.
 */
object ObservedCondition {

    /**
     * @param reported what the provider said for this hour.
     * @param observedRate the fused rate at this moment, mm/h, or null where there is
     * no radar: an absent observation is not a contradicting one.
     * @param cloudCover percent, for naming the sky when precipitation is removed.
     */
    fun of(
        reported: WeatherCondition,
        observedRate: Double?,
        temperature: Double?,
        cloudCover: Int?,
    ): WeatherCondition {
        if (observedRate == null) return reported
        if (reported in RADAR_CANNOT_SEE) return reported

        // The trace bar, lower than rain claims use: this reads an observation rather than
        // a model's hourly average, and the word it yields for the trace band is
        // "drizzle", the accurate name for a tenth of a millimetre from an overcast.
        val falling = observedRate >= PrecipitationIntensity.TRACE_MM_PER_HOUR
        return when {
            falling && !reported.isPrecipitating -> observedPrecipitation(observedRate, temperature)
            !falling && reported.isPrecipitating -> skyOf(cloudCover)
            else -> reported
        }
    }

    /**
     * What to call precipitation the provider did not report: the kind from the
     * temperature, the intensity from the measured rate.
     */
    private fun observedPrecipitation(rate: Double, temperature: Double?): WeatherCondition =
        when (PrecipitationKind.likelyAt(temperature)) {
            PrecipitationKind.SNOW ->
                if (PrecipitationIntensity.ofRate(rate) >= PrecipitationIntensity.MODERATE) {
                    WeatherCondition.SNOW
                } else {
                    WeatherCondition.SNOW_GRAINS
                }

            PrecipitationKind.MIXED -> WeatherCondition.SLEET

            else -> if (PrecipitationIntensity.ofRate(rate) >= PrecipitationIntensity.LIGHT) {
                WeatherCondition.RAIN
            } else {
                WeatherCondition.DRIZZLE
            }
        }

    /**
     * The sky, once the rain is out of the word, from the model's cloud cover; without
     * it, UNKNOWN.
     */
    private fun skyOf(cloudCover: Int?): WeatherCondition = when {
        cloudCover == null -> WeatherCondition.UNKNOWN
        cloudCover >= OVERCAST_FROM -> WeatherCondition.OVERCAST
        cloudCover >= PARTLY_FROM -> WeatherCondition.PARTLY_CLOUDY
        cloudCover >= MAINLY_CLEAR_FROM -> WeatherCondition.MAINLY_CLEAR
        else -> WeatherCondition.CLEAR
    }

    /**
     * Conditions radar cannot see, which keep the provider's word whatever the rate:
     * thunder needs a lightning network, fog sits under the beam, and freezing is a
     * fact about the ground.
     */
    private val RADAR_CANNOT_SEE = setOf(
        WeatherCondition.THUNDERSTORM,
        WeatherCondition.THUNDERSTORM_WITH_HAIL,
        WeatherCondition.FOG,
        WeatherCondition.FREEZING_RAIN,
        WeatherCondition.FREEZING_DRIZZLE,
    )

    /** The octas the WMO calls overcast, as a percentage. */
    private const val OVERCAST_FROM = 85

    private const val PARTLY_FROM = 40

    private const val MAINLY_CLEAR_FROM = 15
}
