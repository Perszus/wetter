package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A forecast left sitting must not keep insisting it is describing now.
 *
 * The screen moves with the clock whether or not the data does, so the only
 * question these cover is whether the reading and the clock still agree.
 */
class CurrentConditionsTest {

    private val zone = ZoneId.of("Europe/Riga")
    private val fetched = Instant.parse("2026-09-03T08:00:00Z")

    private fun hour(offset: Long, temperature: Double, wind: Double) = HourlyWeather(
        timestamp = fetched.plus(Duration.ofHours(offset)),
        temperature = temperature,
        precipitationProbability = null,
        precipitation = 0.0,
        rain = null,
        snowfall = null,
        condition = WeatherCondition.CLEAR,
        windSpeed = wind,
        windGust = null,
        apparentTemperature = null,
        uvIndex = null,
        cloudCover = 20,
        cloudLow = null,
        cloudMedium = null,
        cloudHigh = null,
        isDay = true,
    )

    private val forecast = WeatherForecast(
        location = WeatherLocation(name = "Riga", latitude = 56.95, longitude = 24.11, zone = zone),
        current = CurrentWeather(
            observedAt = fetched,
            temperature = 11.0,
            apparentTemperature = 9.0,
            condition = WeatherCondition.OVERCAST,
            isDay = true,
            precipitation = 0.0,
            windSpeed = 2.0,
            windGust = null,
            windDirection = 270,
            humidity = 80,
            pressure = 1012.0,
        ),
        hourly = List(12) { hour(it.toLong(), 11.0 + it, 2.0 + it) },
        daily = emptyList(),
        fetchedAt = fetched,
        provider = ProviderMetadata(
            id = "test",
            name = "Test",
            model = null,
            resolutionKm = null,
            forecastGeneratedAt = null,
            attribution = "Test",
        ),
    )

    @Test
    fun `fresh data is handed back untouched`() {
        // Half an hour after the observation, still inside its own slot. Nothing
        // has been overtaken, so the richer observation must survive intact -
        // including the fields the hourly rows do not carry.
        val soon = fetched.plus(Duration.ofMinutes(30))
        assertEquals(forecast.current, forecast.conditionsAt(soon))
    }

    @Test
    fun `once overtaken, the reading is the hour we are actually in`() {
        val sixHoursOn = fetched.plus(Duration.ofHours(6))
        val now = forecast.conditionsAt(sixHoursOn)

        assertEquals(17.0, now.temperature!!, 0.001)
        assertEquals(8.0, now.windSpeed!!, 0.001)
        assertEquals(WeatherCondition.CLEAR, now.condition)
        assertEquals(fetched.plus(Duration.ofHours(6)), now.observedAt)
    }

    @Test
    fun `an overtaken reading drops what it can no longer vouch for`() {
        // Pressure and humidity six hours old are not readings for now, and
        // sitting unlabelled beside a corrected temperature they would be taken
        // for readings for now. A dash is the honest answer.
        val now = forecast.conditionsAt(fetched.plus(Duration.ofHours(6)))

        assertNull(now.apparentTemperature)
        assertNull(now.humidity)
        assertNull(now.pressure)
        assertNull(now.windDirection)
    }

    @Test
    fun `past the end of the series the observation is all there is`() {
        // Beyond the forecast there is nothing better to offer, and inventing a
        // dash for every field would be worse than the stale number.
        val wayOut = fetched.plus(Duration.ofDays(30))
        assertEquals(forecast.current, forecast.conditionsAt(wayOut))
    }

    @Test
    fun `the lookup finds the row covering the instant, not the nearest one`() {
        // Ten past two belongs to two o'clock, not to three, however close it is.
        val tenPast = fetched.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(10))
        assertEquals(fetched.plus(Duration.ofHours(2)), forecast.hourly.at(tenPast)?.timestamp)

        val tenTo = fetched.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(10))
        assertEquals(fetched.plus(Duration.ofHours(2)), forecast.hourly.at(tenTo)?.timestamp)
    }

    @Test
    fun `before the series starts there is no row`() {
        assertNull(forecast.hourly.at(fetched.minus(Duration.ofMinutes(1))))
    }

    @Test
    fun `a widening stitched timeline does not open gaps in the lookup`() {
        // Past the first provider's horizon the rows step every six hours
        // (docs/providers.md). A lookup assuming a fixed hour would fall through
        // the five hours after each row and report nothing known about now.
        val stitched = forecast.copy(
            hourly = listOf(hour(0, 11.0, 2.0), hour(6, 17.0, 8.0), hour(12, 23.0, 14.0)),
        )
        val threeHoursIn = fetched.plus(Duration.ofHours(3))

        assertEquals(fetched, stitched.hourly.at(threeHoursIn)?.timestamp)
    }
}
