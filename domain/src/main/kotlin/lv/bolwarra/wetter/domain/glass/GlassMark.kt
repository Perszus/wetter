// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.glass

import lv.bolwarra.wetter.domain.hazard.Hazards
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity.Companion.HEAVY_MM_PER_HOUR
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity.Companion.MODERATE_MM_PER_HOUR
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity.Companion.VIOLENT_MM_PER_HOUR
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherCondition.CLEAR
import lv.bolwarra.wetter.domain.model.WeatherCondition.DRIZZLE
import lv.bolwarra.wetter.domain.model.WeatherCondition.FOG
import lv.bolwarra.wetter.domain.model.WeatherCondition.FREEZING_DRIZZLE
import lv.bolwarra.wetter.domain.model.WeatherCondition.FREEZING_RAIN
import lv.bolwarra.wetter.domain.model.WeatherCondition.MAINLY_CLEAR
import lv.bolwarra.wetter.domain.model.WeatherCondition.OVERCAST
import lv.bolwarra.wetter.domain.model.WeatherCondition.PARTLY_CLOUDY
import lv.bolwarra.wetter.domain.model.WeatherCondition.RAIN
import lv.bolwarra.wetter.domain.model.WeatherCondition.RAIN_SHOWERS
import lv.bolwarra.wetter.domain.model.WeatherCondition.SLEET
import lv.bolwarra.wetter.domain.model.WeatherCondition.SNOW
import lv.bolwarra.wetter.domain.model.WeatherCondition.SNOW_GRAINS
import lv.bolwarra.wetter.domain.model.WeatherCondition.SNOW_SHOWERS
import lv.bolwarra.wetter.domain.model.WeatherCondition.THUNDERSTORM
import lv.bolwarra.wetter.domain.model.WeatherCondition.THUNDERSTORM_WITH_HAIL
import lv.bolwarra.wetter.domain.model.WeatherCondition.UNKNOWN

/**
 * What the weather leaves on the dial's glass, as the name of one picture.
 *
 * Rain beads, snow settles, frost creeps in from the edge. There is a picture for
 * each state the app can name, by day and by night, chosen from exactly what the
 * dial shows, so the glass cannot disagree with the word printed on it.
 *
 * Every threshold is one the app already uses:
 * - how hard it falls: [PrecipitationIntensity], then the torrential-rain and
 * heavy-snow hazard thresholds in [Hazards];
 * - wind: Beaufort, with the gale and storm limits the warnings use;
 * - mist: a dry, quiet hour at [MIST_HUMIDITY] or more, since no service forecasts
 * visibility.
 *
 * Where the provider's word and the app's correction disagree, the picture shows
 * both halves: a provider's "rain" at a drizzle rate is drizzle and rain; its
 * "drizzle" below freezing is drizzle and snow grains.
 */
object GlassMark {

    /** At or above this, a dry hour's glass carries condensation. */
    const val MIST_HUMIDITY = 95

    /**
     * Everything the choice depends on.
     *
     * @param raw the provider's condition, before any correction
     * @param rate millimetres per hour, or null when nothing was measured
     * @param extremeCold an extreme-cold hazard is in force now
     * @param extremeHeat an extreme-heat hazard is in force now
     * @param hazyAir the air quality is notably poor, which makes heat hazy
     * @param shown the word the dial prints, when corrected further than [raw] can
     * be, such as by radar (see ObservedCondition). Without it, the word is worked
     * out from [raw] as on every other surface.
     */
    data class Input(
        val raw: WeatherCondition,
        val rate: Double?,
        val windSpeed: Double?,
        val temperature: Double?,
        val humidity: Int?,
        val isDay: Boolean,
        val extremeCold: Boolean = false,
        val extremeHeat: Boolean = false,
        val hazyAir: Boolean = false,
        val shown: WeatherCondition? = null,
    )

