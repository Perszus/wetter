package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Works out which way the rain is moving, by matching one sweep against the last.
 *
 * ### Why the search is bounded by physics
 *
 * The cost of a block match grows with the square of the search radius, so the
 * radius is the whole performance question. Rather than pick a number that feels
 * fast, it is derived from how fast precipitation can actually travel: past
 * [MAX_GROUND_SPEED_KMH] we are no longer looking at weather, and any match
 * found out there is a coincidence. That makes the search both cheap and a
 * quality control - an implausible displacement is never even considered.
 *
 * ### Why global first, then per block
 *
 * A block on its own is easy to fool. A uniform sheet of drizzle matches itself
 * equally well at every displacement, and an isolated cell can lock onto a
 * different cell entirely. Estimating one robust vector from the whole field
 * first, then letting each block refine within a short leash of it, keeps the
 * answer spatially varying where there is structure to justify it and stops
 * blocks with nothing to track from inventing their own weather.
 *
 * ### Why it may answer nothing
 *
 * With too little echo there is no honest estimate, and a fabricated one is
 * worse than none: it advects a nearly dry map into a confident forecast of dry
 * weather somewhere else. Returning null lets the caller fall back to the
 * numerical model, which is the right source when the radar has nothing to say.
 */
object MotionEstimator {

    /**
     * Faster than this is not precipitation being carried by the wind. Generous
     * on purpose - a fast squall line genuinely does move at over 100 km/h.
     */
    const val MAX_GROUND_SPEED_KMH = 150.0

    /**
     * How far back the long baseline reaches, in frames.
     *
     * Three frames is half an hour at the usual ten-minute cadence. Far enough
     * that a slow, smooth field has actually moved a measurable distance; near
     * enough that it has not also changed shape, which would break the
     * assumption the match rests on.
     */
    const val LONG_BASELINE_FRAMES = 3

    /**
     * The floor on agreement. Two estimates pointing opposite ways discount a
     * projection hard; they do not erase a field that is still right about now.
     */
    const val LEAST_AGREEMENT = 0.25

    /** Below this combined speed there is no motion to disagree about. */
    private const val STILL_FIELD = 0.05

    /** Below this share of wet pixels there is nothing to track. */
    const val MIN_WET_FRACTION = 0.004f

    /** Below this share of the grid observed, the frames are not comparable. */
    const val MIN_COVERAGE = 0.25f

    /**
     * Blocks are 64 px, which at the zooms used here is a few tens of kilometres
     * - about the scale over which storm motion stays coherent. Smaller blocks
     * track noise; larger ones average a curving front into a straight one.
     */
    const val BLOCK_SIZE = 64

    /** How far a block may stray from the global vector, in pixels. */
    private const val REFINE_RADIUS = 6

    /** Every nth pixel is compared. Rain fields are smooth; comparing all is waste. */
    private const val GLOBAL_STEP = 4
    private const val BLOCK_STEP = 2

    /**
     * Displacement step of the coarse pass. Three pixels is well inside the
     * width of any feature big enough to track, so the coarse pass cannot step
     * over a minimum and leave the fine pass hunting in the wrong place.
     */
    private const val COARSE_STRIDE = 3

    /** A candidate needs this many comparable pixels before its cost means anything. */
    private const val MIN_SAMPLES = 24

    /**
     * The best motion available from a run of sweeps.
     *
     * Two sweeps ten minutes apart was all this ever used, and on a smooth field
     * that is the hardest possible question: ten minutes of drift may move the
     * pattern less than the noise in it, so the match is ambiguous and the
     * vector is close to a guess. The frames to do better were already being
     * fetched and thrown away - thirteen of them, two hours of history.
     *
     * So the same measurement is made twice, over a short span and a long one.
     * The long span moves the field further, which is a larger signal against
     * the same noise, and on a featureless sheet it often finds a match the
     * short one cannot.
     *
     * ### The two answers are also a check on each other
     *
     * Match sharpness says how distinctly one displacement beat the others
     * *within a single comparison*. It cannot tell that the whole comparison was
     * misled - a repeating texture matches itself sharply at the wrong offset.
     * Two spans measured independently can: if they agree the motion is real, and
     * if they disagree something is wrong that neither could have reported alone.
     *
     * That disagreement is folded into the confidence the field carries, so a
     * projection built on an unstable estimate is trusted less far. Sharpness
     * says "this match was clean"; agreement says "and it was not a coincidence".
     */
    fun estimate(frames: List<RadarField>): MotionField? {
        val sorted = frames.sortedBy { it.at }
        if (sorted.size < 2) return null

        val latest = sorted.last()
        val short = estimate(sorted[sorted.size - 2], latest)

        val longIndex = sorted.size - 1 - LONG_BASELINE_FRAMES
        val long = if (longIndex >= 0 && longIndex < sorted.size - 2) {
            estimate(sorted[longIndex], latest)
        } else {
            null
        }

        if (long == null) return short
        if (short == null) return long

        // Whichever matched more distinctly carries the answer; the other one
        // is kept only to say whether to believe it.
        val best = if (long.confidence > short.confidence) long else short
        return best.withConfidence(best.confidence * agreementBetween(short, long))
    }

