package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the projection puts the rain where the rain is going, and whether it
 * admits how little it knows by the end.
 */
class RadarNowcasterTest {

    private val start: Instant = Instant.parse("2026-09-03T12:00:00Z")

    /**
     * Deliberately larger than one tile. At 2 px/min a feature travels 240 px in
     * two hours, so on a single 256 px tile everything being tracked runs off
     * the edge before the last lead and the test ends up measuring the grid
     * boundary rather than the projection.
     */
    private val geometry = RadarTestFields.geometry(2)

    /** Two sweeps ten minutes apart, the second shifted right by 20 px. */
    private fun movingEast(scale: Float = 1f) = listOf(
        RadarTestFields.pattern(start, geometry),
        RadarTestFields.pattern(
            start.plus(Duration.ofMinutes(10)),
            geometry,
            shiftX = 20f,
            scale = scale,
        ),
    )

    private val leads = listOf(
        Duration.ofMinutes(10),
        Duration.ofMinutes(30),
        Duration.ofMinutes(60),
        Duration.ofMinutes(120),
    )

    /** Where the heaviest rain sits, in grid pixels. */
    private fun peakX(field: RadarField): Int {
        var best = -1f
        var bestX = -1
        for (x in 0 until field.width) {
            for (y in 0 until field.height) {
                val v = field[x, y]
                if (!v.isNoEcho() && v > best) {
                    best = v
                    bestX = x
                }
            }
        }
        return bestX
    }

    @Test
    fun `rain is carried on in the direction it was going`() {
        val nowcast = RadarNowcaster.nowcast(movingEast(), leads)
        assertNotNull(nowcast)

        val origin = peakX(movingEast().last())
        val tenOn = peakX(nowcast!!.steps.first().field)

        // Moving 2 px/min, ten minutes on the peak should be about 20 px right.
        assertEquals((origin + 20).toFloat(), tenOn.toFloat(), 3f)
    }

    @Test
    fun `further ahead means further along, proportionally`() {
        val nowcast = RadarNowcaster.nowcast(movingEast(), leads)!!
        val origin = peakX(movingEast().last())

        val at30 = peakX(nowcast.steps[1].field)
        val at60 = peakX(nowcast.steps[2].field)

        assertEquals((origin + 60).toFloat(), at30.toFloat(), 5f)
        assertEquals((origin + 120).toFloat(), at60.toFloat(), 6f)
    }

    @Test
    fun `confidence falls away with lead time`() {
        val nowcast = RadarNowcaster.nowcast(movingEast(), leads)!!
        val confidences = nowcast.steps.map { it.confidence }

        confidences.zipWithNext { near, far ->
            assertTrue("confidence rose with lead: $near then $far", far < near)
        }
    }

    @Test
    fun `radar holds the near term and gives way around two hours`() {
        // The shape the whole design turns on. Radar is looking at rain that
        // exists, so for the first hour it should be carrying nearly the whole
        // answer; by two hours the extrapolation has lost the plot and the model
        // has to be taking over.
        //
        // An earlier version decayed exponentially from a raw sharpness reading
        // and gave radar about a third of the answer for the current minute,
        // falling to two percent by two hours. That is the opposite of what
        // radar is for.
        val good = RadarNowcaster.GOOD_MATCH_SHARPNESS

        assertTrue(
            "now: ${RadarNowcaster.confidenceAt(0.0, good)}",
            RadarNowcaster.confidenceAt(0.0, good) > 0.95f,
        )
        assertTrue(RadarNowcaster.confidenceAt(30.0, good) > 0.85f)
        assertTrue(RadarNowcaster.confidenceAt(60.0, good) > 0.70f)

        // Crossing over just under two hours: still leading at ninety minutes,
        // beaten by the model past that.
        assertTrue(RadarNowcaster.confidenceAt(90.0, good) > 0.5f)
        assertTrue(RadarNowcaster.confidenceAt(150.0, good) < 0.5f)

        // And genuinely spent by the time the model is all there is.
        assertTrue(RadarNowcaster.confidenceAt(360.0, good) < 0.1f)
    }

