package lv.bolwarra.wetter.data.provider

import kotlinx.coroutines.test.runTest
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.provider.FakeWeatherProvider
import lv.bolwarra.wetter.domain.provider.GeoBox
import lv.bolwarra.wetter.domain.provider.ProviderCoverage
import lv.bolwarra.wetter.domain.provider.ProviderHealthRegistry
import lv.bolwarra.wetter.domain.provider.ProviderRegion
import lv.bolwarra.wetter.domain.provider.asWeatherError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Failover behaviour: which provider is asked, when a second is tried, and when
 * trying a second would only waste the user's time.
 *
 * The distinction under test throughout is whether a failure was about the
 * provider or about the situation. A timeout is the provider's problem and the
 * fallback should get its turn; having no connection is not, and asking a second
 * service would fail identically a few seconds later.
 */
class WeatherProviderRouterTest {

    private val now = Instant.parse("2026-03-14T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun router(
        vararg providers: FakeWeatherProvider,
        health: ProviderHealthRegistry = ProviderHealthRegistry(),
        maxAttempts: Int = WeatherProviderRouter.DEFAULT_MAX_ATTEMPTS,
    ) = WeatherProviderRouter(
        providers = providers.toList(),
        health = health,
        clock = clock,
        maxAttempts = maxAttempts,
    )

    /** Wins on geography at Riga, so a provider holding it is always preferred. */
    private val nordicCoverage = ProviderCoverage(
        isGlobal = true,
        preferredRegions = listOf(
            ProviderRegion(
                name = "the Nordics",
                box = GeoBox(south = 54.0, north = 72.0, west = 4.0, east = 32.0),
            ),
        ),
    )

    @Test
    fun `the preferred provider answers and is the one recorded`() = runTest {
        val preferred = FakeWeatherProvider.succeeding("preferred", nordicCoverage)
        val fallback = FakeWeatherProvider.succeeding("fallback")

        val forecast = router(preferred, fallback).getForecast(riga).getOrThrow()

        assertEquals("preferred", forecast.provider.id)
        assertEquals(1, preferred.calls)
        assertEquals("the fallback must not be called when the first succeeds", 0, fallback.calls)
    }

