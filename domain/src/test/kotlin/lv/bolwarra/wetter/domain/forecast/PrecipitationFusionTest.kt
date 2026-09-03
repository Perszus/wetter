package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.radar.RadarNowcaster
import lv.bolwarra.wetter.domain.radar.RadarSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecipitationFusionTest {

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
            cloudCover = null,
            isDay = true,
        )
    }

    private fun radar(vararg pairs: Pair<Long, Pair<Float, Float>>) = pairs.map { (minutes, v) ->
        RadarSample(
            at = start.plus(Duration.ofMinutes(minutes)),
            lead = Duration.ofMinutes(minutes),
            millimetresPerHour = v.first,
            confidence = v.second,
        )
    }

    @Test
    fun `a confident radar dominates the near term`() {
        // Radar says it is pouring, the model says dry. For the next few minutes
        // the radar is looking at rain that exists and should win.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0, 0.0, 0.0),
            radar = radar(10L to (8f to 0.9f)),
            from = start.plus(Duration.ofMinutes(10)),
            step = Duration.ofMinutes(10),
            steps = 1,
        )
        assertEquals(1, fused.size)
        assertTrue("radar share was ${fused[0].radarShare}", fused[0].radarShare > 0.8)
        assertTrue(
            "fused rate was ${fused[0].millimetresPerHour}",
            fused[0].millimetresPerHour > 6.0,
        )
    }

    @Test
    fun `an unconfident radar hands over to the model`() {
        // The Dublin case: an estimate built on almost no echo. A fixed
        // lead-time table would still have given it most of the weight.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(2.0, 2.0, 2.0),
            radar = radar(10L to (9f to 0.05f)),
            from = start.plus(Duration.ofMinutes(10)),
            step = Duration.ofMinutes(10),
            steps = 1,
        )
        assertTrue("radar share was ${fused[0].radarShare}", fused[0].radarShare < 0.1)
        assertEquals(2.0, fused[0].millimetresPerHour, 0.7)
    }

    @Test
    fun `radar never takes the whole answer`() {
        // It cannot see snow below the beam, or past its own coverage, and this
        // source cannot even say where that coverage ends.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(4.0, 4.0),
            radar = radar(0L to (0f to 1.0f)),
            from = start,
            step = Duration.ofMinutes(10),
            steps = 1,
        )
        assertTrue(fused[0].radarShare <= PrecipitationFusion.MAX_RADAR_WEIGHT)
        assertTrue("model was shut out entirely", fused[0].millimetresPerHour > 0.0)
    }

    @Test
    fun `with no radar the model stands alone`() {
        val fused = PrecipitationFusion.fuse(
            hourly = hours(1.0, 3.0),
            radar = emptyList(),
            from = start,
            step = Duration.ofMinutes(30),
            steps = 2,
        )
        assertTrue(fused.all { it.radarShare == 0.0 && it.sources == 1 })
        assertEquals(1.0, fused[0].millimetresPerHour, 0.001)
        assertEquals(2.0, fused[1].millimetresPerHour, 0.001)
    }

    @Test
    fun `the model is interpolated between its hours, not stepped`() {
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0, 6.0),
            radar = emptyList(),
            from = start,
            step = Duration.ofMinutes(15),
            steps = 4,
        )
        val rates = fused.map { it.millimetresPerHour }
        assertEquals(listOf(0.0, 1.5, 3.0, 4.5), rates.map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun `sources that agree are trusted more than sources that differ`() {
        val agreeing = PrecipitationFusion.fuse(
            hourly = hours(5.0, 5.0),
            radar = radar(0L to (5f to 0.8f)),
            from = start,
            step = Duration.ofMinutes(10),
            steps = 1,
        )
        val arguing = PrecipitationFusion.fuse(
            hourly = hours(0.0, 0.0),
            radar = radar(0L to (10f to 0.8f)),
            from = start,
            step = Duration.ofMinutes(10),
            steps = 1,
        )
        assertTrue(
            "agreeing ${agreeing[0].confidence} vs arguing ${arguing[0].confidence}",
            agreeing[0].confidence > arguing[0].confidence,
        )
    }

    @Test
    fun `agreement is judged relative to how much rain there is`() {
        // Half a millimetre apart is close in a downpour and total disagreement
        // in a drizzle.
        val inDownpour = PrecipitationFusion.agreement(20.0, 20.5)
        val inDrizzle = PrecipitationFusion.agreement(0.1, 0.6)
        assertTrue(inDownpour > 0.95)
        assertTrue(inDrizzle < inDownpour)
    }

    @Test
    fun `a radar sample from the wrong moment is not used for this one`() {
        // Samples are ten minutes apart; one from an hour away describes
        // different weather and must not be dragged in.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(1.0, 1.0),
            radar = radar(60L to (20f to 0.9f)),
            from = start,
            step = Duration.ofMinutes(10),
            steps = 1,
        )
        assertEquals(0.0, fused[0].radarShare, 0.0001)
        assertEquals(1.0, fused[0].millimetresPerHour, 0.001)
    }

    @Test
    fun `beyond both sources nothing is claimed`() {
        val fused = PrecipitationFusion.fuse(
            hourly = hours(1.0, 1.0),
            radar = emptyList(),
            from = start.plus(Duration.ofHours(9)),
            step = Duration.ofMinutes(10),
            steps = 1,
        )
        assertEquals(0, fused[0].sources)
        assertEquals(0.0, fused[0].confidence, 0.0001)
    }

    @Test
    fun `the answer about a shower sharpens as it approaches`() {
        // The property the whole radar layer exists for, and the one thing a
        // model cannot do. A model hands over a value for a given hour and that
        // value is the same whenever you ask it - you get whatever was sent.
        // Radar re-observes every ten minutes, so the same real shower is a
        // distant projection when it is an hour off and very nearly an
        // observation by the time it is ten minutes away.
        //
        // Here the same event is asked about at two removes, with the radar
        // confidence each would genuinely carry at that lead.
        val sharp = RadarNowcaster.GOOD_MATCH_SHARPNESS

        fun askedAt(leadMinutes: Long) = PrecipitationFusion.fuse(
            hourly = hours(0.0, 0.0, 0.0),
            radar = radar(
                leadMinutes to (6f to RadarNowcaster.confidenceAt(leadMinutes.toDouble(), sharp)),
            ),
            from = start.plus(Duration.ofMinutes(leadMinutes)),
            step = Duration.ofMinutes(10),
            steps = 1,
        ).first()

        val anHourOff = askedAt(60)
        val closeIn = askedAt(10)

        assertTrue(
            "an hour off ${anHourOff.radarShare} vs close in ${closeIn.radarShare}",
            closeIn.radarShare > anHourOff.radarShare,
        )
        assertTrue(closeIn.confidence > anHourOff.confidence)

        // And the closer reading should be most of the answer rather than a
        // minority share of it, or the sharpening is academic.
        assertTrue("close in was ${closeIn.radarShare}", closeIn.radarShare > 0.8)
        assertTrue(
            "the model still overrode a shower ten minutes away: " +
                "${closeIn.millimetresPerHour}",
            closeIn.millimetresPerHour > 4.5,
        )
    }

    @Test
    fun `a model gains no confidence as the hour approaches, radar does`() {
        // The contrast, stated as something that can actually fail. With only a
        // model there is nothing to re-observe: its confidence in a given hour
        // is a property of that hour and of how far the models are apart over
        // it, and is the same at ten minutes' notice as at an hour's. Radar's
        // rises as the thing gets closer, because it keeps looking.
        val sharp = RadarNowcaster.GOOD_MATCH_SHARPNESS

        fun modelOnlyAt(leadMinutes: Long) = PrecipitationFusion.fuse(
            hourly = hours(0.0, 6.0, 6.0),
            radar = emptyList(),
            from = start.plus(Duration.ofMinutes(leadMinutes)),
            step = Duration.ofMinutes(10),
            steps = 1,
        ).first().confidence

        assertEquals(modelOnlyAt(60), modelOnlyAt(10), 1e-9)

        val radarFar = RadarNowcaster.confidenceAt(60.0, sharp)
        val radarNear = RadarNowcaster.confidenceAt(10.0, sharp)
        assertTrue("$radarNear should beat $radarFar", radarNear > radarFar)
    }

    @Test
    fun `degenerate requests produce nothing rather than throwing`() {
        assertTrue(
            PrecipitationFusion.fuse(hours(1.0), emptyList(), start, Duration.ZERO, 3).isEmpty(),
        )
        assertTrue(
            PrecipitationFusion.fuse(hours(1.0), emptyList(), start, Duration.ofMinutes(10), 0)
                .isEmpty(),
        )
    }
}