    @Test
    fun `the present moment is trusted even when nothing can be tracked`() {
        // The distinction that makes the near term work. A flat, featureless
        // rain field gives a poor motion estimate - measured at 0.16 over London
        // - but the sweep still shows rain that is falling. The reading needs no
        // motion estimate at all; only the projection does.
        val featureless = 0.16f

        assertTrue(
            "now: ${RadarNowcaster.confidenceAt(0.0, featureless)}",
            RadarNowcaster.confidenceAt(0.0, featureless) > 0.95f,
        )
        // But it must be discounted quickly for anything beyond the present.
        assertTrue(RadarNowcaster.confidenceAt(60.0, featureless) < 0.4f)
    }

    @Test
    fun `sharpness is normalised against what a good match really measures`() {
        // Sharpness is peak contrast, not a probability: a good match on real
        // precipitation reads about 0.3 and never approaches 1. Using it raw was
        // a units error that capped radar at a third of the answer.
        assertEquals(1.0, RadarNowcaster.matchQualityOf(0.30f), 0.001)
        assertEquals(1.0, RadarNowcaster.matchQualityOf(0.38f), 0.001)
        assertEquals(0.0, RadarNowcaster.matchQualityOf(0.10f), 0.001)
        assertEquals(0.0, RadarNowcaster.matchQualityOf(0.0f), 0.001)
        // Dublin's, the case that came out fifty degrees wrong.
        assertEquals(0.725, RadarNowcaster.matchQualityOf(0.245f), 0.01)
    }

    @Test
    fun `a poorer match is trusted less at every lead that needs it`() {
        val good = RadarNowcaster.confidenceAt(60.0, 0.33f)
        val poor = RadarNowcaster.confidenceAt(60.0, 0.20f)
        assertTrue("good $good should beat poor $poor", good > poor)
    }

    @Test
    fun `now is part of the series, not just the projections`() {
        // Lead zero is the latest sweep itself - observed, not predicted - and
        // is the most reliable number the package produces, so it belongs in the
        // timeline rather than being filtered out of it.
        val withNow = RadarNowcaster.nowcast(
            movingEast(),
            listOf(Duration.ZERO, Duration.ofMinutes(10)),
        )!!
        assertEquals(2, withNow.steps.size)
        assertEquals(Duration.ZERO, withNow.steps.first().lead)
        assertEquals(withNow.issuedAt, withNow.steps.first().at)
        assertTrue(withNow.steps.first().confidence > withNow.steps.last().confidence)
    }

    @Test
    fun `no projection ever forecasts negative rain`() {
        // The decay trend extrapolates downward and will happily go through zero
        // if nothing stops it.
        val decaying = listOf(
            RadarTestFields.pattern(start, geometry, scale = 1f),
            RadarTestFields.pattern(
                start.plus(Duration.ofMinutes(10)),
                geometry,
                shiftX = 20f,
                scale = 0.3f,
            ),
        )
        val nowcast = RadarNowcaster.nowcast(decaying, leads)!!

        nowcast.steps.forEach { step ->
            val values = step.field.snapshot().filter { !it.isNoEcho() }
            assertTrue("negative rain at ${step.lead}", values.all { it >= 0f })
        }
    }

    @Test
    fun `a decaying shower is projected weaker, a growing one stronger`() {
        val peakOf = { field: RadarField ->
            field.snapshot().filter { !it.isNoEcho() }.maxOrNull() ?: 0f
        }
        val decaying = RadarNowcaster.nowcast(
            listOf(
                RadarTestFields.pattern(start, geometry, scale = 1f),
                RadarTestFields.pattern(
                    start.plus(Duration.ofMinutes(10)),
                    geometry,
                    shiftX = 20f,
                    scale = 0.4f,
                ),
            ),
            listOf(Duration.ofMinutes(30)),
        )!!
        val growing = RadarNowcaster.nowcast(
            listOf(
                RadarTestFields.pattern(start, geometry, scale = 0.4f),
                RadarTestFields.pattern(
                    start.plus(Duration.ofMinutes(10)),
                    geometry,
                    shiftX = 20f,
                    scale = 1f,
                ),
            ),
            listOf(Duration.ofMinutes(30)),
        )!!

        val fromDecaying = peakOf(decaying.steps.first().field)
        val fromGrowing = peakOf(growing.steps.first().field)
        assertTrue(
            "decaying $fromDecaying should be under growing $fromGrowing",
            fromDecaying < fromGrowing,
        )
    }