    /**
     * How far two independent estimates of the same motion agree, 0..1.
     *
     * Measured as the size of their difference against their combined size, so
     * it is scale-free: two vectors that differ by 2 px/min agree well if the
     * wind is 40 and barely at all if it is 3.
     *
     * Never returns zero. Total disagreement should discount a projection
     * heavily, not delete a field that may still be right about the present
     * moment - which needs no motion at all.
     */
    fun agreementBetween(a: MotionField, b: MotionField): Float {
        val first = a.meanVector()
        val second = b.meanVector()
        val dx = (first.x - second.x).toDouble()
        val dy = (first.y - second.y).toDouble()
        val difference = hypot(dx, dy)
        val size = hypot(first.x.toDouble(), first.y.toDouble()) +
            hypot(second.x.toDouble(), second.y.toDouble())
        if (size < STILL_FIELD) return 1f
        return (1.0 - difference / size).coerceIn(LEAST_AGREEMENT, 1.0).toFloat()
    }

    fun estimate(previous: RadarField, current: RadarField): MotionField? {
        require(previous.geometry == current.geometry) { "frames must share a geometry" }

        val minutes = Duration.between(previous.at, current.at).toMillis() / MILLIS_PER_MINUTE
        if (minutes <= 0.0) return null
        if (current.wetFraction() < MIN_WET_FRACTION) return null
        if (current.coverage() < MIN_COVERAGE || previous.coverage() < MIN_COVERAGE) return null

        val geometry = current.geometry
        val metresPerPixel = geometry.metresPerPixel(geometry.centreLatitude())
        val radius = searchRadius(metresPerPixel, minutes)
        if (radius < 1) return null

        val global = searchGlobal(previous, current, radius) ?: return null

        val blocksAcross = (geometry.width + BLOCK_SIZE - 1) / BLOCK_SIZE
        val blocksDown = (geometry.height + BLOCK_SIZE - 1) / BLOCK_SIZE
        val vx = FloatArray(blocksAcross * blocksDown)
        val vy = FloatArray(blocksAcross * blocksDown)

        for (row in 0 until blocksDown) {
            for (column in 0 until blocksAcross) {
                val refined = searchBlock(previous, current, column, row, global.offset, radius)
                    ?: global.offset
                vx[row * blocksAcross + column] = (refined.first / minutes).toFloat()
                vy[row * blocksAcross + column] = (refined.second / minutes).toFloat()
            }
        }

        return MotionField(
            blockSize = BLOCK_SIZE,
            blocksAcross = blocksAcross,
            blocksDown = blocksDown,
            vx = vx,
            vy = vy,
            confidence = global.confidence,
        )
    }

    /**
     * The furthest precipitation could plausibly have travelled between the two
     * sweeps, in pixels. Everything beyond it is excluded from the search.
     */
    private fun searchRadius(metresPerPixel: Double, minutes: Double): Int {
        val metres = MAX_GROUND_SPEED_KMH * METRES_PER_KM / MINUTES_PER_HOUR * minutes
        return (metres / metresPerPixel).roundToInt().coerceIn(0, MAX_SEARCH_RADIUS)
    }

    private data class Global(val offset: Pair<Int, Int>, val confidence: Float)

