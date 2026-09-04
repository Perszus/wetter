package lv.bolwarra.wetter.ui.theme

import androidx.compose.runtime.Immutable
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherCondition

/**
 * The weather, as a change in the light rather than as a picture of itself.
 *
 * A weather app that colours a rain screen blue and a sun screen yellow has two
 * screens. The intent here is one object - the same paper, the same ink, the
 * same instrument - seen under a different sky. What a sky changes is not the
 * object's colours but the conditions it is being read in: how much light there
 * is, how far apart light and dark sit, how much colour survives, and how much
 * of the air between you and it is doing something.
 *
 * So an atmosphere is four numbers and no palette. It is applied to the plate's
 * specification before any tone is generated, which means every role moves
 * together and none can drift out of relation with the others - and it means a
 * condition added later is a row in a table, not a new set of colours.
 *
 * ### Restraint
 *
 * The whole range of [ground] is about six lightness steps, and of [contrast]
 * about a fifth. That is deliberately near the threshold of noticing. The test
 * of this system is not that somebody sees the screen change when it starts
 * raining; it is that the screen feels different and they could not say why.
 *
 * @param ground L* added to the page. Overcast weighs it down, snow lifts it.
 * @param contrast multiplier on every ink contrast target. Haze compresses the
 *   range between ink and ground, exactly as it does outdoors.
 * @param chroma multiplier on every accent's colourfulness. Fog drains colour;
 *   a storm concentrates it.
 * @param veil how much of the page is drawn back over the content as haze, 0..1.
 *   The only additive effect in the system, and the only one that can hide
 *   anything, so it is capped low and reserved for conditions that genuinely
 *   obscure.
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
         * The sky, read from what the app actually knows.
         *
         * Intensity is taken separately from condition because a provider's code
         * says "rain" for both a drizzle you would not remark on and a downpour
         * that empties a street, and those are not the same light.
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

            // Heavier precipitation deepens whatever the condition established,
            // rather than switching to a state of its own. One axis, not two.
            val weight = when (intensity) {
                PrecipitationIntensity.HEAVY, PrecipitationIntensity.VIOLENT -> DOWNPOUR
                else -> 1.0
            }

            // Night is not a separate design. It is the same modulation with
            // less of it, because after dark the plate is already dark and a
            // sky that darkened it further would only take away contrast.
            val nightward = if (isDay) 1.0 else NIGHT_DAMPING

            return Atmosphere(
                ground = base.ground * weight * nightward,
                contrast = 1.0 + (base.contrast - 1.0) * nightward,
                chroma = 1.0 + (base.chroma - 1.0) * weight * nightward,
                veil = (base.veil * nightward).toFloat(),
            )
        }

        /**
         * Sun on paper: a shade brighter, a touch more separation, colour intact.
         * Nothing is added - the page is simply better lit.
         */
        private val CLEAR = Atmosphere(ground = 1.4, contrast = 1.06)

        private val MAINLY_CLEAR = Atmosphere(ground = 0.7, contrast = 1.03)

        /** A lid overhead: less light, and a little less separation under it. */
        private val OVERCAST = Atmosphere(ground = -2.0, contrast = 0.95, chroma = 0.90)

        /**
         * The one condition that genuinely takes information away, and the only
         * one allowed a veil. Contrast collapses, colour drains, and the page
         * itself is drawn back over its own content.
         */
        private val FOG = Atmosphere(ground = -0.8, contrast = 0.82, chroma = 0.55, veil = 0.10f)

        private val DRIZZLE = Atmosphere(ground = -1.4, contrast = 0.96, chroma = 0.96)

        /** Rain concentrates the one hue it owns while dimming everything round it. */
        private val RAIN = Atmosphere(ground = -2.4, contrast = 0.98, chroma = 1.05)

        /** Ice: the light goes flat and blue-grey rather than dark. */
        private val FREEZING = Atmosphere(ground = -1.0, contrast = 0.92, chroma = 0.85)

        private val SLEET = Atmosphere(ground = -1.6, contrast = 0.94, chroma = 0.80)

        /**
         * Snow is the one weather that makes the world *brighter* and flatter at
         * once - everything reflects, and everything loses its edges. The only
         * entry in this table with a positive ground and a reduced contrast.
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