    @Test
    fun `growth is capped rather than compounding without limit`() {
        val growing = listOf(
            RadarTestFields.pattern(start, geometry, scale = 0.3f),
            RadarTestFields.pattern(
                start.plus(Duration.ofMinutes(10)),
                geometry,
                shiftX = 20f,
                scale = 1f,
            ),
        )
        val nowcast = RadarNowcaster.nowcast(growing, listOf(Duration.ofMinutes(120)))!!
        val peak = nowcast.steps.first().field.snapshot().filter { !it.isNoEcho() }.max()

        // The pattern tops out at 12 mm/h. A trend applied for two hours without
        // a ceiling would run to hundreds; the saturation keeps it in the region
        // of weather that actually happens.
        assertTrue("runaway growth to $peak mm/h", peak < 60f)
    }

    @Test
    fun `the projected field has no torn holes where the flow spreads`() {
        // The reason trajectories are traced backwards. Forward scatter leaves
        // output pixels that no source pixel happened to land on, and they read
        // as dry gaps nobody forecast.
        val nowcast = RadarNowcaster.nowcast(movingEast(), listOf(Duration.ofMinutes(30)))!!
        val field = nowcast.steps.first().field

        // Away from the edge every pixel must have an answer - a number or an
        // honest "unobserved", never an accidental zero surrounded by rain.
        var torn = 0
        for (y in 60 until field.height - 60) {
            for (x in 60 until field.width - 60) {
                val here = field[x, y]
                if (here.isNoEcho() || here > 0f) continue
                val neighbours = listOf(
                    field[x - 1, y],
                    field[x + 1, y],
                    field[x, y - 1],
                    field[x, y + 1],
                )
                if (neighbours.all { !it.isNoEcho() && it > 1f }) torn++
            }
        }
        assertEquals("found $torn torn pixels", 0, torn)
    }

    @Test
    fun `a place well inside the flow gets every step`() {
        // Far enough east that even the two-hour trajectory, traced back 240 px
        // upwind, still lands on the grid.
        val nowcast = RadarNowcaster.nowcast(movingEast(), leads)!!
        val here = nowcast.seriesAt(56.95, 26.9)

        assertEquals(leads.size, here.size)
        assertTrue(here.zipWithNext().all { (a, b) -> a.at < b.at })
        assertTrue(here.zipWithNext().all { (a, b) -> b.confidence < a.confidence })
    }

    @Test
    fun `a step whose trajectory leaves the radar is dropped, not called dry`() {
        // Riga sits near the upwind edge here, so the longest leads trace back
        // off the grid. Those steps must go missing rather than arrive as a
        // confident zero - beyond the radar we do not know, and the fusion layer
        // has to be able to tell that from a forecast of no rain.
        val nowcast = RadarNowcaster.nowcast(movingEast(), leads)!!
        val nearEdge = nowcast.seriesAt(56.95, 24.11)

        assertTrue(nearEdge.isNotEmpty())
        assertTrue(nearEdge.size < leads.size)
        assertTrue(nearEdge.zipWithNext().all { (a, b) -> a.at < b.at })
    }

    @Test
    fun `a place off the grid gets nothing at all`() {
        val nowcast = RadarNowcaster.nowcast(movingEast(), leads)!!
        assertTrue(nowcast.seriesAt(51.5, -0.12).isEmpty())
    }

    @Test
    fun `too little to go on produces nothing at all`() {
        assertNull(RadarNowcaster.nowcast(emptyList(), leads))
        assertNull(RadarNowcaster.nowcast(listOf(RadarTestFields.pattern(start, geometry)), leads))
        assertNull(RadarNowcaster.nowcast(movingEast(), emptyList()))
        assertNull(
            RadarNowcaster.nowcast(
                listOf(
                    RadarTestFields.dry(start, geometry),
                    RadarTestFields.dry(start.plus(Duration.ofMinutes(10)), geometry),
                ),
                leads,
            ),
        )
    }

    @Test
    fun `steps are stamped forward from the latest sweep`() {
        val nowcast = RadarNowcaster.nowcast(movingEast(), leads)!!
        val latest = start.plus(Duration.ofMinutes(10))

        assertEquals(latest, nowcast.issuedAt)
        nowcast.steps.forEach { step ->
            assertEquals(latest.plus(step.lead), step.at)
        }
    }
}
