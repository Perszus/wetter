package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureRiskTest {

    private val start: Instant = Instant.parse("2026-09-03T12:00:00Z")

    /** A timeline at ten-minute steps with the given rates. */
    private fun timeline(vararg rates: Double, confidence: Double = 0.8) =
        rates.mapIndexed { index, rate ->
            FusedPrecipitation(
                at = start.plus(Duration.ofMinutes(10L * index)),
                millimetresPerHour = rate,
                confidence = confidence,
                radarShare = 0.7,
                sources = 2,
            )
        }

    @Test
    fun `a dry window is a dry answer`() {
        val window = ExposureRisk.assess(
            timeline(0.0, 0.0, 0.0, 0.0),
            start,
            Duration.ofMinutes(30),
        )
        assertEquals(0.0, window.chanceOfRain, 0.0001)
        assertNull(window.peakAt)
    }

    @Test
    fun `walking into a shower is likely to be a wet walk`() {
        val window = ExposureRisk.assess(
            timeline(0.0, 0.0, 3.0, 3.0),
            start,
            Duration.ofMinutes(30),
        )
        assertTrue("chance was ${window.chanceOfRain}", window.chanceOfRain > 0.9)
        assertEquals(start.plus(Duration.ofMinutes(20)), window.peakAt)
    }

    @Test
    fun `a steady drizzle does not compound into certainty`() {
        // The reason the chance is the worst moment rather than the product of
        // the moments. Treated as independent trials, eighteen steps of light
        // drizzle would come out at over 99% - the honest answer is roughly the
        // chance that the drizzle is there at all.
        val drizzle = ExposureRisk.assess(
            List(18) { 0.3 }.toDoubleArray().let { timeline(*it) },
            start,
            Duration.ofHours(3),
        )
        assertTrue("chance was ${drizzle.chanceOfRain}", drizzle.chanceOfRain < 0.75)
    }

    @Test
    fun `leaving later can be the difference between dry and soaked`() {
        // The shower is in the first half hour and gone after it.
        val line = timeline(5.0, 5.0, 5.0, 0.0, 0.0, 0.0, 0.0)

        val now = ExposureRisk.assess(line, start, Duration.ofMinutes(20))
        val later = ExposureRisk.assess(
            line,
            start.plus(Duration.ofMinutes(40)),
            Duration.ofMinutes(20),
        )

        assertTrue(now.chanceOfRain > 0.9)
        assertEquals(0.0, later.chanceOfRain, 0.0001)
    }

    @Test
    fun `the departure table walks forward in the step asked for`() {
        val options = ExposureRisk.departures(
            timeline(4.0, 4.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            from = start,
            outdoorFor = Duration.ofMinutes(20),
            within = Duration.ofHours(1),
            every = Duration.ofMinutes(20),
        )
        assertEquals(4, options.size)
        assertEquals(start, options.first().leaveAt)
        assertTrue(options.first().chanceOfRain > options.last().chanceOfRain)
    }

    @Test
    fun `waiting is only advised when it actually helps`() {
        val soaking = timeline(6.0, 6.0, 6.0, 0.0, 0.0, 0.0, 0.0)
        val best = ExposureRisk.bestDeparture(
            soaking,
            start,
            Duration.ofMinutes(20),
            Duration.ofHours(1),
            Duration.ofMinutes(20),
        )
        assertNotNull("should have suggested waiting", best)
        assertTrue(best!!.leaveAt.isAfter(start))

        // Nothing to gain: it rains throughout, so telling somebody to wait
        // would be advice that buys them nothing.
        val relentless = timeline(4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0)
        assertNull(
            ExposureRisk.bestDeparture(
                relentless,
                start,
                Duration.ofMinutes(20),
                Duration.ofHours(1),
                Duration.ofMinutes(20),
            ),
        )

        // Nor when it is dry throughout.
        val fine = timeline(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertNull(
            ExposureRisk.bestDeparture(
                fine,
                start,
                Duration.ofMinutes(20),
                Duration.ofHours(1),
                Duration.ofMinutes(20),
            ),
        )
    }

    @Test
    fun `the chance saturates - heavy and torrential both soak you`() {
        val light = ExposureRisk.chanceOf(0.5)
        val heavy = ExposureRisk.chanceOf(8.0)
        val torrential = ExposureRisk.chanceOf(40.0)

        assertEquals(0.63, light, 0.02)
        assertTrue(heavy > 0.99)
        // Past a point more rain does not make you wetter, only unhappier.
        assertTrue(torrential - heavy < 0.01)
    }

    @Test
    fun `a window is only as trustworthy as its weakest moment`() {
        val mixed = listOf(
            FusedPrecipitation(start, 1.0, 0.9, 0.8, 2),
            FusedPrecipitation(start.plus(Duration.ofMinutes(10)), 1.0, 0.2, 0.1, 2),
        )
        assertEquals(
            0.2,
            ExposureRisk.assess(mixed, start, Duration.ofMinutes(10)).confidence,
            0.001,
        )
    }

    @Test
    fun `accumulation reflects how long the window is`() {
        // A steady 6 mm/h for an hour is 6 mm, not 7. Seven samples bound six
        // ten-minute intervals, and counting the samples rather than the gaps
        // charges the hour an extra step of rain.
        val hour = ExposureRisk.assess(
            timeline(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0),
            start,
            Duration.ofHours(1),
        )
        assertEquals(6.0, hour.millimetres, 0.05)

        // Half as long, half as much.
        val half = ExposureRisk.assess(
            timeline(6.0, 6.0, 6.0, 6.0, 6.0, 6.0, 6.0),
            start,
            Duration.ofMinutes(30),
        )
        assertEquals(3.0, half.millimetres, 0.05)
    }

    @Test
    fun `accumulation follows a rate that changes across the window`() {
        // Rising evenly from 0 to 6 over an hour averages 3, so 3 mm.
        val ramp = ExposureRisk.assess(
            timeline(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0),
            start,
            Duration.ofHours(1),
        )
        assertEquals(3.0, ramp.millimetres, 0.05)
    }

    @Test
    fun `a window outside the timeline claims nothing`() {
        val window = ExposureRisk.assess(
            timeline(5.0, 5.0),
            start.plus(Duration.ofDays(2)),
            Duration.ofMinutes(30),
        )
        assertEquals(0.0, window.chanceOfRain, 0.0001)
        assertEquals(0.0, window.confidence, 0.0001)
    }
}