    /**
     * The picture's name - `rain_day`, `windy_snow_night` - or null for no mark at
     * all, which is only ever a condition this version does not recognise.
     */
    fun of(input: Input): String? {
        val name = choose(input) ?: return null
        return (name + if (input.isDay) "_day" else "_night").lowercase()
    }

    private fun choose(input: Input): String? {
        val rate = input.rate
        val i = rate?.let { PrecipitationIntensity.ofRate(it) }
        val app = input.shown ?: input.raw.appropriateFor(input.temperature).atRate(rate)
        val raw = input.raw
        val quiet = i == null || i <= PrecipitationIntensity.LIGHT

        // 1. Extremes, when nothing heavier is falling.
        if (input.extremeCold && quiet) {
            when (app) {
                FREEZING_RAIN, FREEZING_DRIZZLE -> return "EXTREME_COLD_ICE"
                FOG -> return "EXTREME_COLD_FOG"
                SNOW, SNOW_GRAINS, SNOW_SHOWERS, SLEET -> return "EXTREME_COLD_SNOW"
                CLEAR, MAINLY_CLEAR -> return "EXTREME_COLD_CLEAR"
                PARTLY_CLOUDY, OVERCAST -> return "EXTREME_COLD_CLOUDY"
                else -> Unit
            }
        }
        if (input.extremeHeat && app in setOf(CLEAR, MAINLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG)) {
            return when {
                input.hazyAir -> "EXTREME_HEAT_HAZY"
                app == CLEAR || app == MAINLY_CLEAR -> "EXTREME_HEAT_CLEAR"
                else -> "EXTREME_HEAT_CLOUDY"
            }
        }

        // 2. Thunder. What falls is decided by temperature, which the
        //    appearance leaves alone for thunderstorms.
        val kind = PrecipitationKind.likelyAt(input.temperature)
        if (app == THUNDERSTORM_WITH_HAIL) {
            return when {
                kind == PrecipitationKind.SNOW -> "THUNDERSTORM_SNOW_HAIL"
                kind == PrecipitationKind.MIXED -> "THUNDERSTORM_RAIN_SNOW_HAIL"
                i == null || i == PrecipitationIntensity.NONE -> "THUNDERSTORM_HAIL"
                i <= PrecipitationIntensity.LIGHT -> "THUNDERSTORM_LIGHT_HAIL"
                i == PrecipitationIntensity.MODERATE -> "THUNDERSTORM_RAIN_HAIL"
                else -> "THUNDERSTORM_HEAVY_HAIL"
            }
        }
        if (app == THUNDERSTORM) {
            if (i == null || i == PrecipitationIntensity.NONE) return "THUNDERSTORM"
            return when (kind) {
                PrecipitationKind.SNOW -> when (snowTier(rate)) {
                    "LIGHT" -> "THUNDERSTORM_LIGHT_SNOW"
                    "" -> "THUNDERSTORM_SNOW"
                    else -> "THUNDERSTORM_HEAVY_SNOW"
                }
                PrecipitationKind.MIXED -> "THUNDERSTORM_RAIN_SNOW"
                else -> when (val tier = rainTier(rate)) {
                    "" -> "THUNDERSTORM_RAIN"
                    "TORRENTIAL" -> "THUNDERSTORM_VERY_HEAVY_RAIN"
                    else -> "THUNDERSTORM_${tier}_RAIN"
                }
            }
        }

        // 3. Where the provider and the measurement disagree: the mixed pictures.
        mixed(raw, app, i, rate, input.windSpeed)?.let { return it }

        // 4. One thing falling.
        val wind = windWord(input.windSpeed)
        when (app) {
            RAIN, SNOW, SLEET -> {
                wetWind(wind, app)?.let { return "${it}_${app.name}" }
                return when (app) {
                    RAIN -> named(rainTier(rate), "RAIN")
                    SNOW -> named(snowTier(rate), "SNOW")
                    else -> if (i != null &&
                        i >= PrecipitationIntensity.HEAVY
                    ) {
                        "HEAVY_SLEET"
                    } else {
                        "SLEET"
                    }
                }
            }
            DRIZZLE -> return if (i == PrecipitationIntensity.NONE) "LIGHT_DRIZZLE" else "DRIZZLE"
            SNOW_GRAINS -> return if (i ==
                PrecipitationIntensity.NONE
            ) {
                "LIGHT_SNOW_GRAINS"
            } else {
                "SNOW_GRAINS"
            }
            FREEZING_DRIZZLE ->
                return if (i ==
                    PrecipitationIntensity.LIGHT
                ) {
                    "HEAVY_FREEZING_DRIZZLE"
                } else {
                    "FREEZING_DRIZZLE"
                }
            FREEZING_RAIN -> return when (i) {
                null, PrecipitationIntensity.MODERATE -> "FREEZING_RAIN"
                PrecipitationIntensity.LIGHT -> "LIGHT_FREEZING_RAIN"
                else -> "HEAVY_FREEZING_RAIN"
            }
            RAIN_SHOWERS -> return when (i) {
                null -> "RAIN_SHOWERS"
                PrecipitationIntensity.NONE -> "PASSING_RAIN_SHOWERS"
                PrecipitationIntensity.TRACE -> "LIGHT_RAIN_SHOWERS"
                else -> named(rainTier(rate).replace("TORRENTIAL", "VERY_HEAVY"), "RAIN_SHOWERS")
            }
            SNOW_SHOWERS -> return when {
                i == null -> "SNOW_SHOWERS"
                i == PrecipitationIntensity.NONE -> "PASSING_SNOW_SHOWERS"
                i == PrecipitationIntensity.TRACE -> "SNOW_FLURRIES"
                rate >= Hazards.SNOW_WARNING_MM -> "HEAVY_SNOW_SHOWERS"
                else -> "SNOW_SHOWERS"
            }
            FOG -> {
                if (i == null || i == PrecipitationIntensity.NONE) return "FOG"
                return if (kind == PrecipitationKind.SNOW) {
                    if (i ==
                        PrecipitationIntensity.TRACE
                    ) {
                        "FOG_WITH_FREEZING_PRECIPITATION"
                    } else {
                        "FOG_WITH_SNOW"
                    }
                } else {
                    if (i == PrecipitationIntensity.TRACE) "FOG_WITH_DRIZZLE" else "FOG_WITH_RAIN"
                }
            }
            UNKNOWN -> return null
            else -> Unit
        }

        // 5. A dry sky: real wind is what the glass shows, then damp air, then the sky.
        if (wind != null &&
            wind != "BREEZY"
        ) {
            return if (wind == "STORM_FORCE") "STORM_FORCE_WIND" else wind
        }
        if (input.humidity != null && input.humidity >= MIST_HUMIDITY) return "MIST"
        if (wind == "BREEZY") return "BREEZY"
        val calm = input.windSpeed != null && beaufort(input.windSpeed) == 0
        if (calm && (app == CLEAR || app == MAINLY_CLEAR)) return "CALM"
        return when (app) {
            CLEAR -> "CLEAR"
            MAINLY_CLEAR -> "MOSTLY_CLEAR"
            else -> app.name
        }
    }

