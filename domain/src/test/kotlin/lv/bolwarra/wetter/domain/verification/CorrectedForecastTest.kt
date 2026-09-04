package lv.bolwarra.wetter.domain.verification

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CorrectedForecastTest {

    private val zone: ZoneId = ZoneId.of("Europe/Riga")
    private val at: Instant = Instant.parse("2026-09-03T18:00:00Z")

    private val forecast = WeatherForecast(
        location = WeatherLocation("Riga", 56.95, 24.11, zone),
        current = CurrentWeather(
            observedAt = at,
            temperature = 15.1,
            apparentTemperature = 14.0,
            condition = WeatherCondition.CLEAR,
            isDay = false,
            precipitation = 0.0,
            windSpeed = 2.0,
            windGust = null,
            windDirection = 220,
            humidity = 80,
            pressure = 1012.0,
        ),
        hourly = List(4) { index ->
            HourlyWeather(
                timestamp = at.plus(Duration.ofHours(index.toLong())),
                temperature = 15.0 - index,
                precipitationProbability = null,
                precipitation = 0.4,
                rain = null,
                snowfall = null,
                condition = WeatherCondition.CLEAR,
                windSpeed = 2.0,
                windGust = null,
                apparentTemperature = null,
                uvIndex = null,
                cloudCover = 10,
                isDay = false,
            )
        },
        daily = listOf(
            DailyWeather(
                date = LocalDate.of(2026, 9, 3),
                temperatureMin = 11.0,
                temperatureMax = 19.0,
                condition = WeatherCondition.CLEAR,
                precipitationTotal = 0.4,
                precipitationProbabilityMax = 20,
                precipitationHours = 1.0,
                sunrise = at,
                sunset = at.plus(Duration.ofHours(6)),
                windSpeedMax = 5.0,
            ),
        ),
        fetchedAt = at,
        provider = ProviderMetadata("test", "Test", null, null, null, "Test"),
    )

    private fun bias(offset: Double, samples: Int) = LearnedBias(
        variable = VerifiedVariable.TEMPERATURE,
        offset = offset,
        samples = samples,
        strength = BiasCorrection.strengthFor(samples),
    )

    @Test
    fun `with nothing learned the forecast is untouched`() {
        // A new location, which is every location to begin with.
        assertSame(forecast, forecast.withLocalCorrection(null))
    }

    @Test
    fun `a correction too weak to see is not applied`() {
        // Just past the minimum sample count the strength is near zero, so the
        // offset cannot move a displayed number and rebuilding the forecast
        // would be pure cost.
        val barely = bias(offset = 1.5, samples = BiasCorrection.MINIMUM_SAMPLES)
        assertSame(forecast, forecast.withLocalCorrection(barely))
    }

    @Test
    fun `a well evidenced warm bias cools the whole forecast`() {
        // The measured Riga evening: every model warm by about a degree and a
        // half. Corrected, 15.1 should come down towards the 14 that was
        // actually reported.
        val corrected = forecast.withLocalCorrection(
            bias(offset = 1.4, samples = BiasCorrection.CONFIDENT_SAMPLES),
        )
        assertEquals(13.7, corrected.current.temperature!!, 0.001)
    }

    @Test
    fun `the whole screen moves together, not just the headline`() {
        // A corrected dial above an uncorrected week would contradict itself,
        // and the contradiction is invisible to anyone who does not know a
        // correction exists.
        val corrected = forecast.withLocalCorrection(
            bias(offset = 2.0, samples = BiasCorrection.CONFIDENT_SAMPLES),
        )
        assertEquals(13.1, corrected.current.temperature!!, 0.001)
        assertEquals(12.0, corrected.current.apparentTemperature!!, 0.001)
        assertEquals(13.0, corrected.hourly.first().temperature!!, 0.001)
        assertEquals(10.0, corrected.hourly.last().temperature!!, 0.001)
        assertEquals(9.0, corrected.daily.first().temperatureMin, 0.001)
        assertEquals(17.0, corrected.daily.first().temperatureMax, 0.001)
    }

    @Test
    fun `a cold bias warms it, so the sign runs both ways`() {
        val corrected = forecast.withLocalCorrection(
            bias(offset = -2.0, samples = BiasCorrection.CONFIDENT_SAMPLES),
        )
        assertEquals(17.1, corrected.current.temperature!!, 0.001)
    }

    @Test
    fun `rain is left alone`() {
        // A temperature bias is an offset, which subtracting a constant fixes.
        // Rain error is about timing and placement, and shifting every rate by a
        // constant would add drizzle to dry hours without moving a missed
        // shower an inch closer to when it fell.
        val corrected = forecast.withLocalCorrection(
            bias(offset = 2.0, samples = BiasCorrection.CONFIDENT_SAMPLES),
        )
        assertEquals(0.4, corrected.hourly.first().precipitation!!, 0.001)
        assertEquals(0.4, corrected.daily.first().precipitationTotal!!, 0.001)
    }

    @Test
    fun `a precipitation bias is refused as a temperature correction`() {
        val wrongVariable = LearnedBias(
            variable = VerifiedVariable.PRECIPITATION,
            offset = 2.0,
            samples = 100,
            strength = 1.0,
        )
        assertSame(forecast, forecast.withLocalCorrection(wrongVariable))
    }

    @Test
    fun `missing temperatures stay missing rather than becoming the offset`() {
        val gappy = forecast.copy(
            current = forecast.current.copy(temperature = null, apparentTemperature = null),
            hourly = forecast.hourly.map { it.copy(temperature = null) },
        )
        val corrected = gappy.withLocalCorrection(
            bias(offset = 2.0, samples = BiasCorrection.CONFIDENT_SAMPLES),
        )
        assertNull(corrected.current.temperature)
        assertNull(corrected.current.apparentTemperature)
        assertNull(corrected.hourly.first().temperature)
    }

    @Test
    fun `the correction grows with the evidence behind it`() {
        val early = forecast.withLocalCorrection(bias(offset = 2.0, samples = 24))
        val settled = forecast.withLocalCorrection(
            bias(offset = 2.0, samples = BiasCorrection.CONFIDENT_SAMPLES),
        )
        val earlyShift = forecast.current.temperature!! - early.current.temperature!!
        val settledShift = forecast.current.temperature!! - settled.current.temperature!!

        assertEquals(2.0, settledShift, 0.001)
        // A quarter of the way to confident, so a quarter of the correction.
        assertEquals(0.5, earlyShift, 0.001)
    }
}
