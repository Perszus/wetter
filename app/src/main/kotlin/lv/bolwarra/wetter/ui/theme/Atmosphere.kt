// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.theme

import androidx.compose.runtime.Immutable
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherCondition

/**
 * The weather, as a change in the light rather than a picture of itself: the same
 * paper and ink, seen under a different sky.
 *
 * Four numbers and no palette, applied to the plate's specification before any
 * tone is generated, so every role moves together. The ranges are kept near the
 * threshold of noticing: the screen should feel different without anybody being
 * able to say why.
 *
 * @param ground L* added to the page. Overcast weighs it down, snow lifts it.
 * @param contrast multiplier on every ink contrast target. Haze compresses the
 * range between ink and ground, as it does outdoors.
 * @param chroma multiplier on every accent's colourfulness. Fog drains colour;
 * a storm concentrates it.
 * @param veil how much of the page is drawn back over the content as haze, 0..1.
 * The only effect that can hide anything, so it is capped low and kept for
 * conditions that obscure.
 */
@Immutable
data class Atmosphere(
    val ground: Double = 0.0,
    val contrast: Double = 1.0,
    val chroma: Double = 1.0,
    val veil: Float = 0f,
) {
    companion object {
        /** No modulation: the plate as specified. */
        val Neutral = Atmosphere()

        /**
         * The sky, from the condition and the intensity separately: a provider's "rain"
         * covers drizzle and downpour alike, which are not the same light.
         */
        fun of(
            condition: WeatherCondition,
            intensity: PrecipitationIntensity,
            isDay: Boolean,
        ): Atmosphere {
            val base = when (condition) {
                WeatherCondition.CLEAR -> CLEAR
                WeatherCondition.MAINLY_CLEAR -> MAINLY_CLEAR
                WeatherCondition.PARTLY_CLOUDY -> Neutral
                WeatherCondition.OVERCAST -> OVERCAST
                WeatherCondition.FOG -> FOG
                WeatherCondition.DRIZZLE, WeatherCondition.FREEZING_DRIZZLE -> DRIZZLE
                WeatherCondition.RAIN, WeatherCondition.RAIN_SHOWERS -> RAIN
                WeatherCondition.FREEZING_RAIN -> FREEZING
                WeatherCondition.SLEET -> SLEET
                WeatherCondition.SNOW, WeatherCondition.SNOW_SHOWERS,
                WeatherCondition.SNOW_GRAINS,
                -> SNOW
                WeatherCondition.THUNDERSTORM,
                WeatherCondition.THUNDERSTORM_WITH_HAIL,
                -> STORM
                // An unrecognised code must not restyle the app on a guess.
                WeatherCondition.UNKNOWN -> Neutral
            }

            // Heavier precipitation deepens whatever the condition established, rather than
            // switching to a state of its own.
            val weight = when (intensity) {
                PrecipitationIntensity.HEAVY, PrecipitationIntensity.VIOLENT -> DOWNPOUR
                else -> 1.0
            }

            // Night is the same modulation with less of it: the plate is already dark, and
            // darkening it further would only take away contrast.
            val nightward = if (isDay) 1.0 else NIGHT_DAMPING

            return Atmosphere(
                ground = base.ground * weight * nightward,
                contrast = 1.0 + (base.contrast - 1.0) * nightward,
                chroma = 1.0 + (base.chroma - 1.0) * weight * nightward,
                veil = (base.veil * nightward).toFloat(),
            )
        }

        /** Sun on paper: a shade brighter, a touch more separation, colour intact. */
        private val CLEAR = Atmosphere(ground = 1.4, contrast = 1.06)

        private val MAINLY_CLEAR = Atmosphere(ground = 0.7, contrast = 1.03)

        /** A lid overhead: less light, and a little less separation under it. */
        private val OVERCAST = Atmosphere(ground = -2.0, contrast = 0.95, chroma = 0.90)

        /**
         * The one condition that takes information away, and the only one allowed a
         * veil.
         */
        private val FOG = Atmosphere(ground = -0.8, contrast = 0.82, chroma = 0.55, veil = 0.10f)

        private val DRIZZLE = Atmosphere(ground = -1.4, contrast = 0.96, chroma = 0.96)

        /** Rain concentrates the one hue it owns while dimming everything round it. */
        private val RAIN = Atmosphere(ground = -2.4, contrast = 0.98, chroma = 1.05)

        /** Ice: the light goes flat and blue-grey rather than dark. */
        private val FREEZING = Atmosphere(ground = -1.0, contrast = 0.92, chroma = 0.85)

        private val SLEET = Atmosphere(ground = -1.6, contrast = 0.94, chroma = 0.80)

        /**
         * Snow makes the world brighter and flatter at once: the only entry with a
         * positive ground and reduced contrast.
         */
        private val SNOW = Atmosphere(ground = 2.0, contrast = 0.90, chroma = 0.70)

        /** A storm darkens the ground and sharpens what is left on it. */
        private val STORM = Atmosphere(ground = -3.6, contrast = 1.08, chroma = 1.12)

        /** How much a heavy fall deepens its condition. */
        private const val DOWNPOUR = 1.35

        /** How much of the daytime modulation survives after dark. */
        private const val NIGHT_DAMPING = 0.55
    }
}
