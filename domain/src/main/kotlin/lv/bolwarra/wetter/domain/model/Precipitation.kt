// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.model

/**
 * What is falling. Kept separate from intensity: 0.4 mm/h of snow and 0.4 mm/h of
 * rain are the same number and a completely different afternoon.
 */
enum class PrecipitationKind {
    NONE,
    RAIN,
    SNOW,
    MIXED,
    ;

    companion object {
        /**
         * What would fall, if anything did, at this temperature: for naming rain before it
         * arrives, and for radar, which cannot tell frozen echoes from liquid.
         *
         * Screen-level air temperature is a proxy; the real answer depends on the warm
         * layer aloft, so snow can reach the ground at +3 and rain at -1. The band of
         * genuine uncertainty around freezing answers MIXED.
         */
        fun likelyAt(temperatureCelsius: Double?): PrecipitationKind = when {
            temperatureCelsius == null -> RAIN
            temperatureCelsius <= SNOW_CEILING_C -> SNOW
            temperatureCelsius <= MIXED_CEILING_C -> MIXED
            else -> RAIN
        }

        /** At or below this, what falls reaches the ground frozen. */
        const val SNOW_CEILING_C = 0.5

        /** Between the two, either is possible and neither is worth asserting. */
        const val MIXED_CEILING_C = 2.5
    }
}

/**
 * How hard it is falling, in the conventional bands for rainfall rate (mm/h). The
 * timeline's bar heights and colours are keyed to these.
 */
enum class PrecipitationIntensity {
    NONE,

    /** Measurable but barely: damp ground, no more. */
    TRACE,
    LIGHT,
    MODERATE,
    HEAVY,
    VIOLENT,
    ;

    val isWet: Boolean get() = this != NONE

    /**
     * Whether this is worth calling rain, as opposed to being measurable.
     *
     * Against aerodrome reports over 1295 hours at ten airports, an hour forecast in
     * the trace band was precipitating at the station 53% of the time; at light or
     * above, 79%. So a trace is drawn but never named. Raising the measurable
     * threshold instead loses real rain from the curve.
     */
    val isWorthNaming: Boolean get() = this >= LIGHT

    companion object {
        /** Below this, an hour is reported dry rather than as a sliver of a bar. */
        const val TRACE_MM_PER_HOUR = 0.1
        const val LIGHT_MM_PER_HOUR = 0.5
        const val MODERATE_MM_PER_HOUR = 2.5
        const val HEAVY_MM_PER_HOUR = 7.6
        const val VIOLENT_MM_PER_HOUR = 50.0

        /**
         * Classify a rate in millimetres per hour. Null (the provider said nothing) and
         * zero both draw no bar, so both map to [NONE]; callers that must tell "dry" from
         * "unknown" check the source value.
         */
        fun ofRate(millimetresPerHour: Double?): PrecipitationIntensity = when {
            millimetresPerHour == null -> NONE
            millimetresPerHour < TRACE_MM_PER_HOUR -> NONE
            millimetresPerHour < LIGHT_MM_PER_HOUR -> TRACE
            millimetresPerHour < MODERATE_MM_PER_HOUR -> LIGHT
            millimetresPerHour < HEAVY_MM_PER_HOUR -> MODERATE
            millimetresPerHour < VIOLENT_MM_PER_HOUR -> HEAVY
            else -> VIOLENT
        }
    }
}
