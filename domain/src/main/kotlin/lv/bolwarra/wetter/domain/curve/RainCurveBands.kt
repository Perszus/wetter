package lv.bolwarra.wetter.domain.curve

import lv.bolwarra.wetter.domain.model.PrecipitationIntensity

/**
 * The vertical axis of every rain curve Wetter draws.
 *
 * This is geometry, not painting: given a rate it says how high up the track
 * that rate sits, and which of the three levels it is in. Nothing here knows
 * about a canvas, a colour or a screen.
 *
 * It lives in `:domain` because there are now two painters. The app draws the
 * curve in Compose at full size with the level named in words beside it; the
 * home-screen widget draws the same curve into a bitmap at a fifth of the area,
 * with no words at all. Those are different pictures of one shape, and the one
 * thing that must not differ is the shape - a widget whose peak sits in
 * "moderate" while the app puts the same hour in "heavy" is worse than no
 * widget, because the reader has no way to tell which lied.
 *
 * ### Why it is not a scale of millimetres
 *
 * The rates are wildly unevenly spaced: a trace is a tenth of a millimetre and
 * torrential is fifty. Drawn linearly against a real downpour, drizzle is two
 * percent of the height and reads as nothing at all - when it is precisely the
 * difference between nothing and take a coat.
 *
 * So the axis is anchored to the conventional intensity bands, each given a
 * slice of the height wide enough to see. It is not linear in millimetres and
 * does not pretend to be; there is no number on it. What it is linear in is how
 * wet you get, which is what the chart is for.
 */
object RainCurveBands {

    /**
     * The level the curve names a rate, which is coarser than the domain's own.
     *
     * Two things went with the earlier five-level version. "Barely" was a level
     * nobody does anything differently about, so it is folded into light: the
     * bottom band means anything from a spit to a proper shower. And the top
     * band was reserved for torrential, which put a fifth of the chart aside for
     * weather that essentially never arrives and pushed everything real into the
     * lower half.
     */
    fun levelOf(millimetresPerHour: Double): PrecipitationIntensity = when {
        millimetresPerHour < PrecipitationIntensity.TRACE_MM_PER_HOUR ->
            PrecipitationIntensity.NONE

        millimetresPerHour < PrecipitationIntensity.MODERATE_MM_PER_HOUR ->
            PrecipitationIntensity.LIGHT

        millimetresPerHour < PrecipitationIntensity.HEAVY_MM_PER_HOUR ->
            PrecipitationIntensity.MODERATE

        else -> PrecipitationIntensity.HEAVY
    }

    /** Where a rate sits on the track, as a fraction of its height. */
    fun heightFraction(millimetresPerHour: Float): Float {
        val mm = millimetresPerHour.toDouble()
        if (mm <= 0.0) return 0f
        for (i in 0 until ANCHORS.size - 1) {
            val (lowMm, lowY) = ANCHORS[i]
            val (highMm, highY) = ANCHORS[i + 1]
            if (mm <= highMm) {
                val t = ((mm - lowMm) / (highMm - lowMm)).toFloat()
                // Eased across each band rather than run straight through it.
                // The anchors are what carry the meaning - trace here, heavy
                // there - but joining them with straight segments puts a slope
                // change at every boundary, so a perfectly smooth rate still
                // drew a visibly kinked line wherever it crossed from one
                // intensity into the next.
                return lowY + (highY - lowY) * smoothStep(t)
            }
        }
        return 1f
    }

    /**
     * The height at which light becomes moderate, and moderate becomes heavy.
     *
     * Exposed because both painters need to put something at these two
     * places - a guide line, a colour step - and both must put it at the same
     * height as the curve that crosses it.
     */
    val moderateEdge: Float get() = heightFraction(MODERATE_EDGE_MM)

    val heavyEdge: Float get() = heightFraction(HEAVY_EDGE_MM)

    /**
     * Dry sits on the floor and the least rain there is steps just clear of it,
     * so the question the chart is asked most - whether it will rain at all - is
     * answered without reading anything.
     */
    private val ANCHORS = listOf(
        0.0 to 0.00f,
        // The least rain there is, lifted just clear of the floor.
        PrecipitationIntensity.TRACE_MM_PER_HOUR to TRACE_FLOOR,
        PrecipitationIntensity.MODERATE_MM_PER_HOUR to LIGHT_TOP,
        PrecipitationIntensity.HEAVY_MM_PER_HOUR to MODERATE_TOP,
        PrecipitationIntensity.VIOLENT_MM_PER_HOUR to 1.00f,
    )

    /**
     * Smoothstep: flat at both ends, steepest in the middle.
     *
     * Chosen over a spline through the anchors because it is local. A spline
     * would let a change to one band's height shift the curve inside its
     * neighbours, which would make the bands stop meaning exactly what they say.
     */
    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

private const val TRACE_FLOOR = 0.08f

private const val MODERATE_EDGE_MM = PrecipitationIntensity.MODERATE_MM_PER_HOUR.toFloat()
private const val HEAVY_EDGE_MM = PrecipitationIntensity.HEAVY_MM_PER_HOUR.toFloat()

/**
 * The three levels take the same height as each other.
 *
 * Light used to take a fifth less, on the reasoning that it is the least of the
 * three. There is no reading of the chart that wants that: the bands are a scale
 * of the three words anybody acts on, and a scale whose steps are different
 * sizes is asking the reader to remember which step is which. Equal thirds means
 * the height a curve has climbed is the fraction of the way through the levels
 * it has climbed, with nothing to correct for.
 *
 * The floor sits *inside* the light band rather than under all three. The first
 * attempt at equal bands added it underneath, which made light 38.7% of the
 * height against 30.7% for the other two - arithmetic that said "equal" over a
 * picture that plainly was not. A trace is not a fourth level; it is the bottom
 * of the light one.
 */
private const val BANDS = 3f
private const val LIGHT_TOP = 1f / BANDS
private const val MODERATE_TOP = 2f / BANDS
