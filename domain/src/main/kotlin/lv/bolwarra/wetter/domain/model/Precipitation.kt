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
         * What would fall, if anything did, at this temperature.
         *
         * For naming the thing before it arrives - a chart headed "rain" during
         * a January cold snap is wrong in a way that matters to whoever reads
         * it - and for radar, which returns echoes from hydrometeors and cannot
         * say whether they are frozen.
         *
         * Screen-level air temperature, which is what every provider publishes
         * and what almost every consumer forecast uses. It is a proxy: what
         * actually decides the answer is the depth of the warm layer the flake
         * falls through, so snow reaches the ground at +3 in a dry column and
         * rain falls at -1 under an inversion. Hence a band of genuine
         * uncertainty around freezing answered with MIXED rather than a
         * confident guess either way.
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
 * How hard it is falling, in the conventional meteorological bands for rainfall
 * rate (mm per hour). These are the thresholds the timeline's bar heights and
 * colours are keyed to, so they live in one place and are tested.
 *
 * VIOLENT is included because the scale has a top; it is not expected often.
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

    companion object {
        /** Below this, an hour is reported dry rather than as a sliver of a bar. */
        const val TRACE_MM_PER_HOUR = 0.1
        const val LIGHT_MM_PER_HOUR = 0.5
        const val MODERATE_MM_PER_HOUR = 2.5
        const val HEAVY_MM_PER_HOUR = 7.6
        const val VIOLENT_MM_PER_HOUR = 50.0

        /**
         * Classify a rate in millimetres per hour.
         *
         * A null rate means the provider had nothing to say, which is not the same
         * as zero — but for the purposes of drawing a bar both are "no bar", so
         * both map to [NONE]. Callers that need to distinguish "dry" from
         * "unknown" must check the source value.
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
