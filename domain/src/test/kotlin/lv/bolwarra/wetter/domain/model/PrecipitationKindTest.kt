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
        temperature: Double = -3.0,
    ) = HourlyWeather(
        timestamp = Instant.parse("2026-01-14T09:00:00Z"),
        temperature = temperature,
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
        // With a temperature to match: this helper's hours are at minus three by
        // default, where a rain code is now read as snow on purpose.
        assertEquals(
            PrecipitationKind.RAIN,
            hour(2.0, condition = WeatherCondition.RAIN, temperature = 8.0).kind,
        )
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

    @Test
    fun `what would fall is decided by temperature, with a band of doubt`() {
        assertEquals(PrecipitationKind.SNOW, PrecipitationKind.likelyAt(-4.0))
        assertEquals(PrecipitationKind.SNOW, PrecipitationKind.likelyAt(0.5))
        // Near freezing neither answer is worth asserting, because what decides
        // it is the warm layer the flake falls through and not the screen-level
        // reading we have.
        assertEquals(PrecipitationKind.MIXED, PrecipitationKind.likelyAt(1.0))
        assertEquals(PrecipitationKind.MIXED, PrecipitationKind.likelyAt(2.5))
        assertEquals(PrecipitationKind.RAIN, PrecipitationKind.likelyAt(2.6))
        assertEquals(PrecipitationKind.RAIN, PrecipitationKind.likelyAt(15.0))
    }

    @Test
    fun `no temperature falls back to rain rather than inventing winter`() {
        assertEquals(PrecipitationKind.RAIN, PrecipitationKind.likelyAt(null))
    }

    @Test
    fun `rain below freezing is shown as snow`() {
        // Providers do publish this - usually a coarse grid averaging a valley
        // floor with the ridge above it. "Rain" over minus four is the kind of
        // contradiction that costs a reader their trust in the whole screen.
        assertEquals(WeatherCondition.SNOW, WeatherCondition.RAIN.appropriateFor(-4.0))
        assertEquals(WeatherCondition.SNOW_GRAINS, WeatherCondition.DRIZZLE.appropriateFor(-4.0))
        assertEquals(
            WeatherCondition.SNOW_SHOWERS,
            WeatherCondition.RAIN_SHOWERS.appropriateFor(-4.0),
        )
    }

    @Test
    fun `near freezing it says sleet rather than choosing`() {
        assertEquals(WeatherCondition.SLEET, WeatherCondition.RAIN.appropriateFor(1.5))
    }

    @Test
    fun `snow is never turned into rain`() {
        // The reverse correction would be wrong more often: a provider saying
        // snow at plus three has looked at the depth of the warm layer, which
        // is what decides it and is not something this app can see.
        assertEquals(WeatherCondition.SNOW, WeatherCondition.SNOW.appropriateFor(8.0))
        assertEquals(WeatherCondition.SLEET, WeatherCondition.SLEET.appropriateFor(8.0))
    }

    @Test
    fun `freezing rain keeps its name, which is the warning`() {
        assertEquals(
            WeatherCondition.FREEZING_RAIN,
            WeatherCondition.FREEZING_RAIN.appropriateFor(-3.0),
        )
        assertEquals(
            WeatherCondition.FREEZING_DRIZZLE,
            WeatherCondition.FREEZING_DRIZZLE.appropriateFor(-3.0),
        )
    }

    @Test
    fun `a dry condition is left alone`() {
        assertEquals(WeatherCondition.OVERCAST, WeatherCondition.OVERCAST.appropriateFor(-10.0))
        assertEquals(WeatherCondition.FOG, WeatherCondition.FOG.appropriateFor(-10.0))
    }

    @Test
    fun `a measured rain and snow split beats the temperature`() {
        // A provider that troubled to separate them has said something this
        // hour's screen temperature cannot improve on.
        val warmSnow = hour(
            precipitation = 2.0,
            snowfall = 2.0,
            condition = WeatherCondition.RAIN,
            temperature = 4.0,
        )
        assertEquals(PrecipitationKind.SNOW, warmSnow.kind)
    }
}
