package lv.bolwarra.wetter.data.provider

import io.ktor.http.HttpStatusCode
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoProvider
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.provider.asWeatherError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open-Meteo's response, checked against the domain model it should become.
 *
 * The fixture is a real response shape, trimmed to four hours and two days. What
 * matters is that it contains the awkward parts: a null probability in the
 * middle of an array, a WMO code for every branch of the condition table, and a
 * timezone the caller did not supply.
 */
class OpenMeteoProviderTest {

    private val fetchedAt = Instant.parse("2026-03-14T09:05:00Z")
    private val clock = Clock.fixed(fetchedAt, ZoneOffset.UTC)

    private fun provider(body: String = fixture("open-meteo-riga.json")) =
        OpenMeteoProvider(clientReturning(body), clock = clock)

    @Test
    fun `a response becomes a forecast`() = runTest {
        val forecast = provider().getForecast(riga).getOrThrow()

        assertEquals(riga.name, forecast.location.name)
        assertEquals(fetchedAt, forecast.fetchedAt)
        assertEquals(OpenMeteoProvider.ID, forecast.provider.id)
        assertEquals(OpenMeteoProvider.ATTRIBUTION, forecast.provider.attribution)
    }

    @Test
    fun `the zone the server resolved is adopted`() = runTest {
        val stale = riga.copy(zone = ZoneId.of("UTC"))
        val forecast = provider().getForecast(stale).getOrThrow()

        assertEquals(ZoneId.of("Europe/Riga"), forecast.location.zone)
    }

    @Test
    fun `local times are anchored to the resolved zone`() = runTest {
        val forecast = provider().getForecast(riga).getOrThrow()

        // 11:00 in Riga during March is 09:00 UTC.
        assertEquals(Instant.parse("2026-03-14T09:00:00Z"), forecast.hourly.first().timestamp)
    }

    @Test
    fun `WMO codes become conditions and never integers`() = runTest {
        val hourly = provider().getForecast(riga).getOrThrow().hourly

        assertEquals(WeatherCondition.OVERCAST, hourly[0].condition)
        assertEquals(WeatherCondition.RAIN, hourly[1].condition)
        assertEquals(WeatherCondition.RAIN, hourly[2].condition)
        assertEquals(WeatherCondition.DRIZZLE, hourly[3].condition)
    }

    @Test
    fun `a missing probability stays missing rather than becoming zero`() = runTest {
        val hourly = provider().getForecast(riga).getOrThrow().hourly

        assertEquals(10, hourly[0].precipitationProbability)
        assertNull("a null in the array must not be read as 0%", hourly[2].precipitationProbability)
    }

    @Test
    fun `precipitation drives the intensity bands`() = runTest {
        val hourly = provider().getForecast(riga).getOrThrow().hourly

        assertEquals(PrecipitationIntensity.NONE, hourly[0].intensity)
        assertEquals(PrecipitationIntensity.LIGHT, hourly[1].intensity)
        assertEquals(PrecipitationIntensity.MODERATE, hourly[2].intensity)
        assertEquals(PrecipitationIntensity.TRACE, hourly[3].intensity)
    }

    @Test
    fun `the daily block carries sunrise and sunset in the right zone`() = runTest {
        val today = provider().getForecast(riga).getOrThrow().daily.first()

        assertEquals(-2.4, today.temperatureMin, 1e-9)
        assertEquals(5.6, today.temperatureMax, 1e-9)
        assertEquals(Instant.parse("2026-03-14T04:45:00Z"), today.sunrise)
        assertEquals(Instant.parse("2026-03-14T16:22:00Z"), today.sunset)
    }

    @Test
    fun `current conditions come from the current block`() = runTest {
        val current = provider().getForecast(riga).getOrThrow().current

        assertEquals(4.2, current.temperature!!, 1e-9)
        assertEquals(0.8, current.apparentTemperature!!, 1e-9)
        assertEquals(WeatherCondition.OVERCAST, current.condition)
        assertEquals(81, current.humidity)
        assertTrue(current.isDay)
    }

    @Test
    fun `a short array does not take down the whole forecast`() = runTest {
        // Open-Meteo omits a variable rather than padding it when a location has
        // no data for it. Reading past the end must not cost the user their
        // precipitation timeline.
        val truncated = fixture("open-meteo-riga.json")
            .replace("\"cloud_cover\": [87, 95, 98, 90]", "\"cloud_cover\": [87]")

        val hourly = provider(truncated).getForecast(riga).getOrThrow().hourly

        assertEquals(4, hourly.size)
        assertEquals(87, hourly[0].cloudCover)
        assertNull(hourly[3].cloudCover)
    }

    @Test
    fun `a missing current block falls back to the first hour`() = runTest {
        val withoutCurrent = fixture("open-meteo-riga.json")
            .replace("\"current\": {", "\"current_absent\": {")

        val forecast = provider(withoutCurrent).getForecast(riga).getOrThrow()

        assertEquals(4.2, forecast.current.temperature!!, 1e-9)
        assertEquals(forecast.hourly.first().timestamp, forecast.current.observedAt)
    }

    @Test
    fun `a rejected request becomes a provider error, not an exception`() = runTest {
        val provider =
            OpenMeteoProvider(clientFailing(HttpStatusCode.ServiceUnavailable), clock = clock)
        val result = provider.getForecast(riga)

        assertTrue(result.isFailure)
        assertEquals(
            WeatherError.ProviderRejected(503),
            result.exceptionOrNull()!!.asWeatherError(),
        )
    }

    @Test
    fun `an unresolvable host is reported as being offline`() = runTest {
        val provider = OpenMeteoProvider(
            clientThrowing(UnknownHostException("api.open-meteo.com")),
            clock = clock,
        )

        assertEquals(
            WeatherError.Offline,
            provider.getForecast(riga).exceptionOrNull()!!.asWeatherError(),
        )
    }

    @Test
    fun `unreadable JSON is reported as malformed`() = runTest {
        val result = provider("{\"hourly\": \"not an object\"}").getForecast(riga)

        assertTrue(result.exceptionOrNull()!!.asWeatherError() is WeatherError.MalformedResponse)
    }

    @Test
    fun `an unknown field in the response is ignored`() = runTest {
        // Providers add variables without warning. An app already installed must
        // keep working when they do.
        val extended = fixture("open-meteo-riga.json")
            .replace("\"elevation\": 7.0,", "\"elevation\": 7.0, \"a_new_field_from_2027\": 42,")

        assertNotNull(provider(extended).getForecast(riga).getOrNull())
    }
}
