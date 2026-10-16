package lv.bolwarra.wetter.domain.radar

import java.time.Instant
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One radar sweep, as rainfall rate over a grid.
 *
 * Millimetres per hour rather than reflectivity, because converting from dBZ is
 * a property of the source - it depends on the radar, on the assumed drop-size
 * distribution, and on whether the echo is rain, snow or hail - and so belongs
 * in the adapter that knows which radar it is talking to. By the time a field
 * arrives here it is a physical quantity, and everything in this package can
 * treat every source alike.
 *
 * ### Absent is not zero
 *
 * [NO_ECHO] means the radar did not look: beyond range, blocked by terrain, or
 * outside the composite altogether. Zero means it looked and found nothing
 * falling. Collapsing the two would let a coverage hole drift across the map as
 * a patch of confidently forecast dry weather, which is the worst failure this
 * app has - not a wrong number, but a confident one.
 */
class RadarField(
    val at: Instant,
    val geometry: RadarGeometry,
    /** Row-major millimetres per hour, or [NO_ECHO] where nothing was observed. */
    values: FloatArray,
) {
    init {
        require(values.size == geometry.width * geometry.height) {
            "grid is ${geometry.width}x${geometry.height}, got ${values.size} values"
        }
    }

    /**
     * Held as hundredths of a millimetre an hour in sixteen bits, not as floats.
     *
     * A projection keeps sixteen of these grids and the map another hour of
     * sweeps, each a million pixels, and twice over while a newer projection is
     * built beside the old one. As floats that came within a few megabytes of
     * an ordinary phone's heap limit. A hundredth of a millimetre is far finer
     * than rates read back out of a colour scale can claim, and the top of the
     * range, 655 mm/h, is several times anything the scale reaches.
     */
    private val stored = ShortArray(values.size) { encode(values[it]) }

    private fun value(index: Int): Float = decode(stored[index])

    val width: Int get() = geometry.width
    val height: Int get() = geometry.height

    /** Off-grid reads as unobserved rather than throwing: advection walks off edges. */
    operator fun get(x: Int, y: Int): Float =
        if (x < 0 || y < 0 || x >= width || y >= height) NO_ECHO else value(y * width + x)

    /** A copy of the backing array, for bulk work. */
    fun snapshot(): FloatArray = FloatArray(stored.size) { value(it) }

    /**
     * Rate at a fractional position, bilinearly interpolated.
     *
     * Any of the four neighbours being unobserved makes the whole sample
     * unobserved, rather than dragging an average down towards zero. A sample
     * that is three quarters coverage hole is not a light shower.
     */
    fun sampleAt(point: GridPoint): Float {
        val x0 = floor(point.x).toInt()
        val y0 = floor(point.y).toInt()
        val fx = point.x - x0
        val fy = point.y - y0

        // Only the corners carrying weight are consulted. Landing exactly on a
        // column gives the one to its right a weight of zero, and demanding it
        // anyway made the last row and column of every field unsamplable - a
        // coverage hole reported around the whole rim of a fully observed grid.
        val needsRight = fx > 0f
        val needsBelow = fy > 0f

        val topLeft = get(x0, y0)
        val topRight = if (needsRight) get(x0 + 1, y0) else topLeft
        val bottomLeft = if (needsBelow) get(x0, y0 + 1) else topLeft
        val bottomRight = when {
            needsRight && needsBelow -> get(x0 + 1, y0 + 1)
            needsRight -> topRight
            needsBelow -> bottomLeft
            else -> topLeft
        }
        if (topLeft.isNoEcho() ||
            topRight.isNoEcho() ||
            bottomLeft.isNoEcho() ||
            bottomRight.isNoEcho()
        ) {
            return NO_ECHO
        }

        val top = topLeft + (topRight - topLeft) * fx
        val bottom = bottomLeft + (bottomRight - bottomLeft) * fx
        return top + (bottom - top) * fy
    }

    /** Rate at a place, or null when it is off the grid or unobserved. */
    fun rateAt(latitude: Double, longitude: Double): Float? {
        val point = geometry.pointOf(latitude, longitude) ?: return null
        return sampleAt(point).takeUnless { it.isNoEcho() }
    }

    /**
     * Whether anything at all is falling within [radius] pixels of a point.
     *
     * This is how the engine tells "the radar looked and saw nothing" from "the
     * radar is not looking here", on a source that draws both as an empty pixel.
     *
     * The distinction cannot be made from one pixel and does not need to be. A
     * dry pixel with rain a few tens of kilometres away is a real observation of
     * dry - the radar is plainly watching this area and reporting nothing here,
     * which is exactly the case worth having. A dry pixel with nothing anywhere
     * near it says only that this part of the composite is empty, and an empty
     * composite is what a country with no radar looks like.
     *
     * Reading the second case as dry is the failure this app can least afford:
     * not a wrong number but a confident one, delivered to somebody standing in
     * the rain in a place the composite has never covered.
     */
    fun hasEchoNear(latitude: Double, longitude: Double, radius: Int): Boolean {
        val point = geometry.pointOf(latitude, longitude) ?: return false
        val centreX = point.x.toInt()
        val centreY = point.y.toInt()

        for (y in (centreY - radius)..(centreY + radius)) {
            if (y < 0 || y >= height) continue
            for (x in (centreX - radius)..(centreX + radius)) {
                if (x < 0 || x >= width) continue
                val value = value(y * width + x)
                if (!value.isNoEcho() && value > 0f) return true
            }
        }
        return false
    }

    /** The share of the grid the radar actually saw, 0..1. */
    fun coverage(): Float {
        if (stored.isEmpty()) return 0f
        return stored.count { it != UNOBSERVED }.toFloat() / stored.size
    }

    /** The share of the grid with rain in it, counting only what was observed. */
    fun wetFraction(): Float {
        val observed = stored.count { it != UNOBSERVED }
        if (observed == 0) return 0f
        return stored.count { it != UNOBSERVED && decode(it) >= TRACE_MM_PER_HOUR }.toFloat() /
            observed
    }

    companion object {
        /** The stored form of [NO_ECHO]: all ones, which no rate encodes to. */
        private const val UNOBSERVED: Short = -1
        private const val HUNDREDTHS = 100f
        private const val LARGEST = 0xFFFE

        private fun encode(rate: Float): Short = if (rate.isNoEcho()) {
            UNOBSERVED
        } else {
            (rate * HUNDREDTHS).roundToInt().coerceIn(0, LARGEST).toShort()
        }

        private fun decode(stored: Short): Float =
            if (stored == UNOBSERVED) NO_ECHO else (stored.toInt() and 0xFFFF) / HUNDREDTHS

        /** Not observed. Deliberately distinct from 0.0, which is an observation. */
        const val NO_ECHO = Float.NaN

        /**
         * Below this a radar echo is not worth calling rain. Matches the domain's
         * own trace threshold so the radar and the forecast agree on the word.
         */
        const val TRACE_MM_PER_HOUR = 0.1f
    }
}

fun Float.isNoEcho(): Boolean = isNaN()
