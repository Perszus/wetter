package lv.bolwarra.wetter.domain.radar

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distinction this class exists to keep is between rain that was measured at
 * zero and rain that was never measured. Most of these are about that.
 */
class RadarFieldTest {

    private val at: Instant = Instant.parse("2026-09-03T12:00:00Z")
    private val geometry = RadarGeometry.ofTileBlock(7, 72, 39, 1, 1)

    private fun field(vararg rows: FloatArray): RadarField {
        val small =
            RadarGeometry(
                zoom = 7,
                originX = 0,
                originY = 0,
                width = rows[0].size,
                height = rows.size,
            )
        return RadarField(at, small, rows.reduce { a, b -> a + b })
    }

    @Test
    fun `interpolation runs between the corners`() {
        val f = field(
            floatArrayOf(0f, 10f),
            floatArrayOf(0f, 10f),
        )
        assertEquals(0f, f.sampleAt(GridPoint(0f, 0f)), 0.001f)
        assertEquals(5f, f.sampleAt(GridPoint(0.5f, 0f)), 0.001f)
        assertEquals(10f, f.sampleAt(GridPoint(1f, 0.5f)), 0.001f)
    }

    @Test
    fun `one unobserved corner makes the whole sample unobserved`() {
        // The point of the class. Averaging a coverage hole in as though it were
        // a zero turns "we cannot see" into "it is not raining", quietly, and
        // three quarters of a hole would read as a light shower.
        val f = field(
            floatArrayOf(8f, RadarField.NO_ECHO),
            floatArrayOf(8f, 8f),
        )
        assertTrue(f.sampleAt(GridPoint(0.1f, 0.1f)).isNoEcho())
        assertTrue(f.sampleAt(GridPoint(0.9f, 0.9f)).isNoEcho())
    }

    @Test
    fun `off the grid reads as unobserved, not as dry`() {
        // Advection walks trajectories off the edge as a matter of course, so
        // this has to be a defined answer rather than an exception - and the
        // answer is that we do not know, not that it is dry there.
        val f = field(floatArrayOf(5f, 5f), floatArrayOf(5f, 5f))
        assertTrue(f[-1, 0].isNoEcho())
        assertTrue(f[0, -1].isNoEcho())
        assertTrue(f[99, 0].isNoEcho())
        assertFalse(f[1, 1].isNoEcho())
    }

    @Test
    fun `a dry field is fully covered and not wet`() {
        val dry = RadarTestFields.dry(at, geometry)
        assertEquals(1f, dry.coverage(), 0.001f)
        assertEquals(0f, dry.wetFraction(), 0.001f)
    }

    @Test
    fun `coverage counts what was looked at, wetness what was found`() {
        val holed = RadarTestFields.pattern(at, geometry, holes = true)
        val expectedCoverage = 1f - RadarTestFields.HOLE_WIDTH.toFloat() / geometry.width
        assertEquals(expectedCoverage, holed.coverage(), 0.01f)

        // Wetness is a share of what was observed, so blanking part of the map
        // must not make the rest look wetter or drier than it is.
        val whole = RadarTestFields.pattern(at, geometry)
        assertEquals(whole.wetFraction(), holed.wetFraction(), 0.05f)
    }

    @Test
    fun `a place off the grid has no rate at all`() {
        val f = RadarTestFields.pattern(at, geometry)
        assertNull(f.rateAt(51.5, -0.12))
    }

    @Test
    fun `the backing array is copied, not handed out`() {
        val f = RadarTestFields.pattern(at, geometry)
        val snapshot = f.snapshot()
        snapshot[0] = 999f
        assertEquals(f[0, 0], RadarTestFields.pattern(at, geometry)[0, 0], 0.001f)
    }

    @Test
    fun `a grid that does not match its geometry is refused`() {
        val bad = runCatching { RadarField(at, geometry, FloatArray(10)) }
        assertTrue(bad.isFailure)
    }
}
