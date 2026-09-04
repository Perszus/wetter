package lv.bolwarra.wetter.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an hour says is falling.
 *
 * The interesting case is the one where the provider gives an amount but no
 * breakdown, which is most of MET Norway's output — and therefore most of what
 * users in the Nordics see, since that is the region it is chosen for. Getting
 * this wrong paints a blizzard in the rain colour.
 */
class PrecipitationKindTest {

    private fun hour(
        precipitation: Double?,
        rain: Double? = null,
        snowfall: Double? = null,
        condition: WeatherCondition = WeatherCondition.UNKNOWN,
    ) = HourlyWeather(
        timestamp = Instant.parse("2026-01-14T09:00:00Z"),
        temperature = -3.0,
        precipitationProbability = null,
        precipitation = precipitation,
        rain = rain,
        snowfall = snowfall,
        condition = condition,
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

    @Test
    fun `a dry hour is falling nothing`() {
        assertEquals(PrecipitationKind.NONE, hour(0.0).kind)
        assertEquals(PrecipitationKind.NONE, hour(null).kind)
    }

    @Test
    fun `a breakdown is believed over the condition`() {
        assertEquals(
            PrecipitationKind.RAIN,
            hour(2.0, rain = 2.0, condition = WeatherCondition.SNOW).kind,
        )
        assertEquals(
            PrecipitationKind.SNOW,
            hour(2.0, snowfall = 3.0, condition = WeatherCondition.RAIN).kind,
        )
        assertEquals(
            PrecipitationKind.MIXED,
            hour(2.0, rain = 1.0, snowfall = 1.0).kind,
        )
    }

    @Test
    fun `without a breakdown the condition decides`() {
        // MET Norway's shape: an amount, and a symbol, and nothing else.
        assertEquals(PrecipitationKind.SNOW, hour(2.0, condition = WeatherCondition.SNOW).kind)
        assertEquals(
            PrecipitationKind.SNOW,
            hour(2.0, condition = WeatherCondition.SNOW_SHOWERS).kind,
        )
        assertEquals(PrecipitationKind.MIXED, hour(2.0, condition = WeatherCondition.SLEET).kind)
        assertEquals(PrecipitationKind.RAIN, hour(2.0, condition = WeatherCondition.RAIN).kind)
        assertEquals(
            PrecipitationKind.RAIN,
            hour(2.0, condition = WeatherCondition.THUNDERSTORM).kind,
        )
    }

    @Test
    fun `an amount with no breakdown and no usable condition is not called rain`() {
        // The old behaviour assumed rain here. It is more honest to say that
        // something is falling without claiming to know what.
        assertEquals(PrecipitationKind.NONE, hour(2.0, condition = WeatherCondition.UNKNOWN).kind)
    }

    @Test
    fun `a trace below the threshold is not precipitation`() {
        assertEquals(
            PrecipitationKind.NONE,
            hour(0.05, condition = WeatherCondition.SNOW).kind,
        )
    }
}