    @Test
    fun `a timeout hands over to the fallback`() = runTest {
        val preferred = FakeWeatherProvider.failing("preferred", WeatherError.Timeout, nordicCoverage)
        val fallback = FakeWeatherProvider.succeeding("fallback")

        val forecast = router(preferred, fallback).getForecast(riga).getOrThrow()

        assertEquals("fallback", forecast.provider.id)
        assertEquals(1, preferred.calls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `a server error hands over to the fallback`() = runTest {
        val preferred = FakeWeatherProvider.failing(
            "preferred",
            WeatherError.ProviderRejected(503),
            nordicCoverage,
        )
        val fallback = FakeWeatherProvider.succeeding("fallback")

        assertEquals("fallback", router(preferred, fallback).getForecast(riga).getOrThrow().provider.id)
    }

    @Test
    fun `a rate limit hands over to the fallback`() = runTest {
        val preferred = FakeWeatherProvider.failing(
            "preferred",
            WeatherError.ProviderRejected(429),
            nordicCoverage,
        )
        val fallback = FakeWeatherProvider.succeeding("fallback")

        assertEquals("fallback", router(preferred, fallback).getForecast(riga).getOrThrow().provider.id)
    }

    @Test
    fun `a malformed response hands over to the fallback`() = runTest {
        val preferred = FakeWeatherProvider.failing(
            "preferred",
            WeatherError.MalformedResponse(),
            nordicCoverage,
        )
        val fallback = FakeWeatherProvider.succeeding("fallback")

        assertEquals("fallback", router(preferred, fallback).getForecast(riga).getOrThrow().provider.id)
    }

    @Test
    fun `being offline does not waste a second request`() = runTest {
        val preferred = FakeWeatherProvider.failing("preferred", WeatherError.Offline, nordicCoverage)
        val fallback = FakeWeatherProvider.succeeding("fallback")

        val result = router(preferred, fallback).getForecast(riga)

        assertEquals(WeatherError.Offline, result.exceptionOrNull()!!.asWeatherError())
        assertEquals("no second provider can fix a device with no connection", 0, fallback.calls)
    }

    @Test
    fun `a rejected request is not retried against another service`() = runTest {
        // A 400 means the request was wrong, and the same request would be just
        // as wrong sent somewhere else.
        val preferred = FakeWeatherProvider.failing(
            "preferred",
            WeatherError.ProviderRejected(400),
            nordicCoverage,
        )
        val fallback = FakeWeatherProvider.succeeding("fallback")

        val result = router(preferred, fallback).getForecast(riga)

        assertTrue(result.isFailure)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `when everything fails the first failure is the one reported`() = runTest {
        val preferred = FakeWeatherProvider.failing("preferred", WeatherError.Timeout, nordicCoverage)
        val fallback = FakeWeatherProvider.failing("fallback", WeatherError.ProviderRejected(503))

        val result = router(preferred, fallback).getForecast(riga)

        assertEquals(WeatherError.Timeout, result.exceptionOrNull()!!.asWeatherError())
    }

    @Test
    fun `no more than the attempt limit is tried`() = runTest {
        val first = FakeWeatherProvider.failing("a", WeatherError.Timeout, nordicCoverage)
        val second = FakeWeatherProvider.failing("b", WeatherError.Timeout)
        val third = FakeWeatherProvider.succeeding("c")

        val result = router(first, second, third, maxAttempts = 2).getForecast(riga)

        assertTrue(result.isFailure)
        assertEquals("a third timeout would outlast anyone's patience", 0, third.calls)
    }

    @Test
    fun `a location nobody covers is reported as such`() = runTest {
        val restricted = FakeWeatherProvider.succeeding(
            "restricted",
            ProviderCoverage(isGlobal = false),
        )

        val result = router(restricted).getForecast(riga)

        assertEquals(WeatherError.NoProviderAvailable, result.exceptionOrNull()!!.asWeatherError())
        assertEquals(0, restricted.calls)
    }

    @Test
    fun `repeated failures rest a provider and the fallback takes over`() = runTest {
        val health = ProviderHealthRegistry()
        val preferred = FakeWeatherProvider.failing("preferred", WeatherError.Timeout, nordicCoverage)
        val fallback = FakeWeatherProvider.succeeding("fallback")
        val router = router(preferred, fallback, health = health)

        router.getForecast(riga)
        router.getForecast(riga)
        val callsBefore = preferred.calls

        // By now the preferred provider has failed twice and is resting, so the
        // third refresh should not disturb it at all.
        router.getForecast(riga)

        assertEquals(callsBefore, preferred.calls)
        assertEquals(2, health.of("preferred").consecutiveFailures)
    }

    @Test
    fun `a success clears the failure count`() = runTest {
        val health = ProviderHealthRegistry()
        health.recordFailure("only", now, WeatherError.Timeout)

        val provider = FakeWeatherProvider.succeeding("only")
        router(provider, health = health).getForecast(riga)

        assertTrue(health.of("only").isHealthy)
    }

    @Test
    fun `an offline failure is not held against the provider`() = runTest {
        val health = ProviderHealthRegistry()
        val provider = FakeWeatherProvider.failing("only", WeatherError.Offline)

        router(provider, health = health).getForecast(riga)

        assertEquals(0, health.of("only").consecutiveFailures)
    }

    @Test
    fun `the source does not change while it is still working`() = runTest {
        val preferred = FakeWeatherProvider.succeeding("preferred", nordicCoverage)
        val fallback = FakeWeatherProvider.succeeding("fallback")
        val router = router(preferred, fallback)

        val first = router.getForecast(riga).getOrThrow().provider.id
        val second = router.getForecast(riga, incumbentId = first).getOrThrow().provider.id

        assertEquals(first, second)
    }

    // --- keeping the hourly timeline going -----------------------------------

    @Test
    fun `a short hourly forecast is extended by the next candidate`() = runTest {
        // The shape of the real pairing: a regional model that wins on geography
        // but is hourly for only 60 hours, and a global one that is hourly for a
        // week.
        val regional = FakeWeatherProvider.succeeding("regional", nordicCoverage, hourlyHours = 60)
        val global = FakeWeatherProvider.succeeding("global", hourlyHours = 7 * 24)

        val forecast = router(regional, global).getForecast(riga).getOrThrow()

        assertEquals("the better model still owns the forecast", "regional", forecast.provider.id)
        assertEquals("global", forecast.supplement!!.provider.id)
        assertEquals(7 * 24, forecast.hourly.size)
        assertEquals(1, global.calls)
    }

    @Test
    fun `a forecast that already reaches far enough costs no extra request`() = runTest {
        val regional = FakeWeatherProvider.succeeding("regional", nordicCoverage, hourlyHours = 7 * 24)
        val global = FakeWeatherProvider.succeeding("global", hourlyHours = 7 * 24)

        val forecast = router(regional, global).getForecast(riga).getOrThrow()

        assertNull(forecast.supplement)
        assertEquals(0, global.calls)
    }

    @Test
    fun `a failed extension still yields the forecast that worked`() = runTest {
        val regional = FakeWeatherProvider.succeeding("regional", nordicCoverage, hourlyHours = 60)
        val global = FakeWeatherProvider.failing("global", WeatherError.Timeout)

        val forecast = router(regional, global).getForecast(riga).getOrThrow()

        // Extending a forecast must never be able to cost somebody one.
        assertEquals("regional", forecast.provider.id)
        assertEquals(60, forecast.hourly.size)
        assertNull(forecast.supplement)
    }

    @Test
    fun `nothing is extended when no other provider reaches further`() = runTest {
        val regional = FakeWeatherProvider.succeeding("regional", nordicCoverage, hourlyHours = 60)
        val alsoShort = FakeWeatherProvider.succeeding("also-short", hourlyHours = 48)

        val forecast = router(regional, alsoShort).getForecast(riga).getOrThrow()

        assertNull(forecast.supplement)
        assertEquals(0, alsoShort.calls)
    }

    @Test
    fun `extending counts as a success for the provider that supplied it`() = runTest {
        val health = ProviderHealthRegistry()
        health.recordFailure("global", now, WeatherError.Timeout)

        val regional = FakeWeatherProvider.succeeding("regional", nordicCoverage, hourlyHours = 60)
        val global = FakeWeatherProvider.succeeding("global", hourlyHours = 7 * 24)

        router(regional, global, health = health).getForecast(riga)

        assertTrue(health.of("global").isHealthy)
    }
}
