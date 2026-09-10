package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lv.bolwarra.wetter.domain.climate.ArchivedDay
import lv.bolwarra.wetter.domain.climate.ClimateNormals
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.hazard.HazardAnnouncements
import lv.bolwarra.wetter.domain.hazard.Hazards
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ForecastStitcher
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when the data is not there, or is nonsense.
 *
 * Every provider field is nullable for a reason: services drop variables, a
 * model has no gust for an hour it did not resolve, a response arrives truncated.
 * The rule this file enforces is the app's own - **a wrong number is worse than a
 * blank** - so the question at every step is whether missing input produces a
 * missing output or an invented zero.
 *
 * Nothing here should throw. A weather app that crashes on an empty array is a
 * weather app that crashes on a bad afternoon at a provider.
 */
class DegradationTest {

    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")
    private val zone: ZoneId = ZoneId.of("Europe/Riga")

    private fun hour(
        index: Int,
        temperature: Double? = 15.0,
        precipitation: Double? = 0.0,
        gust: Double? = null,
        uv: Double? = null,
    ) = HourlyWeather(
        timestamp = now.plus(Duration.ofHours(index.toLong())),
        temperature = temperature,
        apparentTemperature = null,
        precipitationProbability = null,
        precipitation = precipitation,
        rain = null,
        snowfall = null,
        condition = WeatherCondition.OVERCAST,
        windSpeed = null,
        windGust = gust,
        uvIndex = uv,
        cloudCover = null,
        cloudLow = null,
        cloudMedium = null,
        cloudHigh = null,
        isDay = true,
    )

    private fun forecast(hours: List<HourlyWeather>) = WeatherForecast(
        location = WeatherLocation("Riga", 56.9496, 24.1052, zone),
        current = CurrentWeather(
            observedAt = now,
            temperature = null,
            apparentTemperature = null,
            condition = WeatherCondition.OVERCAST,
            isDay = true,
            precipitation = null,
            windSpeed = null,
            windGust = null,
            windDirection = null,
            humidity = null,
            pressure = null,
        ),
        hourly = hours,
        daily = emptyList(),
        fetchedAt = now,
        provider = ProviderMetadata(
            id = "test",
            name = "Test",
            model = null,
            resolutionKm = null,
            forecastGeneratedAt = null,
            attribution = "Test",
        ),
    )

    // ---- G4: nothing at all ---------------------------------------------------

    @Test
    fun `a forecast with no hours raises nothing and crashes nothing`() {
        val empty = forecast(emptyList())
        assertTrue(Hazards.scan(empty, air = null, now = now).isEmpty())
        assertTrue(empty.hourly.window(now, 24).isEmpty())
        assertNull(empty.hourly.at(now))
    }

    @Test
    fun `a forecast entirely in the past yields no window and no hazard`() {
        val stale = forecast((1..12).map { hour(-it) })
        assertTrue(Hazards.scan(stale, air = null, now = now).isEmpty())
        // Every row is behind us, so the row covering now is genuinely absent.
        assertNull(stale.hourly.at(now))
    }

    @Test
    fun `a single hour is enough to not fall over`() {
        val one = forecast(listOf(hour(0)))
        assertEquals(1, one.hourly.window(now, 24).size)
        assertNotNull(one.hourly.at(now))
    }

    // ---- G3: fields that are simply absent -------------------------------------

    @Test
    fun `an hour with no temperature raises no temperature hazard`() {
        // Not "zero degrees", which would be a frost warning invented out of a
        // gap in a response.
        val blank = forecast((0..11).map { hour(it, temperature = null) })
        assertTrue(Hazards.scan(blank, air = null, now = now).isEmpty())
    }

    @Test
    fun `an hour with no gust raises no wind hazard`() {
        val blank = forecast((0..11).map { hour(it, gust = null) })
        assertTrue(
            Hazards.scan(blank, air = null, now = now)
                .none { it.kind.name == "DAMAGING_WIND" },
        )
    }

    @Test
    fun `an hour with no precipitation figure is not a dry hour`() {
        // Null and 0.0 are different claims. Null must not become a wet-or-dry
        // verdict at all.
        val blank = forecast((0..11).map { hour(it, precipitation = null) })
        assertTrue(Hazards.scan(blank, air = null, now = now).isEmpty())
    }