    private fun mixed(
        raw: WeatherCondition,
        app: WeatherCondition,
        i: PrecipitationIntensity?,
        rate: Double?,
        windSpeed: Double?,
    ): String? {
        val barely = i == PrecipitationIntensity.NONE || i == PrecipitationIntensity.TRACE
        return when {
            raw == RAIN && app == DRIZZLE -> "DRIZZLE_RAIN"
            raw == DRIZZLE && app == RAIN -> "HEAVY_DRIZZLE"
            raw == SNOW && app == SNOW_GRAINS -> "SNOW_SNOW_GRAINS"
            raw == SNOW_GRAINS && app == SNOW -> "HEAVY_SNOW_GRAINS"
            raw == DRIZZLE && app == SNOW_GRAINS -> "DRIZZLE_SNOW_GRAINS"
            raw == RAIN && app == SNOW_GRAINS -> "SNOW_RAIN_SNOW_GRAINS"
            raw == DRIZZLE && app == SLEET -> "DRIZZLE_SNOW"
            raw == RAIN && app == SLEET -> when {
                barely -> "RAIN_DRIZZLE_SNOW"
                wetWind(windWord(windSpeed), SLEET) != null -> "WINDY_SLEET"
                else -> named(
                    when (rainTier(rate)) {
                        "LIGHT" -> "LIGHT"
                        "" -> ""
                        else -> "HEAVY"
                    },
                    "RAIN_SNOW",
                )
            }
            raw == RAIN_SHOWERS && app == SLEET ->
                if (i != null &&
                    i >= PrecipitationIntensity.HEAVY
                ) {
                    "HEAVY_RAIN_SNOW_SHOWERS"
                } else {
                    "RAIN_SNOW_SHOWERS"
                }
            raw == FREEZING_RAIN && barely -> "FREEZING_RAIN_AND_DRIZZLE"
            raw == FREEZING_DRIZZLE && i != null && i >= PrecipitationIntensity.MODERATE ->
                "HEAVY_FREEZING_RAIN_AND_DRIZZLE"
            else -> null
        }
    }

