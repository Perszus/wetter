package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No single model gets to decide.
 *
 * Built from what was actually on the phone on a wet evening in Riga. The
 * chosen provider reported 0.0 mm for every hour after the next and the symbol
 * "cloudy"; the seven-model ensemble already downloaded had six of seven wet
 * over the same hours. The screen drew a flat dry evening while it rained.
 */
class ModelMedianTest {

    private val start: Instant = Instant.parse("2026-09-05T19:00:00Z")

    private fun hours(vararg mm: Double?) = mm.mapIndexed { index, value ->
        HourlyWeather(
            timestamp = start.plus(Duration.ofHours(index.toLong())),
            temperature = 12.0,
            precipitationProbability = null,
            precipitation = value,
            rain = null,
            snowfall = null,
            condition = WeatherCondition.OVERCAST,
            windSpeed = null,
            windGust = null,
            apparentTemperature = 12.0,
            uvIndex = null,
            cloudCover = 99,
            cloudLow = null,
            cloudMedium = null,
            cloudHigh = null,
            isDay = false,
        )
    }

    /** One hour of the ensemble, from the values each model gave. */
    private fun ensemble(vararg members: Double) = ModelEnsemble(
        readings = (0..5).map { hour ->
            ModelReading(
                at = start.plus(Duration.ofHours(hour.toLong())),
                temperature = null,
                precipitation = ModelAgreement.summarise(
                    start.plus(Duration.ofHours(hour.toLong())),
                    members.toList(),
                ),
                chanceOfRain = null,
                // By position, because the fusion follows one source across
                // time to read its own line at a moment.
                precipitationByModel = members.toList(),
            )
        },
    )

    /**
     * The tolerance is 1e-6 rather than exact: the consensus is drawn through
     * the same Float monotone curve the provider's own hours use, so a value
     * lands within a rounding of itself rather than on it.
     */
    private fun rateAt(hour: Long, fused: List<FusedPrecipitation>) =
        fused.first { it.at == start.plus(Duration.ofHours(hour)) }.millimetresPerHour

    @Test
    fun `three sources, one moment, the middle one`() {
        // The whole rule, at the size it can be checked by eye. Three sources
        // say 2.0, 1.5 and 1.0 about a quarter to six; the answer for a quarter
        // to six is 1.5, because it is the middle one.
        //
        // A point in time, not an hour. The hour is only where a source happened
        // to put a number and is not a thing the weather does.
        val quarterToSix = start.plus(Duration.ofMinutes(45))
        val fused = PrecipitationFusion.fuse(
            hourly = emptyList(),
            radar = emptyList(),
            from = quarterToSix,
            step = Duration.ofMinutes(5),
            steps = 1,
            ensemble = ensemble(2.0, 1.5, 1.0),
        )

        assertEquals(1.5, fused.single().millimetresPerHour, 1e-6)
    }

    @Test
    fun `the middle is read at the moment, not averaged out of the interval`() {
        // Where the two orderings part company. One source falls from 4 to 0
        // across the interval, another sits at 3, a third climbs 0 to 4. At the
        // halfway point they read 2, 3 and 2, so the middle is 2.
        //
        // Taking a middle at each end first and drawing a line through those
        // would give 3 both ends and therefore 3 in between - a value no source
        // holds at that moment, which is the one thing a median should never
        // produce.
        val falling = listOf(4.0, 0.0)
        val flat = listOf(3.0, 3.0)
        val rising = listOf(0.0, 4.0)
        val readings = (0..1).map { hour ->
            val at = start.plus(Duration.ofHours(hour.toLong()))
            val members = listOf(falling[hour], flat[hour], rising[hour])
            ModelReading(
                at = at,
                temperature = null,
                precipitation = ModelAgreement.summarise(at, members),
                chanceOfRain = null,
                precipitationByModel = members,
            )
        }

        val halfway = PrecipitationFusion.fuse(
            hourly = emptyList(),
            radar = emptyList(),
            from = start.plus(Duration.ofMinutes(30)),
            step = Duration.ofMinutes(5),
            steps = 1,
            ensemble = ModelEnsemble(readings),
        ).single().millimetresPerHour

        assertTrue(
            "the middle at the moment was $halfway, not the middle of the ends",
            halfway < 2.9,
        )
    }

