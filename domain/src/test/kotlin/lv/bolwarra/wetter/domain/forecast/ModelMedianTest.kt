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
