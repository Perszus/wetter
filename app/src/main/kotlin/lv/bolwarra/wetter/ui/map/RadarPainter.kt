package lv.bolwarra.wetter.ui.map

import android.graphics.Bitmap
import kotlin.math.roundToInt
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.radar.RadarField
import lv.bolwarra.wetter.domain.radar.RadarGeometry
import lv.bolwarra.wetter.domain.radar.isNoEcho

/**
 * A rain field as a picture: purple where it rains, running to red where the
 * rain concentrates.
 *
 * Not the source's colours. The tiles arrive in somebody else's palette, a
 * rainbow whose steps nobody can read without a legend. Two hues and the road
 * between them say the one thing the map is for - where it rains, and where it
 * rains hardest - so the eye goes to the red without being told to.
 *
 * **Colour and opacity follow the chart's height.** Both run through
 * [RainCurveBands], so a cell drawn at the moderate step is moderate on the
 * curve too - the one thing the two surfaces must never disagree about
 * (docs/design-principles.md, "The rain axis").
 *
 * ### Smoothed for the eye, not for the numbers
 *
 * The field is a grid of kilometre pixels, and drawn as it is it reads as a
 * mosaic - the grid, not the rain. A small Gaussian pass softens that grid
 * before the colours are chosen. It is a picture-only step: nothing reads rates
 * back out of the bitmap, and the kernel is a pixel wide, well inside what the
 * composite itself resolves, so it removes the staircase without inventing
 * structure.
 *
 * ### Where the picture runs out, it fades
 *
 * A projection carries rain in from upwind, and at the upwind edge of the grid
 * there is nothing to carry: those pixels are unobserved. They used to be
 * veiled in the plate colour, and because motion is estimated in blocks the
 * edge of that veil was a staircase - dark blocks marching in from the side as
 * the loop played, with the rain beside them cut off along the same steps.
 *
 * So the edge of what is known is feathered instead. An observed mask is
 * blurred over [FEATHER_PX] and the rain's opacity follows it, reaching nothing
 * exactly at the boundary. Nothing is drawn where nothing was seen, and the
 * rain thins out towards that ground rather than stopping at a wall - which is
 * also the honest picture: confidence in a pixel falls the nearer its
 * trajectory came to the edge of what was observed.
 */
internal object RadarPainter {

    /**
     * The part of [field] under [view], which must lie inside its grid.
     *
     * Smoothing and the fade are worked out over the whole field and only then
     * cropped, so the picture's edge is not treated as the edge of the data.
     */
    fun paint(field: RadarField, view: RadarGeometry = field.geometry): Bitmap =
        Bitmap.createBitmap(pixels(field, view), view.width, view.height, Bitmap.Config.ARGB_8888)

    /** The picture as packed ARGB, separated from [Bitmap] so it can be checked on the JVM. */
    internal fun pixels(field: RadarField, view: RadarGeometry = field.geometry): IntArray {
        val width = field.width
        val height = field.height
        val left = view.originX - field.geometry.originX
        val top = view.originY - field.geometry.originY
        require(
            left >= 0 && top >= 0 && left + view.width <= width && top + view.height <= height,
        ) {
            "view lies outside the field"
        }
        val raw = field.snapshot()
        val smooth = smoothed(raw, width, height)
        val reach = feathered(raw, width, height)
        return IntArray(view.width * view.height) { out ->
            val index = (top + out / view.width) * width + left + out % view.width
            val rate = smooth[index]
            val fade = reach[index]
            when {
                raw[index].isNoEcho() || fade <= 0f -> 0
                rate < RadarField.TRACE_MM_PER_HOUR -> 0
                else -> faded(colourOf(rate), fade)
            }
        }
    }

    private fun faded(argb: Int, by: Float): Int {
        if (by >= 1f) return argb
        val alpha = (((argb ushr 24) and 0xFF) * by).roundToInt()
        return (alpha shl 24) or (argb and 0x00FFFFFF)
    }

    /**
     * How far inside the observed ground each pixel sits, 0 at its edge and 1 at
     * [FEATHER_PX] or more. A fully observed field is 1 everywhere, including at
     * the grid's own border: past the grid is off the picture, not unobserved.
     */
    private fun feathered(raw: FloatArray, width: Int, height: Int): FloatArray {
        if (raw.none { it.isNoEcho() }) return FloatArray(raw.size) { 1f }
        var mask = FloatArray(raw.size) { if (raw[it].isNoEcho()) 0f else 1f }
        repeat(FEATHER_PASSES) {
            mask = boxBlur(mask, width, height, horizontal = true)
            mask = boxBlur(mask, width, height, horizontal = false)
        }
        // Two measures, and the smaller wins. The blurred mask rounds the steps
        // off the edge's shape, but at the inside corner of a step it is still
        // over a half beside unobserved ground, so on its own the rain there
        // stopped dead. Distance to the nearest unobserved pixel is nothing at
        // every boundary pixel and climbs at a fixed rate, which is what
        // guarantees the fade finishes exactly at the edge.
        val distance = distanceToUnobserved(raw, width, height)
        return FloatArray(mask.size) {
            val rounded = ((mask[it] - 0.5f) * 2f).coerceIn(0f, 1f)
            val near = smoothStep(distance[it] / FEATHER_PX)
            minOf(rounded, near)
        }
    }

