// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Works out which way the rain is moving, by matching one sweep against an
 * earlier one.
 *
 * The search radius comes from how fast precipitation can travel
 * ([MAX_GROUND_SPEED_KMH]), which keeps the match cheap and rules out implausible
 * displacements. One vector is found for the whole field first and each block
 * refines within a short leash of it, so blocks with nothing to track cannot
 * invent motion. With too little echo it answers nothing, and the caller falls
 * back to the models.
 */
object MotionEstimator {

    /**
     * Faster than this is not precipitation carried by the wind. Generous: a squall
     * line can move at over 100 km/h.
     */
    const val MAX_GROUND_SPEED_KMH = 150.0

    /**
     * How far back the long baseline reaches, in frames: half an hour at the usual
     * cadence, far enough for a slow field to move measurably and near enough that it
     * has not changed shape.
     */
    const val LONG_BASELINE_FRAMES = 3

    /**
     * The floor on agreement: opposed estimates discount a projection hard but do not
     * erase a field that is still right about now.
     */
    const val LEAST_AGREEMENT = 0.25

    /** Below this combined speed there is no motion to disagree about. */
    private const val STILL_FIELD = 0.05

    /** Below this share of wet pixels there is nothing to track. */
    const val MIN_WET_FRACTION = 0.004f

    /** Below this share of the grid observed, the frames are not comparable. */
    const val MIN_COVERAGE = 0.25f

    /**
     * Blocks of a few tens of kilometres, about the scale over which storm motion
     * stays coherent: smaller tracks noise, larger straightens a curving front.
     */
    const val BLOCK_SIZE = 64

    /** How far a block may stray from the global vector, in pixels. */
    private const val REFINE_RADIUS = 6

    /** Every nth pixel is compared. Rain fields are smooth; comparing all is waste. */
    private const val GLOBAL_STEP = 4
    private const val BLOCK_STEP = 2

    /**
     * Displacement step of the coarse pass, well inside the width of any trackable
     * feature, so the fine pass is never left hunting in the wrong place.
     */
    private const val COARSE_STRIDE = 3

    /** A candidate needs this many comparable pixels before its cost means anything. */
    private const val MIN_SAMPLES = 24

    /**
     * The best motion available from a run of sweeps.
     *
     * Measured twice, over a short span and a long one: the long span moves a smooth
     * field further, a larger signal against the same noise. The two also check each
     * other. Sharpness says one match was clean; agreement between independent spans
     * says it was not a coincidence, such as a repeating texture matching at the wrong
     * offset. Disagreement lowers the field's confidence.
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

        // Whichever matched more distinctly carries the answer; the other only says
        // whether to believe it.
        val best = if (long.confidence > short.confidence) long else short
        return best.withConfidence(best.confidence * agreementBetween(short, long))
    }

    /**
     * How far two independent estimates of the same motion agree, 0..1: the size of
     * their difference against their combined size, so it is scale-free. Never zero,
     * since the present moment needs no motion at all.
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
     * sweeps, in pixels.
     */
    private fun searchRadius(metresPerPixel: Double, minutes: Double): Int {
        val metres = MAX_GROUND_SPEED_KMH * METRES_PER_KM / MINUTES_PER_HOUR * minutes
        return (metres / metresPerPixel).roundToInt().coerceIn(0, MAX_SEARCH_RADIUS)
    }

    private data class Global(val offset: Pair<Int, Int>, val confidence: Float)

    /**
     * One vector for the whole field, found coarse then fine: the coarse pass samples
     * every [COARSE_STRIDE] pixels and only the winner's neighbourhood is searched
     * fully.
     *
     * The confidence is the sharpness of the minimum, not its depth: featureless
     * drizzle costs little at every displacement, which is not evidence of motion.
     * Measured on the coarse pass, which sees the whole surface.
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

        return Global(bestOffset, sharpness.coerceIn(0f, 1f))
    }

    /**
     * One block, searched on a short leash around the global vector. The global
     * vector holds unless something strictly beats it: a uniform block ties
     * everywhere, and resolving ties by scan order would drift a stationary field
     * north-west.
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
     * Mean absolute difference between this frame and the last one shifted, or null
     * when too few pixels are comparable. Unobserved pixels take no part, or the
     * emptiest displacement would look like the best match.
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
