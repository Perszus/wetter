package lv.bolwarra.wetter.domain.model

/**
 * What is falling. Kept separate from intensity: 0.4 mm/h of snow and 0.4 mm/h of
 * rain are the same number and a completely different afternoon.
 */
enum class PrecipitationKind { NONE, RAIN, SNOW, MIXED }

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
