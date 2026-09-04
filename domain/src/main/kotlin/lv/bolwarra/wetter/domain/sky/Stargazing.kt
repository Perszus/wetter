package lv.bolwarra.wetter.domain.sky

/**
 * Whether the sky overhead is worth looking up at.
 *
 * Built on the cloud decks rather than the total, because the total cannot
 * answer this question at all: 90% of high cirrus is a sky you can still pick
 * constellations out of, and 40% of low stratus is a lid with holes in it. Only
 * the split says which one you have.
 */
object Stargazing {

    /**
     * How far the sun must be below the horizon before the sky is dark enough.
     *
     * The end of nautical twilight. Astronomers want -18°, the end of
     * astronomical twilight, and get it here as the top of the scale rather than
     * as the gate - at -12° the bright stars and every planet are already out,
     * and in a Nordic summer -18° never arrives at all, so a mark that waited
     * for it would simply never appear for half the year at these latitudes.
     */
    const val DARK_ENOUGH_DEGREES = -12.0

    /** Full astronomical darkness. */
    const val FULLY_DARK_DEGREES = -18.0

    /**
     * How much of the sky can be given up before it stops being worth going out.
     *
     * Two thirds clear. Below that there is more cloud than sky and the good
     * moments are gaps passing overhead, which is a different activity.
     */
    const val WORTH_LOOKING_UP = 0.66f

    /**
     * How opaque high cloud is, against low and medium taken as solid.
     *
     * Cirrus is ice crystals kilometres thick and mostly empty; it dims a sky
     * and puts a ring round the moon without hiding much. Stratus is a ceiling.
     * Treating them alike is what makes a total cloud figure useless for this.
     */
    const val HIGH_CLOUD_OPACITY = 0.45f

    /**
     * How much a full moon costs.
     *
     * It does not hide the sky, it raises its floor: the moon washes out faint
     * stars while the bright ones and the planets carry on regardless. So it
     * grades the answer rather than gating it - a clear night under a full moon
     * is still a clear night, and telling somebody to stay in would be wrong.
     *
     * Illumination stands in for the moon actually being up, which is an
     * approximation and a knowingly rough one. It leans the right way - a near
     * full moon rises about when the sun sets and is up all night, a new moon
     * is up all day - but it is wrong for a gibbous moon in the hours before it
     * rises, and correcting that needs a lunar ephemeris this app does not have.
     */
    const val FULL_MOON_COST = 0.45f

    /**
     * What the sky is doing, from the readings that decide it.
     *
     * @param cloudLow percent, 0..100. Null where the provider did not say, and
     *   treated as unknown rather than as clear: claiming a good night from a
     *   reading nobody took is the one failure mode worth designing out.
     */
    fun assess(
        cloudLow: Int?,
        cloudMedium: Int?,
        cloudHigh: Int?,
        sunElevationDegrees: Double,
        moonIllumination: Double,
        precipitationMmPerHour: Double?,
    ): Sky {
        val dark = sunElevationDegrees < DARK_ENOUGH_DEGREES
        val clarity = clarityOf(cloudLow, cloudMedium, cloudHigh) ?: return Sky.unknown(dark)
        val dry = (precipitationMmPerHour ?: 0.0) < WET

        val moonFactor = 1.0f - FULL_MOON_COST * moonIllumination.toFloat().coerceIn(0f, 1f)
        // Only the depth of darkness that has actually been reached counts, so a
        // northern summer's endless dusk cannot score as a black sky.
        val darkness = (
            (sunElevationDegrees - DARK_ENOUGH_DEGREES) /
                (FULLY_DARK_DEGREES - DARK_ENOUGH_DEGREES)
            ).toFloat().coerceIn(0f, 1f)

        return Sky(
            isDark = dark,
            isClear = clarity >= WORTH_LOOKING_UP && dry,
            clarity = clarity,
            quality = (clarity * moonFactor * (SETTLED + (1f - SETTLED) * darkness))
                .coerceIn(0f, 1f),
            moonWashed = moonIllumination >= MOON_WORTH_MENTIONING,
        )
    }

    /**
     * How much of the sky is actually open, from the three decks.
     *
     * The decks overlap - a deck seen through a gap in the one below is counted
     * by both - so they are combined as independent layers of cover rather than
     * added. Adding them is what produces a "150% cloudy" sky.
     */
    fun clarityOf(cloudLow: Int?, cloudMedium: Int?, cloudHigh: Int?): Float? {
        // High cloud alone is enough to answer with, but low cloud alone is not:
        // a missing low deck is the difference between a clear night and a lid.
        if (cloudLow == null && cloudMedium == null && cloudHigh == null) return null

        val low = fractionOf(cloudLow)
        val medium = fractionOf(cloudMedium)
        val high = fractionOf(cloudHigh) * HIGH_CLOUD_OPACITY
        return ((1f - low) * (1f - medium) * (1f - high)).coerceIn(0f, 1f)
    }

    private fun fractionOf(percent: Int?): Float = (percent ?: 0).coerceIn(0, 100) / 100f

    /** Below this an hour is dry enough to stand outside in. */
    private const val WET = 0.1

    /** Quality retained at the gate, before deeper darkness adds the rest. */
    private const val SETTLED = 0.8f

    /** Below this the moon is not worth warning anybody about. */
    private const val MOON_WORTH_MENTIONING = 0.5

    /**
     * The sky, as far as looking at it goes.
     *
     * @param quality 0..1, for grading the words. Not shown as a number: a sky
     *   is not 0.72 good, and printing it would claim a precision that three
     *   cloud percentages and a moon phase cannot support.
     */
    data class Sky(
        val isDark: Boolean,
        val isClear: Boolean,
        val clarity: Float,
        val quality: Float,
        val moonWashed: Boolean,
    ) {
        /** Worth putting a mark on the dial for. */
        val isWorthIt: Boolean get() = isDark && isClear

        companion object {
            fun unknown(dark: Boolean) = Sky(
                isDark = dark,
                isClear = false,
                clarity = 0f,
                quality = 0f,
                moonWashed = false,
            )
        }
    }
}