    /**
     * One vector for the whole field, found coarse then fine.
     *
     * Searching every displacement is quadratic in the radius, and at the grid
     * sizes this runs on that is hundreds of millions of comparisons for a
     * result that a coarse pass already locates. So the surface is sampled every
     * [COARSE_STRIDE] pixels first and only the neighbourhood of the winner is
     * examined properly. Precipitation fields are smooth at these scales, so the
     * coarse pass cannot step over a minimum that the fine pass would then miss.
     *
     * The confidence is the sharpness of the minimum, not its depth: a
     * featureless sheet of drizzle produces a low cost at every displacement, and
     * a low cost no better than its neighbours is not evidence of motion. It is
     * measured on the coarse pass because that is the pass that sees the whole
     * surface, which is what "sharper than the rest" is relative to.
     */
    private fun searchGlobal(previous: RadarField, current: RadarField, radius: Int): Global? {
        var best = Double.MAX_VALUE
        var bestOffset: Pair<Int, Int>? = null
        var total = 0.0
        var candidates = 0

        fun consider(dx: Int, dy: Int, tally: Boolean) {
            val cost = costOf(
                previous, current, dx, dy,
                0, 0, current.width, current.height, GLOBAL_STEP,
            ) ?: return
            if (tally) {
                total += cost
                candidates++
            }
            if (cost < best) {
                best = cost
                bestOffset = dx to dy
            }
        }

        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                consider(dx, dy, tally = true)
                dx += COARSE_STRIDE
            }
            dy += COARSE_STRIDE
        }

        val coarse = bestOffset ?: return null
        val mean = if (candidates > 0) total / candidates else 0.0
        val sharpness = if (mean > 0.0) ((mean - best) / mean).toFloat() else 0f

        for (fy in -COARSE_STRIDE..COARSE_STRIDE) {
            for (fx in -COARSE_STRIDE..COARSE_STRIDE) {
                val dxx = coarse.first + fx
                val dyy = coarse.second + fy
                if (abs(dxx) > radius || abs(dyy) > radius) continue
                consider(dxx, dyy, tally = false)
            }
        }

        return Global(bestOffset ?: coarse, sharpness.coerceIn(0f, 1f))
    }

    /**
     * One block, searched on a short leash around the global vector.
     *
     * The global vector is measured first and holds the block unless something
     * strictly beats it. That tie-break is not a detail: a block of uniform rain
     * - or of nothing at all - costs the same at every displacement, so with
     * ties resolved by scan order the winner is simply whichever candidate the
     * loops reach first, which is the most negative one. Across a whole field of
     * such blocks that bias does not cancel, and a perfectly stationary rain
     * field came out drifting steadily north-west.
     */
    private fun searchBlock(
        previous: RadarField,
        current: RadarField,
        column: Int,
        row: Int,
        global: Pair<Int, Int>,
        radius: Int,
    ): Pair<Int, Int>? {
        val left = column * BLOCK_SIZE
        val top = row * BLOCK_SIZE
        val right = minOf(left + BLOCK_SIZE, current.width)
        val bottom = minOf(top + BLOCK_SIZE, current.height)

        var bestOffset = global
        var best = costOf(
            previous, current, global.first, global.second,
            left, top, right, bottom, BLOCK_STEP,
        ) ?: return null

        for (ddy in -REFINE_RADIUS..REFINE_RADIUS) {
            for (ddx in -REFINE_RADIUS..REFINE_RADIUS) {
                if (ddx == 0 && ddy == 0) continue
                val dx = global.first + ddx
                val dy = global.second + ddy
                // The leash must not carry a block past what physics allows.
                if (abs(dx) > radius || abs(dy) > radius) continue
                val cost = costOf(previous, current, dx, dy, left, top, right, bottom, BLOCK_STEP)
                    ?: continue
                if (cost < best) {
                    best = cost
                    bestOffset = dx to dy
                }
            }
        }
        return bestOffset
    }

    /**
     * Mean absolute difference between this frame and the last one shifted.
     *
     * Null when too few pixels are comparable. Pixels the radar did not observe
     * take no part: a coverage hole is not agreement, and counting it as a zero
     * difference would make the emptiest displacement look like the best match.
     */
    private fun costOf(
        previous: RadarField,
        current: RadarField,
        dx: Int,
        dy: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        step: Int,
    ): Double? {
        var sum = 0.0
        var samples = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val now = current[x, y]
                val then = previous[x - dx, y - dy]
                if (!now.isNoEcho() && !then.isNoEcho()) {
                    sum += abs(now - then)
                    samples++
                }
                x += step
            }
            y += step
        }
        return if (samples < MIN_SAMPLES) null else sum / samples
    }

    /** Guards against a pathological zoom producing a search nobody can afford. */
    private const val MAX_SEARCH_RADIUS = 48

    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val MINUTES_PER_HOUR = 60.0
    private const val METRES_PER_KM = 1000.0
}
