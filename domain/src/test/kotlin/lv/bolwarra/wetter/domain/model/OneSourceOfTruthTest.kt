package lv.bolwarra.wetter.domain.model

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One answer to "how hard is it falling", for every surface.
 *
 * A provider's symbol carries two claims in one word: what is falling, and how
 * hard. The first is theirs. The second this app measures against its own
 * published scale, and until these tests existed the two were displayed side by
 * side without ever being reconciled.
 *
 * The failure was real and reported from use. On a MET Norway forecast for Rīga
 * the 20th peaked at 1.0 mm/h and came back as `DRIZZLE`, while the app's own
 * scale calls anything from 0.5 light rain — so the week showed a drizzle mark
 * on a day whose own bar said rain, and elsewhere the reverse.
 */
class OneSourceOfTruthTest {

    private fun hour(rate: Double?, condition: WeatherCondition, temperature: Double = 10.0) =
        HourlyWeather(
            timestamp = Instant.parse("2026-09-20T09:00:00Z"),
            temperature = temperature,
            apparentTemperature = null,
            precipitationProbability = null,
            precipitation = rate,
            rain = null,
            snowfall = null,
            condition = condition,
            windSpeed = null,
            windGust = null,
            uvIndex = null,
            cloudCover = null,
            cloudLow = null,
            cloudMedium = null,
            cloudHigh = null,
            isDay = true,
        )

    private fun day(peak: Double?, condition: WeatherCondition, high: Double = 12.0) = DailyWeather(
        date = LocalDate.parse("2026-09-20"),
        temperatureMin = 6.0,
        temperatureMax = high,
        condition = condition,
        precipitationTotal = 6.6,
        precipitationPeakRate = peak,
        precipitationProbabilityMax = null,
        precipitationHours = null,
        sunrise = null,
        sunset = null,
        windSpeedMax = null,
    )

    @Test
    fun `the measured case, both ways round`() {
        // A millimetre an hour called drizzle by the provider is rain here.
        assertEquals(WeatherCondition.RAIN, day(1.0, WeatherCondition.DRIZZLE).appearance)

        // And two tenths called rain by the provider is drizzle here.
        assertEquals(WeatherCondition.DRIZZLE, day(0.2, WeatherCondition.RAIN).appearance)
    }

    @Test
    fun `an hour and a day agree with each other and with the scale`() {
        listOf(0.0, 0.1, 0.4, 0.5, 2.0, 30.0).forEach { rate ->
            val fromHour = hour(rate, WeatherCondition.DRIZZLE).appearance
            val fromDay = day(rate, WeatherCondition.DRIZZLE).appearance
            assertEquals("an hour and a day disagree at $rate mm/h", fromHour, fromDay)

            val worthNaming = PrecipitationIntensity.ofRate(rate).isWorthNaming
            assertEquals(
                "$rate mm/h: the word and the scale disagree",
                if (worthNaming) WeatherCondition.RAIN else WeatherCondition.DRIZZLE,
                fromHour,
            )
        }
    }

    @Test
    fun `snow is renamed on the same scale as rain`() {
        assertEquals(
            WeatherCondition.SNOW,
            hour(2.0, WeatherCondition.SNOW_GRAINS, temperature = -5.0).appearance,
        )
        assertEquals(
            WeatherCondition.SNOW_GRAINS,
            hour(0.2, WeatherCondition.SNOW, temperature = -5.0).appearance,
        )
    }

    @Test
    fun `an absent measurement leaves the provider's word alone`() {
        // Null is not evidence of a light hour. A provider that says rain and
        // gives no amount has still said rain.
        assertEquals(WeatherCondition.RAIN, hour(null, WeatherCondition.RAIN).appearance)
        assertEquals(WeatherCondition.DRIZZLE, hour(null, WeatherCondition.DRIZZLE).appearance)
        assertEquals(WeatherCondition.RAIN, day(null, WeatherCondition.RAIN).appearance)
    }

    @Test
    fun `a dry sky is never turned into precipitation by a rate`() {
        // The rate may only rename precipitation, never invent it. Contradicting
        // a clear sky from a stray millimetre is the radar's job, and it is done
        // somewhere that knows whether the radar could see.
        listOf(
            WeatherCondition.CLEAR,
            WeatherCondition.OVERCAST,
            WeatherCondition.FOG,
            WeatherCondition.PARTLY_CLOUDY,
        ).forEach {
            assertEquals(it, hour(5.0, it).appearance)
        }
    }

    @Test
    fun `character and hazard survive the rename`() {
        // Showers are about the fall being intermittent, which a rate cannot
        // see. Thunder and freezing are hazards, and a provider that has said
        // which one it is knows more than a millimetre count.
        listOf(
            WeatherCondition.RAIN_SHOWERS,
            WeatherCondition.SNOW_SHOWERS,
            WeatherCondition.THUNDERSTORM,
            WeatherCondition.THUNDERSTORM_WITH_HAIL,
            WeatherCondition.FREEZING_RAIN,
            WeatherCondition.FREEZING_DRIZZLE,
            WeatherCondition.SLEET,
        ).forEach { condition ->
            assertEquals(
                "$condition was renamed by a rate",
                condition,
                condition.atRate(0.2),
            )
            assertEquals(
                "$condition was renamed by a rate",
                condition,
                condition.atRate(9.0),
            )
        }
    }

    @Test
    fun `temperature still gets the last word on what is falling`() {
        // The two corrections compose: the rate says how hard, the temperature
        // says what. Below freezing a heavy hour is snow, not rain.
        val freezing = hour(3.0, WeatherCondition.DRIZZLE, temperature = -6.0).appearance
        assertEquals(WeatherCondition.SNOW, freezing)
        assertNotEquals(WeatherCondition.RAIN, freezing)
    }

    @Test
    fun `renaming is idempotent`() {
        // Applying it twice must not walk the word up the scale, or a value that
        // passed through two layers would end up stronger than it was measured.
        listOf(0.2, 1.0, 9.0).forEach { rate ->
            listOf(WeatherCondition.DRIZZLE, WeatherCondition.RAIN, WeatherCondition.SNOW).forEach {
                val once = it.atRate(rate)
                assertEquals("$it at $rate is not stable", once, once.atRate(rate))
            }
        }
    }

    @Test
    fun `every precipitating condition survives a round trip through the scale`() {
        // Nothing may fall off the vocabulary into UNKNOWN.
        WeatherCondition.entries.forEach { condition ->
            listOf(null, 0.0, 0.3, 5.0, 60.0).forEach { rate ->
                val named = condition.atRate(rate)
                assertTrue(
                    "$condition at $rate became $named",
                    named != WeatherCondition.UNKNOWN || condition == WeatherCondition.UNKNOWN,
                )
                assertEquals(
                    "$condition at $rate changed whether anything is falling",
                    condition.isPrecipitating,
                    named.isPrecipitating,
                )
            }
        }
    }
}