    /**
     * Pixels to the nearest unobserved pixel, by a two-pass chamfer - close
     * enough to Euclidean that the fade's contours do not come out octagonal.
     */
    private fun distanceToUnobserved(raw: FloatArray, width: Int, height: Int): FloatArray {
        val far = Float.MAX_VALUE / 2f
        val d = FloatArray(raw.size) { if (raw[it].isNoEcho()) 0f else far }
        fun relax(x: Int, y: Int, nx: Int, ny: Int, cost: Float) {
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) return
            val candidate = d[ny * width + nx] + cost
            if (candidate < d[y * width + x]) d[y * width + x] = candidate
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                relax(x, y, x - 1, y, 1f)
                relax(x, y, x - 1, y - 1, DIAGONAL)
                relax(x, y, x, y - 1, 1f)
                relax(x, y, x + 1, y - 1, DIAGONAL)
            }
        }
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                relax(x, y, x + 1, y, 1f)
                relax(x, y, x + 1, y + 1, DIAGONAL)
                relax(x, y, x, y + 1, 1f)
                relax(x, y, x - 1, y + 1, DIAGONAL)
            }
        }
        return d
    }

    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** A running-sum box blur, normalised by what is actually inside the grid. */
    private fun boxBlur(
        values: FloatArray,
        width: Int,
        height: Int,
        horizontal: Boolean,
    ): FloatArray {
        val out = FloatArray(values.size)
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val radius = FEATHER_PX / FEATHER_PASSES
        for (line in 0 until lines) {
            fun at(i: Int) = if (horizontal) line * width + i else i * width + line
            var sum = 0f
            var count = 0
            for (i in 0..minOf(radius, length - 1)) {
                sum += values[at(i)]
                count++
            }
            for (i in 0 until length) {
                out[at(i)] = sum / count
                val leaving = i - radius
                val entering = i + radius + 1
                if (leaving >= 0) {
                    sum -= values[at(leaving)]
                    count--
                }
                if (entering < length) {
                    sum += values[at(entering)]
                    count++
                }
            }
        }
        return out
    }

    /** Purple, through magenta, to red, as the rate climbs the band axis. */
    internal fun colourOf(rate: Float): Int {
        val height = RainCurveBands.heightFraction(rate).coerceIn(0f, 1f)
        val (from, to, t) = if (height < MIDDLE) {
            Triple(LIGHT, MIDDLE_HUE, height / MIDDLE)
        } else {
            Triple(MIDDLE_HUE, HEAVY, (height - MIDDLE) / (1f - MIDDLE))
        }
        val alpha = ((FAINTEST + (STRONGEST - FAINTEST) * height) * 255f).roundToInt()
        return (alpha.coerceIn(0, 255) shl 24) or mix(from, to, t)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).roundToInt().coerceIn(0, 255) shl shift
        }
        return channel(16) or channel(8) or channel(0)
    }

    /**
     * A separable [1 2 1] pass, twice - close to a Gaussian of about a pixel.
     *
     * Unobserved pixels neither contribute nor receive: a coverage hole must not
     * bleed into observed ground as if it were dry, or pick up rain from beside
     * it and look observed.
     */
    private fun smoothed(values: FloatArray, width: Int, height: Int): FloatArray {
        var current = values
        repeat(PASSES) {
            current = pass(current, width, height, dx = 1, dy = 0)
            current = pass(current, width, height, dx = 0, dy = 1)
        }
        return current
    }

    private fun pass(values: FloatArray, width: Int, height: Int, dx: Int, dy: Int): FloatArray {
        val out = FloatArray(values.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val centre = values[index]
                if (centre.isNoEcho()) {
                    out[index] = centre
                    continue
                }
                var sum = centre * 2f
                var weight = 2f
                for (side in intArrayOf(-1, 1)) {
                    val nx = x + dx * side
                    val ny = y + dy * side
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val neighbour = values[ny * width + nx]
                    if (neighbour.isNoEcho()) continue
                    sum += neighbour
                    weight += 1f
                }
                out[index] = sum / weight
            }
        }
        return out
    }

    private const val PASSES = 2

    /** About ten kilometres at the zooms used: wide enough to hide the steps. */
    private const val FEATHER_PX = 18
    private const val FEATHER_PASSES = 3
    private const val DIAGONAL = 1.4142135f

    /** Where on the band axis the ramp passes through its middle hue. */
    private const val MIDDLE = 0.5f

    /** The ramp's own colours, for the controls drawn beside the picture. */
    val lightInk = androidx.compose.ui.graphics.Color(0xFF8A5CF6)

    private const val LIGHT = 0x8A5CF6
    private const val MIDDLE_HUE = 0xD946A8
    private const val HEAVY = 0xEF3B36

    /** Enough that a trace reads as rain on a busy basemap. */
    private const val FAINTEST = 0.42f

    /** Short of opaque, so the streets under a downpour can still be found. */
    private const val STRONGEST = 0.92f
}
