package lv.bolwarra.wetter.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lv.bolwarra.wetter.data.provider.WeatherProviderRouter
import lv.bolwarra.wetter.data.provider.riga
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.provider.FakeWeatherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The offline-first contract: reading never waits on the network, and a failed
 * refresh never costs the user the forecast they were already looking at.
 */
class WeatherRepositoryTest {

    private val now = Instant.parse("2026-03-14T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun repository(
        provider: FakeWeatherProvider,
        cache: ForecastCache = InMemoryForecastCache(),
    ) = WeatherRepository(
        router = WeatherProviderRouter(listOf(provider), clock = clock),
        cache = cache,
        clock = clock,
    )

    @Test
    fun `an empty cache reads as nothing rather than blocking`() = runTest {
        val repository = repository(FakeWeatherProvider.succeeding("source"))

        assertNull(repository.observe(riga).first())
        assertNull(repository.cached(riga))
    }

    @Test
    fun `a refresh stores the forecast and the observer sees it`() = runTest {
        val repository = repository(FakeWeatherProvider.succeeding("source"))

        val fetched = repository.refresh(riga).getOrThrow()

        assertEquals("source", fetched.provider.id)
        assertEquals(fetched, repository.observe(riga).first())
        assertEquals(fetched, repository.cached(riga))
    }

    @Test
    fun `a failed refresh leaves the cached forecast untouched`() = runTest {
        val cache = InMemoryForecastCache()
        val good = repository(FakeWeatherProvider.succeeding("source"), cache)
        val stored = good.refresh(riga).getOrThrow()

        val broken = repository(
            FakeWeatherProvider.failing("source", WeatherError.Timeout),
            cache,
        )
        val result = broken.refresh(riga)

        assertTrue(result.isFailure)
        assertEquals(
            "the forecast on screen must survive a failed update",
            stored,
            broken.cached(riga),
        )
    }

    @Test
    fun `nothing cached means a refresh is needed`() {
        val repository = repository(FakeWeatherProvider.succeeding("source"))
        assertTrue(repository.needsRefresh(null))
    }

    @Test
    fun `a forecast inside the freshness window is left alone`() {
        val repository = repository(FakeWeatherProvider.succeeding("source"))
        val fresh = FakeWeatherProvider.forecastFrom("source", riga, at = now.minus(Duration.ofMinutes(5)))

        assertFalse(repository.needsRefresh(fresh))
    }

    @Test
    fun `a forecast past the freshness window is replaced`() {
        val repository = repository(FakeWeatherProvider.succeeding("source"))
        val stale = FakeWeatherProvider.forecastFrom("source", riga, at = now.minus(Duration.ofHours(2)))

        assertTrue(repository.needsRefresh(stale))
        assertEquals(Duration.ofHours(2), repository.age(stale))
    }

    @Test
    fun `two places do not overwrite each other in the cache`() = runTest {
        val cache = InMemoryForecastCache()
        val repository = repository(FakeWeatherProvider.succeeding("source"), cache)
        val elsewhere = riga.copy(name = "Oslo", latitude = 59.9139, longitude = 10.7522)

        repository.refresh(riga)
        repository.refresh(elsewhere)

        assertEquals("Rīga", repository.cached(riga)!!.location.name)
        assertEquals("Oslo", repository.cached(elsewhere)!!.location.name)
    }

    @Test
    fun `coordinates that differ below the cache resolution hit the same entry`() = runTest {
        val repository = repository(FakeWeatherProvider.succeeding("source"))
        repository.refresh(riga)

        // A position read from the device wobbles in the far decimals every time.
        // Treating those as different places would make every refresh a miss.
        val jittered = riga.copy(latitude = riga.latitude + 0.000004)

        assertEquals(repository.cached(riga), repository.cached(jittered))
    }
}
