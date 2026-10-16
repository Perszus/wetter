package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windowed projection is an optimisation, not a different forecast: what it
 * says about a place must be what the whole-grid projection says about it.
 */
class NowcastAroundTest {

    private val start: Instant = Instant.parse("2026-10-15T06:00:00Z")
    private val leads = (0..6).map { Duration.ofMinutes(it * 5L) } +
        (4..12).map { Duration.ofMinutes(it * 10L) }

    /** Rain cells drifting east across the app's real grid size around Riga. */
    private fun frames(seed: Long): List<RadarField> {
        val geometry = RadarGeometry.blockAround(56.94, 24.10, 7, 3)
        val rnd = java.util.Random(seed)
        val cells = List(30) {
            Triple(rnd.nextInt(geometry.width), rnd.nextInt(geometry.height), 6 + rnd.nextInt(28))
        }
        return (0..2).map { i ->
            val v = FloatArray(geometry.width * geometry.height)
            for (y in 0 until geometry.height) {
                for (x in 0 until geometry.width) {
                    var r = 0.0
                    for ((cx, cy, radius) in cells) {
                        val dx = (x - cx - i * 5).toDouble()
                        val dy = (y - cy - i * 2).toDouble()
                        r += 9 * (1 + 0.2 * i) * exp(-(dx * dx + dy * dy) / (radius * radius))
                    }
                    v[y * geometry.width + x] = if (r < 0.02) 0f else r.toFloat()
                }
            }
            RadarField(start.plusSeconds(600L * i), geometry, v)
        }
    }

    private val places = listOf(
        56.94 to 24.10, // the centre
        57.40 to 23.10, // well off-centre
        56.10 to 25.60, // near a corner, where the window is clipped
    )

    @Test
    fun saysWhatTheWholeGridSaysAboutTheSamePlace() {
        for (seed in 1L..3L) {
            val sweeps = frames(seed)
            val whole = RadarNowcaster.nowcast(sweeps, leads)!!
            for ((lat, lon) in places) {
                val windowed = RadarNowcaster.nowcastAround(sweeps, leads, lat, lon)!!
                val a = whole.seriesAt(lat, lon)
                val b = windowed.seriesAt(lat, lon)
                assertEquals("seed $seed at $lat,$lon", a.size, b.size)
                a.zip(b).forEach { (x, y) ->
                    assertEquals(x.at, y.at)
                    assertEquals(x.lead, y.lead)
                    assertEquals(
                        "seed $seed at $lat,$lon ${x.lead}",
                        x.millimetresPerHour,
                        y.millimetresPerHour,
                        1e-3f,
                    )
                    assertEquals(x.confidence, y.confidence, 0f)
                    assertEquals(x.motionQuality, y.motionQuality, 0f)
                }
            }
        }
    }

    @Test
    fun isMuchSmallerThanTheGrid() {
        val windowed = RadarNowcaster.nowcastAround(frames(1), leads, 56.94, 24.10)!!
        val field = windowed.steps.first().field
        assertTrue(
            "window ${field.width}x${field.height}",
            field.width * field.height < 768 * 768 / 20,
        )
    }

    @Test
    fun hasNothingToSayAboutAPlaceOffTheGrid() {
        assertNull(RadarNowcaster.nowcastAround(frames(1), leads, 40.0, 10.0))
    }
}
