package lv.bolwarra.wetter.ui.chart

/**
 * Smooth interpolation through a series that never invents a value the series
 * does not contain.
 *
 * This exists because the obvious way to draw a flowing curve is wrong. A
 * Catmull-Rom spline — the usual choice, and what most charting libraries reach
 * for — overshoots: given rainfall of 0, 0, 4, 0 it dips *below zero* between
 * the flat points and bulges *above 4* around the peak. On a rain chart that
 * means drawing negative rainfall, and drawing a heavier downpour than anybody
 * forecast, purely as an artefact of the curve.
 *
 * The Fritsch–Carlson construction fixes exactly that. It picks tangents such
 * that the curve is monotone on every interval where the data is, which
 * guarantees the drawn value between two samples always lies between them.
 * Smooth to look at, and incapable of claiming more than it was given.
 *
 * The tangents are what the whole thing turns on, so they are computed here
 * rather than left to a library whose overshoot behaviour would be somebody
 * else's decision.
 */
internal object MonotoneCurve {

    /**
     * Tangents for a monotone cubic Hermite spline through [values], assumed to
     * be evenly spaced.
     *
     * Even spacing is not a simplification — a forecast series is one sample per
     * fixed interval by construction, and if that ever stops being true the
     * chart has a bigger problem than its tangents.
     */
    fun tangents(values: List<Float>): FloatArray {
        val n = values.size
        if (n < 2) return FloatArray(n)

        // Secants: the slope of each straight segment between neighbours.
        val secants = FloatArray(n - 1) { values[it + 1] - values[it] }

        val tangents = FloatArray(n)
        tangents[0] = secants[0]
        tangents[n - 1] = secants[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = (secants[i - 1] + secants[i]) / 2f
        }

        for (i in 0 until n - 1) {
            if (secants[i] == 0f) {
                // A flat segment must stay flat. Without this, a curve through
                // two equal dry hours bows away from zero and draws rain that
                // was never forecast.
                tangents[i] = 0f
                tangents[i + 1] = 0f
                continue
            }

            val alpha = tangents[i] / secants[i]
            val beta = tangents[i + 1] / secants[i]

            // A tangent pointing against the segment would turn the curve back
            // on itself, producing a local extreme between two samples.
            if (alpha < 0f) tangents[i] = 0f
            if (beta < 0f) tangents[i + 1] = 0f

            // Fritsch-Carlson: keeping (alpha, beta) inside a circle of radius 3
            // is what bounds the overshoot away entirely.
            val magnitude = alpha * alpha + beta * beta
            if (magnitude > MONOTONICITY_LIMIT) {
                val scale = 3f / kotlin.math.sqrt(magnitude)
                tangents[i] = scale * alpha * secants[i]
                tangents[i + 1] = scale * beta * secants[i]
            }
        }
        return tangents
    }

    /**
     * The value of the spline a fraction [t] of the way through the interval
     * that starts at [index]. Used by the tests; the drawing code emits Bézier
     * control points instead, which is the same curve expressed for a canvas.
     */
    fun valueAt(values: List<Float>, tangents: FloatArray, index: Int, t: Float): Float {
        val p0 = values[index]
        val p1 = values[index + 1]
        val m0 = tangents[index]
        val m1 = tangents[index + 1]
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * p0 +
            (t3 - 2 * t2 + t) * m0 +
            (-2 * t3 + 3 * t2) * p1 +
            (t3 - t2) * m1
    }

    /**
     * The two cubic Bézier control points for the interval starting at [index],
     * as fractions of the interval width.
     *
     * A Hermite segment converted to Bézier form: the control points sit a third
     * of the way along each tangent, which is the identity that lets a canvas
     * draw the same curve with `cubicTo`.
     */
    fun controlPoints(values: List<Float>, tangents: FloatArray, index: Int): Pair<Float, Float> =
        Pair(
            values[index] + tangents[index] / 3f,
            values[index + 1] - tangents[index + 1] / 3f,
        )

    private const val MONOTONICITY_LIMIT = 9f
}