    @Test
    fun `six models saying rain outvote the one saying none`() {
        // The measured case, hour by hour: ecmwf, icon, gfs, ukmo, meteofrance,
        // knmi, dmi at 19:00Z, against a provider reporting nothing at all.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0, 0.0, 0.0),
            radar = emptyList(),
            from = start,
            step = Duration.ofHours(1),
            steps = 3,
            ensemble = ensemble(1.3, 0.7, 0.4, 1.7, 0.7, 1.9, 0.0),
        )

        // Provider plus members, sorted: 0, 0, 0.4, 0.7, 0.7, 1.3, 1.7, 1.9.
        // Eight values, so the median is the mean of the middle two.
        assertEquals(0.7, rateAt(0, fused), 1e-6)
        assertTrue("a wet evening must not draw as dry", rateAt(0, fused) > 0.1)
    }

    @Test
    fun `the provider is a vote, not a tiebreaker applied afterwards`() {
        // Three members and a provider is a median over four, not the provider
        // nudged toward a median over three. Those differ, and the difference is
        // the whole reason the member values are carried.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0),
            radar = emptyList(),
            from = start,
            step = Duration.ofHours(1),
            steps = 1,
            ensemble = ensemble(1.0, 2.0, 3.0),
        )

        // 0, 1, 2, 3 -> 1.5. The members alone would have said 2.0.
        assertEquals(1.5, rateAt(0, fused), 1e-6)
    }

    @Test
    fun `one model forecasting a deluge cannot carry the hour`() {
        // The failure in the other direction, and the reason for a median rather
        // than a mean: the mean of these is 2.1, which no model predicted.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.2),
            radar = emptyList(),
            from = start,
            step = Duration.ofHours(1),
            steps = 1,
            ensemble = ensemble(0.2, 0.3, 0.0, 0.1, 13.7),
        )

        assertTrue("an outlier must not set the hour", rateAt(0, fused) < 1.0)
    }

    @Test
    fun `with no ensemble the provider is the answer, as before`() {
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.4),
            radar = emptyList(),
            from = start,
            step = Duration.ofHours(1),
            steps = 1,
            ensemble = null,
        )

        assertEquals(0.4, rateAt(0, fused), 1e-6)
    }

    @Test
    fun `models agreeing with the provider change nothing`() {
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.5),
            radar = emptyList(),
            from = start,
            step = Duration.ofHours(1),
            steps = 1,
            ensemble = ensemble(0.5, 0.5, 0.5),
        )

        assertEquals(0.5, rateAt(0, fused), 1e-6)
    }

    @Test
    fun `the hour has a shape, not a step`() {
        // A lot happens in an hour and the models only speak once in each.
        // Holding their median flat until the next one drew the chart as a
        // staircase - one level per hour, the middle value and nothing else -
        // which is a claim no model made.
        val rising = ModelEnsemble(
            readings = listOf(0.0, 3.0, 0.0).mapIndexed { hour, rate ->
                ModelReading(
                    at = start.plus(Duration.ofHours(hour.toLong())),
                    temperature = null,
                    precipitation = ModelAgreement.summarise(
                        start.plus(Duration.ofHours(hour.toLong())),
                        List(7) { rate },
                    ),
                    chanceOfRain = null,
                    precipitationByModel = List(7) { rate },
                )
            },
        )

        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0, 3.0, 0.0),
            radar = emptyList(),
            from = start,
            step = Duration.ofMinutes(10),
            steps = 12,
            ensemble = rising,
        )

        val withinTheHour = fused.take(7).map { it.millimetresPerHour }
        assertTrue(
            "the hour should climb, not step: $withinTheHour",
            withinTheHour.distinct().size > 3,
        )
        assertTrue(
            "and climb in order: $withinTheHour",
            withinTheHour.zipWithNext().all { (a, b) -> b >= a - 1e-6 },
        )
        // Monotone, so it cannot overshoot: nothing between two anchors may
        // exceed the wetter of them or fall below the drier.
        assertTrue(
            "no invented peak: $withinTheHour",
            withinTheHour.all { it <= 3.0 + 1e-6 && it >= -1e-6 },
        )
    }

    @Test
    fun `a dry evening all seven agree on stays dry`() {
        // The rule has to be able to say nothing, or it is just a wetness bias.
        val fused = PrecipitationFusion.fuse(
            hourly = hours(0.0),
            radar = emptyList(),
            from = start,
            step = Duration.ofHours(1),
            steps = 1,
            ensemble = ensemble(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
        )

        assertEquals(0.0, rateAt(0, fused), 1e-6)
    }
}
