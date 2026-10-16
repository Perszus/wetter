package lv.bolwarra.wetter.ui.map

import java.time.Instant
import kotlin.math.abs
import lv.bolwarra.wetter.domain.radar.RadarField
import lv.bolwarra.wetter.domain.radar.RadarGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the map draws where the projection runs out of observed ground.
 *
 * The case is the one the loop produced: a field of steady rain whose upwind
 * side has been advected off the grid in 64-pixel steps. That edge was drawn as
 * a dark staircase, with the rain beside it cut off along the same steps.
 */
class RadarPainterTest {

    private val geometry = RadarGeometry.ofTileBlock(7, 72, 39, 1, 1)
    private val size = geometry.width

    /** Rain everywhere, unobserved to the right of a staircase edge. */
    private fun steppedField(): RadarField {
        val values = FloatArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val edge = 160 + (y / 64) * 20
            if (x >= edge) RadarField.NO_ECHO else 3f
        }
        return RadarField(Instant.EPOCH, geometry, values)
    }

    private fun alphaOf(argb: Int) = (argb ushr 24) and 0xFF

    @Test
    fun `nothing is drawn where nothing was observed`() {
        val field = steppedField()
        val pixels = RadarPainter.pixels(field)
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (field[x, y].isNaN()) assertEquals("($x, $y)", 0, pixels[y * size + x])
            }
        }
    }

    @Test
    fun `rain fades out towards the edge instead of stopping at it`() {
        val field = steppedField()
        val pixels = RadarPainter.pixels(field)
        val full = alphaOf(RadarPainter.colourOf(3f))

        for (y in 0 until size) {
            val edge = (0 until size).first { field[it, y].isNaN() }
            // The last observed pixel before the edge is all but clear.
            assertTrue(
                "row $y: ${alphaOf(pixels[y * size + edge - 1])} at the edge",
                alphaOf(pixels[y * size + edge - 1]) <= full / 8,
            )
            // Well inside, the rain is at full strength.
            assertEquals("row $y", full, alphaOf(pixels[y * size + edge - 40]))
        }
    }

    @Test
    fun `no hard edge anywhere, across the steps or along them`() {
        val pixels = RadarPainter.pixels(steppedField())
        val full = alphaOf(RadarPainter.colourOf(3f))
        // A feather of eighteen pixels moves at most about a tenth of the way
        // per pixel. A cut-off edge jumps the whole way in one.
        val steepest = full / 6
        for (y in 0 until size) {
            for (x in 0 until size) {
                val here = alphaOf(pixels[y * size + x])
                if (x + 1 < size) {
                    assertTrue(
                        "across at ($x, $y)",
                        abs(here - alphaOf(pixels[y * size + x + 1])) <= steepest,
                    )
                }
                if (y + 1 < size) {
                    assertTrue(
                        "down at ($x, $y)",
                        abs(here - alphaOf(pixels[(y + 1) * size + x])) <= steepest,
                    )
                }
            }
        }
    }

    @Test
    fun `a fully observed field is not faded at the border of the grid`() {
        val values = FloatArray(size * size) { 3f }
        val pixels = RadarPainter.pixels(RadarField(Instant.EPOCH, geometry, values))
        val full = alphaOf(RadarPainter.colourOf(3f))
        assertEquals(full, alphaOf(pixels[0]))
        assertEquals(full, alphaOf(pixels[size * size - 1]))
    }

    @Test
    fun `the map shows the middle of the grid, with margin on every side`() {
        val block = RadarGeometry.blockAround(56.95, 24.11, 7, 4)
        val view = viewOf(block)
        val margin = RadarGeometry.TILE_SIZE / 2
        assertEquals(3 * RadarGeometry.TILE_SIZE, view.width)
        assertEquals(block.originX + margin, view.originX)
        assertEquals(block.originY + margin, view.originY)
        // And the place is on screen.
        assertTrue(view.pointOf(56.95, 24.11) != null)
    }

    @Test
    fun `a cropped picture is the matching part of the whole`() {
        val block = RadarGeometry.blockAround(56.95, 24.11, 7, 4)
        val values = FloatArray(block.width * block.height) { index ->
            val x = index % block.width
            if (x in 300..420) 4f else 0f
        }
        val field = RadarField(Instant.EPOCH, block, values)
        val view = viewOf(block)
        val whole = RadarPainter.pixels(field)
        val cropped = RadarPainter.pixels(field, view)
        val dx = view.originX - block.originX
        val dy = view.originY - block.originY
        for (y in 0 until view.height step 37) {
            for (x in 0 until view.width step 29) {
                assertEquals(whole[(y + dy) * block.width + x + dx], cropped[y * view.width + x])
            }
        }
    }
}
