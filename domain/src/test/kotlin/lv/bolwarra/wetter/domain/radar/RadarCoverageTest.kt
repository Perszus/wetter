package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import java.time.Instant
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling "the radar saw nothing" from "there is no radar here".
 *
 * The tile source draws an empty pixel for both, and the app now leans 95% of
 * its near-term answer on that pixel. Measured on one afternoon, a nine-tile
 * block around Lagos and one over the mid-Atlantic carried 0.00% echo, against
 * 14% over Riga and 11% over London - so for most of the world the empty pixel
 * is the only pixel there is, and reading it as "dry" would have the app
 * confidently contradict a model forecasting rain in precisely the places with
 * no radar to correct it.
 */
class RadarCoverageTest {

    private val start: Instant = Instant.parse("2026-09-04T12:00:00Z")

    private val geometry = RadarTestFields.geometry(2)

    /** The place every test here asks about. */
    private val latitude = 56.95
    private val longitude = 24.11

    private val here = geometry.pointOf(latitude, longitude)!!

    private val leads = listOf(
        Duration.ofMinutes(10),
        Duration.ofMinutes(30),
        Duration.ofMinutes(60),
    )

    /**
     * A field that is empty everywhere except one blob, placed [offset] pixels
     * east of the point under test. East because the point lands near the left
     * edge of this block, and a blob pushed the other way falls off the grid
     * and stops being rain at all.
     *
     * Empty means zero, not [RadarField.NO_ECHO] - that is the whole difficulty.
     * A source that marked its own coverage holes would need none of this.
     */
    private fun blobAt(at: Instant, offset: Float, shift: Float = 0f): RadarField {
        val centreX = here.x + offset + shift
        val centreY = here.y
        val values = FloatArray(geometry.width * geometry.height)
        for (y in 0 until geometry.height) {
            for (x in 0 until geometry.width) {
                val distance = hypot(x - centreX, y - centreY)
                if (distance < RADIUS) {
                    values[y * geometry.width + x] = PEAK * (1f - distance / RADIUS)
                }
            }
        }
        return RadarField(at, geometry, values)
    }

    private fun nowcastOf(offset: Float): RadarNowcast? = RadarNowcaster.nowcast(
        listOf(
            blobAt(start, offset),
            blobAt(start.plus(Duration.ofMinutes(10)), offset, shift = 20f),
        ),
        leads,
    )

    @Test
    fun `an empty sky with rain nowhere near it is not an observation of dry`() {
        // The Lagos case. There is echo in the block - enough to estimate motion
        // and build a projection - but none of it is anywhere near the point, so
        // the composite says nothing about this place and the app must not
        // pretend otherwise.
        val nowcast = nowcastOf(offset = FAR)!!

        assertTrue(
            "a point with no echo within reach should get no radar opinion",
            nowcast.seriesAt(latitude, longitude).isEmpty(),
        )
    }

    @Test
    fun `an empty sky with rain beside it is a real observation of dry`() {
        // The Riga case, and the one worth keeping: a shower passing a few tens
        // of kilometres away is proof the radar is watching this area, so a dry
        // reading here is evidence rather than absence.
        val nowcast = nowcastOf(offset = NEAR)!!
        val series = nowcast.seriesAt(latitude, longitude)

        assertTrue("a watched point should still get an answer", series.isNotEmpty())
    }

    @Test
    fun `rain arriving from beyond the neighbourhood still gets through`() {
        // The failure the first version of this had: checking only the nearest
        // frame would silence a shower that has not reached the neighbourhood
        // yet, which is the single thing a nowcast exists to say.
        val approaching = RadarNowcaster.nowcast(
            listOf(
                blobAt(start, offset = FAR),
                blobAt(start.plus(Duration.ofMinutes(10)), offset = FAR, shift = -60f),
            ),
            listOf(Duration.ofMinutes(30), Duration.ofMinutes(45), Duration.ofMinutes(60)),
        )!!

        val series = approaching.seriesAt(latitude, longitude)
        assertTrue("approaching rain must not be silenced", series.isNotEmpty())
        assertTrue("and it must actually arrive", series.any { it.millimetresPerHour > 0f })
    }

    @Test
    fun `a sweep with nothing in it anywhere says nothing`() {
        // No echo in the whole composite is what a country with no radar looks
        // like, and it must not read as a nationwide forecast of no rain.
        val empty = RadarNowcaster.nowcast(
            listOf(
                RadarTestFields.dry(start, geometry),
                RadarTestFields.dry(start.plus(Duration.ofMinutes(10)), geometry),
            ),
            leads,
        )

        // Nothing to track means no projection at all, which is the same answer
        // reached one step earlier.
        assertEquals(null, empty)
    }

    @Test
    fun `the neighbourhood is a neighbourhood, not the whole grid`() {
        val field = blobAt(start, offset = FAR)

        assertTrue(field.hasEchoNear(latitude, longitude, radius = 300))
        assertTrue(!field.hasEchoNear(latitude, longitude, radius = 40))
    }

    private companion object {
        const val RADIUS = 34f
        const val PEAK = 8f

        /** Comfortably outside one motion block. */
        const val FAR = 200f

        /** Comfortably inside it, and not touching the point itself. */
        const val NEAR = 50f
    }
}