    @Test
    fun `nonsense numbers do not propagate`() {
        // A provider that ships a NaN, or a value no atmosphere produces. The
        // thresholds must not turn either into a warning that a reader would act
        // on - and nothing may throw.
        val absurd = forecast(
            listOf(
                hour(0, temperature = Double.NaN),
                hour(1, temperature = Double.POSITIVE_INFINITY),
                hour(2, temperature = -1000.0),
                hour(3, precipitation = Double.NaN),
            ),
        )
        val found = Hazards.scan(absurd, air = null, now = now)
        assertTrue("nonsense raised a hazard: $found", found.isEmpty())
    }

    // ---- the curve and the stitcher --------------------------------------------

    @Test
    fun `the shared intensity axis holds at the edges`() {
        // RainCurveBands is the one piece of geometry the app and the widget
        // share, so a disagreement here is a chart and a home screen that
        // describe different afternoons.
        listOf(0f, -1f, 0.001f, 1000f, Float.MAX_VALUE).forEach { rate ->
            val fraction = RainCurveBands.heightFraction(rate)
            assertTrue(
                "rate $rate produced $fraction, outside 0..1",
                fraction in 0f..1f,
            )
        }

        // And the band edges keep their order, since the widget draws its
        // gridlines from these and the chart draws its labels from them.
        assertTrue(
            "moderate ${RainCurveBands.moderateEdge} must sit below heavy ${RainCurveBands.heavyEdge}",
            RainCurveBands.moderateEdge < RainCurveBands.heavyEdge,
        )
    }

    @Test
    fun `stitching an empty extension returns the primary untouched`() {
        val primary = forecast((0..23).map { hour(it) })
        val stitched = ForecastStitcher.stitch(primary, forecast(emptyList()))
        assertEquals(primary.hourly.size, stitched.hourly.size)
    }

    @Test
    fun `stitching onto an empty primary does not lose the extension`() {
        val extension = forecast((0..23).map { hour(it) })
        val stitched = ForecastStitcher.stitch(forecast(emptyList()), extension)
        assertEquals(24, stitched.hourly.size)
    }

    // ---- climatology -----------------------------------------------------------

    @Test
    fun `climatology from nothing is empty rather than wrong`() {
        assertTrue(ClimateNormals.build(emptyList()).isEmpty)

        // And from too little: below the sample floor a date is left out
        // entirely, so the page draws a blank square instead of a median taken
        // over four years.
        val thin = (0..4).map {
            ArchivedDay(
                date = LocalDate.of(2020 + it, 9, 11),
                high = 18.0,
                low = 9.0,
                precipitation = 0.0,
            )
        }
        assertTrue(ClimateNormals.build(thin).isEmpty)
    }

    @Test
    fun `a hazard with no climatology still uses the published thresholds`() {
        // The degradation that matters most: an archive that did not answer must
        // not silence the warnings.
        val gale = forecast((0..5).map { hour(it, gust = 30.0) })
        assertTrue(Hazards.scan(gale, air = null, now = now, climate = null).isNotEmpty())
    }

    @Test
    fun `announcements from an empty hazard list are empty`() {
        assertTrue(
            HazardAnnouncements.due(emptyList(), said = emptySet(), zone = zone).isEmpty(),
        )
    }

    @Test
    fun `a missing-data sentinel is treated as missing, not as a record`() {
        // -9999 and 999 mean "no value" across a good deal of meteorology and
        // are ordinary numbers as far as JSON is concerned. Reaching a threshold,
        // either one raises a DANGER - the strongest thing the app can say - and
        // pushes a notification about it.
        val sentinels = forecast(
            listOf(
                hour(0, temperature = -9999.0),
                hour(1, temperature = 999.0),
                hour(2, gust = 9999.0),
                hour(3, precipitation = -9999.0),
            ),
        )
        assertTrue(Hazards.scan(sentinels, air = null, now = now).isEmpty())
    }

    @Test
    fun `real extremes are still allowed through`() {
        // The filter must reject sentinels without rejecting weather. Vostok has
        // been -89 and Furnace Creek 57, and a hurricane gust is 80 m/s.
        val vostok = forecast((0..5).map { hour(it, temperature = -89.2) })
        assertTrue(
            "the coldest day ever recorded raised nothing",
            Hazards.scan(vostok, air = null, now = now).isNotEmpty(),
        )

        val hurricane = forecast((0..5).map { hour(it, gust = 80.0) })
        assertTrue(
            "a hurricane gust raised nothing",
            Hazards.scan(hurricane, air = null, now = now).isNotEmpty(),
        )
    }
}