    /** Rain's rung: the app's scale, then the torrential-rain warning and danger. */
    private fun rainTier(rate: Double?): String = when {
        rate == null || rate < HEAVY_MM_PER_HOUR -> when (
            rate?.let {
                PrecipitationIntensity.ofRate(it)
            }
        ) {
            PrecipitationIntensity.LIGHT -> "LIGHT"
            else -> ""
        }
        rate < Hazards.TORRENT_WARNING_MM -> "HEAVY"
        rate < VIOLENT_MM_PER_HOUR -> "VERY_HEAVY"
        else -> "TORRENTIAL"
    }

    /** Snow's rung: by liquid equivalent, then the heavy-snow warning and danger. */
    private fun snowTier(rate: Double?): String = when {
        rate == null -> ""
        rate < MODERATE_MM_PER_HOUR -> "LIGHT"
        rate < Hazards.SNOW_WARNING_MM -> ""
        rate < Hazards.SNOW_DANGER_MM -> "HEAVY"
        else -> "VERY_HEAVY"
    }

    private fun named(tier: String, base: String) = if (tier.isEmpty()) base else "${tier}_$base"

    /** Beaufort number for a mean wind in m/s. */
    private fun beaufort(speed: Double): Int =
        BEAUFORT_TOPS.indexOfFirst { speed < it }.let { if (it < 0) BEAUFORT_TOPS.size else it }

    private val BEAUFORT_TOPS =
        doubleArrayOf(0.5, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)

    /**
     * The glass's wind words. Beaufort 8 and 9 are both a gale: the dial's wind
     * reading carries the exact speed.
     */
    private fun windWord(speed: Double?): String? {
        if (speed == null) return null
        return when (beaufort(speed)) {
            in 10..12 -> "STORM_FORCE"
            8, 9 -> "GALE"
            7 -> "VERY_STRONG_WIND"
            6 -> "STRONG_WIND"
            5 -> "WINDY"
            4 -> "BREEZY"
            else -> null
        }
    }

    /**
     * Wind over falling rain, snow or sleet, rounded down to a drawn rung, so the
     * glass never claims more wind than there is.
     */
    private fun wetWind(word: String?, what: WeatherCondition): String? {
        if (word == null) return null
        val drawn = when (what) {
            RAIN, SNOW -> listOf("WINDY", "STRONG_WIND", "GALE")
            SLEET -> listOf("WINDY")
            else -> return null
        }
        val order =
            listOf("BREEZY", "WINDY", "STRONG_WIND", "VERY_STRONG_WIND", "GALE", "STORM_FORCE")
        return order.take(order.indexOf(word) + 1).lastOrNull { it in drawn }
    }
}
