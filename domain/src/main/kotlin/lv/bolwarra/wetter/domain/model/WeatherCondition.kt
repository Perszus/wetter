// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.model

/**
 * The sky, as a closed set the UI can exhaustively handle. Provider codes (WMO
 * table 4677 for most) are translated by the provider (see WeatherProvider).
 */
enum class WeatherCondition {
    CLEAR,
    MAINLY_CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    DRIZZLE,
    FREEZING_DRIZZLE,
    RAIN,
    FREEZING_RAIN,

    /**
     * Rain and snow falling together. WMO 4677 has no entry for it, but MET Norway
     * reports it, and in a northern winter it is much of what falls.
     */
    SLEET,
    SNOW,
    SNOW_GRAINS,
    RAIN_SHOWERS,
    SNOW_SHOWERS,
    THUNDERSTORM,
    THUNDERSTORM_WITH_HAIL,

    /** A code the provider sent that this version does not recognise. */
    UNKNOWN,

    ;

    /**
     * What this condition implies is falling, for providers that report an
     * amount without saying what it was made of.
     */
    val precipitationKind: PrecipitationKind
        get() = when (this) {
            SNOW, SNOW_GRAINS, SNOW_SHOWERS -> PrecipitationKind.SNOW
            SLEET -> PrecipitationKind.MIXED
            DRIZZLE, FREEZING_DRIZZLE, RAIN, FREEZING_RAIN, RAIN_SHOWERS,
            THUNDERSTORM, THUNDERSTORM_WITH_HAIL,
            -> PrecipitationKind.RAIN
            CLEAR, MAINLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, UNKNOWN ->
                PrecipitationKind.NONE
        }

    /**
     * The same condition, named for what would actually reach the ground: "Rain" at
     * minus four is a contradiction a reader will not forgive.
     *
     * Only ever from liquid towards frozen. A provider saying snow at +3 has usually
     * seen the warm layer aloft, which this app cannot. Freezing rain and drizzle are
     * liquid below zero by definition, so they are left alone.
     */
    fun appropriateFor(temperatureCelsius: Double?): WeatherCondition {
        if (precipitationKind != PrecipitationKind.RAIN) return this
        return when (PrecipitationKind.likelyAt(temperatureCelsius)) {
            PrecipitationKind.SNOW -> when (this) {
                RAIN -> SNOW
                DRIZZLE -> SNOW_GRAINS
                RAIN_SHOWERS -> SNOW_SHOWERS
                else -> this
            }
            PrecipitationKind.MIXED -> when (this) {
                RAIN, DRIZZLE, RAIN_SHOWERS -> SLEET
                else -> this
            }
            else -> this
        }
    }

    /**
     * The same condition, named for how hard it is actually falling.
     *
     * The counterpart to [appropriateFor]: temperature decides rain or snow, the
     * measured rate decides drizzle or rain, so the word cannot disagree with the bar
     * drawn under it. Only drizzle/rain and snow grains/snow are renamed. Showers
     * (intermittency), thunderstorms, and the freezing pair (a hazard distinction)
     * stay as the provider said.
     *
     * @param millimetresPerHour the measured rate, or null when nothing was measured.
     * Null leaves the symbol as it came.
     */
    fun atRate(millimetresPerHour: Double?): WeatherCondition {
        if (millimetresPerHour == null || !isPrecipitating) return this
        val worthNaming = PrecipitationIntensity.ofRate(millimetresPerHour).isWorthNaming
        return when (this) {
            DRIZZLE, RAIN -> if (worthNaming) RAIN else DRIZZLE
            SNOW_GRAINS, SNOW -> if (worthNaming) SNOW else SNOW_GRAINS
            else -> this
        }
    }

    val isPrecipitating: Boolean
        get() = when (this) {
            DRIZZLE, FREEZING_DRIZZLE, RAIN, FREEZING_RAIN, SLEET, SNOW, SNOW_GRAINS,
            RAIN_SHOWERS, SNOW_SHOWERS, THUNDERSTORM, THUNDERSTORM_WITH_HAIL,
            -> true
            CLEAR, MAINLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, UNKNOWN -> false
        }
}
