// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.radar

import kotlin.math.floor
import kotlin.math.hypot

/** How fast and which way precipitation is travelling, in grid pixels per minute. */
data class Motion(val x: Float, val y: Float) {
    val speed: Float get() = hypot(x, y)

    companion object {
        val STILL = Motion(0f, 0f)
    }
}

/**
 * Where the precipitation is going, across the grid. Estimated per block, the
 * smallest thing with trackable structure, and interpolated between block centres
 * so a curving front advects smoothly.
 */
class MotionField(
    val blockSize: Int,
    val blocksAcross: Int,
    val blocksDown: Int,
    private val vx: FloatArray,
    private val vy: FloatArray,
    /**
     * How well the blocks actually matched, 0..1.
     *
     * Travels with the field because a nowcast built on a weak match must not be
     * presented with the same confidence as one built on a strong one.
     */
    val confidence: Float,
) {
    init {
        require(vx.size == blocksAcross * blocksDown && vy.size == vx.size) {
            "motion field is ${blocksAcross}x$blocksDown but got ${vx.size} vectors"
        }
    }

    /**
     * The same field, believed more or less.
     *
     * Used when a second, independent estimate has something to say about
     * whether this one should be trusted - the vectors are unchanged, only the
     * weight put on them.
     */
    fun withConfidence(confidence: Float): MotionField = MotionField(
        blockSize = blockSize,
        blocksAcross = blocksAcross,
        blocksDown = blocksDown,
        vx = vx,
        vy = vy,
        confidence = confidence.coerceIn(0f, 1f),
    )

    /**
     * The average vector over the whole field.
     *
     * For comparing one estimate against another - two fields measured over
     * different spans agree or they do not, and the per-block detail is noise
     * for that question.
     */
    fun meanVector(): Motion = Motion(vx.average().toFloat(), vy.average().toFloat())

    /** The block vector, clamped at the edges so lookups never fall off. */
    fun blockAt(column: Int, row: Int): Motion {
        val c = column.coerceIn(0, blocksAcross - 1)
        val r = row.coerceIn(0, blocksDown - 1)
        return Motion(vx[r * blocksAcross + c], vy[r * blocksAcross + c])
    }

    /**
     * The flow at a pixel, interpolated between the surrounding block centres.
     *
     * Block centres sit half a block in from the grid origin, which is why the
     * half-block offset appears here rather than being folded away: getting it
     * wrong shifts the whole flow field by half a block, a bias too small to
     * look broken and large enough to matter over two hours.
     */
    fun at(x: Float, y: Float): Motion {
        val bx = x / blockSize - 0.5f
        val by = y / blockSize - 0.5f
        val x0 = floor(bx).toInt()
        val y0 = floor(by).toInt()
        val fx = bx - x0
        val fy = by - y0

        val topLeft = blockAt(x0, y0)
        val topRight = blockAt(x0 + 1, y0)
        val bottomLeft = blockAt(x0, y0 + 1)
        val bottomRight = blockAt(x0 + 1, y0 + 1)

        fun mix(a: Float, b: Float, c: Float, d: Float): Float {
            val top = a + (b - a) * fx
            val bottom = c + (d - c) * fx
            return top + (bottom - top) * fy
        }

        return Motion(
            mix(topLeft.x, topRight.x, bottomLeft.x, bottomRight.x),
            mix(topLeft.y, topRight.y, bottomLeft.y, bottomRight.y),
        )
    }

    /** The average vector, for describing the flow in one line. */
    fun mean(): Motion = Motion(vx.average().toFloat(), vy.average().toFloat())

    /**
     * Ground speed of the mean flow, in km/h.
     *
     * Needs the geometry because pixels are not a unit of distance
     * ([RadarGeometry.metresPerPixel]).
     */
    fun meanSpeedKmh(geometry: RadarGeometry): Double {
        val metres = geometry.metresPerPixel(geometry.centreLatitude())
        return mean().speed * metres * MINUTES_PER_HOUR / METRES_PER_KM
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
        const val METRES_PER_KM = 1000.0
    }
}
