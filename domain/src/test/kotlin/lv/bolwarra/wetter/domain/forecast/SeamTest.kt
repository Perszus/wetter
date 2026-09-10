package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.radar.RadarSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join between two sources, and whether a reader can see it.
 *
 * The fusion tests next door check that each source wins where it should. This
 * asks a different question: whether the hand-over is *visible* as a step no
 * weather produced. Two sources can each be right about their own stretch and
 * still produce a chart with a cliff in the middle of it, and a cliff is a claim
 * — it says something happens at that minute, and nothing does. The minute it
 * happens at is an implementation detail of which service was asked.
 */
class SeamTest {

    private val start: Instant = Instant.parse("2026-09-03T12:00:00Z")

    private fun hours(vararg mm: Double?) = mm.mapIndexed { index, value ->
        HourlyWeather(
            timestamp = start.plus(Duration.ofHours(index.toLong())),
            temperature = null,
            precipitationProbability = null,
            precipitation = value,
            rain = null,
            snowfall = null,
            condition = WeatherCondition.CLEAR,
            windSpeed = null,
            windGust = null,
            apparentTemperature = null,
            uvIndex = null,
            cloudCover = null,
            cloudLow = null,
            cloudMedium = null,
            cloudHigh = null,
            isDay = true,
        )
    }

    private fun radar(minutes: LongRange, rate: Float, confidence: Float) = minutes.step(5).map {
        RadarSample(
            at = start.plus(Duration.ofMinutes(it)),
            lead = Duration.ofMinutes(it),
            millimetresPerHour = rate,
            confidence = confidence,
        )
    }

    @Test
    fun `the radar to model hand-over is not a cliff`() {
        // The hardest case there is: radar sees a downpour and the model says
        // the same hours are dry. Each is authoritative over its own stretch, so
        // a naive join steps from 8 mm/h to nothing at whatever minute the radar
        // stops being trusted.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0, 0.0, 0.0, 0.0),
            radar = radar(0..90L, rate = 8.0f, confidence = 0.9f),
            from = start,
            step = Duration.ofMinutes(5),
            steps = 36,
        )
        assertTrue("nothing was fused", fused.size > 6)

        val steps = fused.zipWithNext { a, b ->
            abs(b.millimetresPerHour - a.millimetresPerHour)
        }
        val worst = steps.maxOrNull() ?: 0.0

        // The whole disagreement is 8 mm/h. Handing over in one step would show
        // all of it at once; a blend spreads it across the window.
        assertTrue(
            "the hand-over stepped by $worst mm/h out of a total disagreement of 8",
            worst < 8.0 / 2.0,
        )
    }

    @Test
    fun `the hand-over runs one way, without a bounce`() {
        // A blend that overshoots would show rain easing, returning, and easing
        // again - three events where there is one. Radar high, model low, so the
        // series must fall and keep falling.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0, 0.0, 0.0, 0.0),
            radar = radar(0..90L, rate = 6.0f, confidence = 0.9f),
            from = start,
            step = Duration.ofMinutes(5),
            steps = 30,
        )

        val rises = fused.zipWithNext { a, b -> b.millimetresPerHour - a.millimetresPerHour }
            .count { it > 0.05 }
        assertEquals("the hand-over should not go back up", 0, rises)
    }

    @Test
    fun `a seam between two sources that agree is invisible`() {
        // The control. If both say 3 mm/h there is nothing to blend, and any step
        // at all would be the join inventing one.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(3.0, 3.0, 3.0, 3.0),
            radar = radar(0..90L, rate = 3.0f, confidence = 0.9f),
            from = start,
            step = Duration.ofMinutes(5),
            steps = 30,
        )
        val worst = fused.zipWithNext { a, b ->
            abs(b.millimetresPerHour - a.millimetresPerHour)
        }.maxOrNull() ?: 0.0
        assertTrue("two sources agreeing still produced a step of $worst", worst < 0.1)
    }

    @Test
    fun `every surface agrees about which hours are wet`() {
        // The chart draws a band, the hour strip puts a mark on wet hours, and
        // the day's figure counts them. All three ask the same question and they
        // must not disagree - a strip with no wet hour under a day headed "rain"
        // is the complaint that started the hour strip in the first place.
        val rates = listOf(0.0, 0.02, 0.09, 0.1, 0.5, 2.0, 12.0, 55.0)

        rates.forEach { rate ->
            val byIntensity = PrecipitationIntensity.ofRate(rate) != PrecipitationIntensity.NONE
            val byBand = RainCurveBands.levelOf(rate) != PrecipitationIntensity.NONE
            val byThreshold = rate >= PrecipitationIntensity.TRACE_MM_PER_HOUR

            assertEquals(
                "$rate mm/h: the chart band and the intensity scale disagree",
                byIntensity,
                byBand,
            )
            assertEquals(
                "$rate mm/h: the trace threshold and the intensity scale disagree",
                byIntensity,
                byThreshold,
            )
        }
    }

    @Test
    fun `the wet-dry boundary is in exactly one place`() {
        // And it is the trace threshold, on both sides of it, so nothing sits in
        // a gap where one surface calls it rain and another calls it nothing.
        val trace = PrecipitationIntensity.TRACE_MM_PER_HOUR
        val justUnder = trace - 0.0001
        val justOver = trace + 0.0001

        assertEquals(PrecipitationIntensity.NONE, PrecipitationIntensity.ofRate(justUnder))
        assertEquals(PrecipitationIntensity.NONE, RainCurveBands.levelOf(justUnder))

        assertTrue(PrecipitationIntensity.ofRate(justOver) != PrecipitationIntensity.NONE)
        assertTrue(RainCurveBands.levelOf(justOver) != PrecipitationIntensity.NONE)
    }
}
