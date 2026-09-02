package lv.bolwarra.wetter.domain.provider

import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

/**
 * The selection policy, tested as a pure function.
 *
 * Every case docs/providers.md asks for is here: geographic preference, global
 * fallback, capability filtering, failure, recovery, determinism, and the
 * boundary conditions — which are the ones most likely to be wrong, because a
 * rectangle has edges and the weather does not.
 */
class ScoringProviderSelectorTest {

    private val selector = ScoringProviderSelector()
    private val now: Instant = Instant.parse("2026-03-14T09:00:00Z")

    private val nordics = GeoBox(south = 54.0, north = 72.0, west = 4.0, east = 32.0)

    /**
     * A global provider running a fine-grid model over the Nordics only — the
     * shape MET Norway actually has, and the shape that makes geography rather
     * than a single resolution figure decide the outcome.
     */
    private val regional = FakeWeatherProvider(
        id = "regional",
        coverage = ProviderCoverage(
            isGlobal = true,
            preferredRegions = listOf(ProviderRegion("the Nordics", nordics, resolutionKm = 2.5)),
        ),
        capabilities = FakeWeatherProvider.everyVariable(resolutionKm = 11.0),
    )

    private val global = FakeWeatherProvider(
        id = "global",
        capabilities = FakeWeatherProvider.everyVariable(resolutionKm = 11.0),
    )

    private fun at(latitude: Double, longitude: Double) = WeatherLocation(
        name = "test",
        latitude = latitude,
        longitude = longitude,
        zone = ZoneId.of("UTC"),
    )

    private fun context(
        location: WeatherLocation,
        providers: List<WeatherProvider> = listOf(global, regional),
        health: Map<String, ProviderHealth> = emptyMap(),
        incumbentId: String? = null,
        requirements: ForecastRequirements = ForecastRequirements.Default,
    ) = ProviderSelectionContext(
        location = location,
        providers = providers,
        health = health,
        now = now,
        requirements = requirements,
        incumbentId = incumbentId,
    )

    // --- geographic preference -------------------------------------------------

    @Test
    fun `inside its region the regional provider wins`() {
        // Riga.
        val chosen = selector.select(context(at(56.95, 24.10)))
        assertEquals("regional", chosen?.id)
    }

    @Test
    fun `far outside its region the global provider wins`() {
        // Buenos Aires — nothing regional reaches this far.
        val chosen = selector.select(context(at(-34.60, -58.38)))
        assertEquals("global", chosen?.id)
    }

    @Test
    fun `a provider that does not serve the location is not eligible`() {
        val restricted = FakeWeatherProvider(
            id = "restricted",
            coverage = ProviderCoverage(
                isGlobal = false,
                preferredRegions = listOf(ProviderRegion("the Nordics", nordics)),
            ),
        )
        val ranked = selector.rank(
            context(at(-34.60, -58.38), providers = listOf(global, restricted)),
        )
        val restrictedScore = ranked.single { it.provider.id == "restricted" }
        assertFalse(restrictedScore.eligible)
        assertEquals(listOf("Outside coverage"), restrictedScore.reasons)
        assertEquals("global", selector.select(context(at(-34.60, -58.38), listOf(global, restricted)))?.id)
    }

    @Test
    fun `nothing is selected when no provider serves the location`() {
        val restricted = FakeWeatherProvider(
            id = "restricted",
            coverage = ProviderCoverage(isGlobal = false),
        )
        assertNull(selector.select(context(at(0.0, 0.0), providers = listOf(restricted))))
    }

    // --- boundary conditions ---------------------------------------------------

    @Test
    fun `the regional preference fades rather than stopping at the border`() {
        val inside = strengthOf("regional", at(55.0, 20.0))
        val justOutside = strengthOf("regional", at(53.0, 20.0))
        val wellOutside = strengthOf("regional", at(50.0, 20.0))

        assertTrue("inside should beat just outside", inside > justOutside)
        assertTrue("just outside should still beat well outside", justOutside > wellOutside)
        assertEquals("beyond the grace distance the bonus is gone", 0.0, wellOutside, 1e-9)
    }

    @Test
    fun `the fine-grid model does not reach past the region that runs it`() {
        val inside = regional.coverage.resolutionKmAt(56.95, 24.10, baseline = 11.0)
        val outside = regional.coverage.resolutionKmAt(-34.60, -58.38, baseline = 11.0)
        assertEquals(2.5, inside!!, 1e-9)
        assertEquals(11.0, outside!!, 1e-9)
    }

    @Test
    fun `a point one degree beyond the edge keeps part of its regional bonus`() {
        // 53.0 is 1 degree south of the box, half of the 2-degree grace distance.
        val strength = regional.coverage.regionalStrength(53.0, 20.0)
        assertEquals(0.5, strength, 1e-9)
    }

    @Test
    fun `a box spanning the antimeridian contains points on both sides of it`() {
        val pacific = GeoBox(south = -20.0, north = 20.0, west = 170.0, east = -170.0)
        assertTrue(pacific.crossesAntimeridian)
        assertTrue(pacific.contains(0.0, 175.0))
        assertTrue(pacific.contains(0.0, -175.0))
        assertFalse(pacific.contains(0.0, 0.0))
        assertEquals(0.0, pacific.degreesOutside(0.0, 179.9), 1e-9)
    }

    // --- capability filtering --------------------------------------------------

    @Test
    fun `a provider without precipitation is excluded outright`() {
        val temperatureOnly = FakeWeatherProvider(
            id = "temperature-only",
            capabilities = ProviderCapabilities(
                variables = setOf(WeatherVariable.HOURLY, WeatherVariable.DAILY),
                maximumForecastDays = 7,
                resolutionKm = 1.0,
                updateIntervalHours = 1.0,
            ),
        )
        val ranked = selector.rank(context(at(56.95, 24.10), listOf(temperatureOnly, global)))
        val excluded = ranked.single { it.provider.id == "temperature-only" }

        assertFalse(excluded.eligible)
        assertTrue(excluded.reasons.single().contains("precipitation"))
        assertEquals("global", selector.select(context(at(56.95, 24.10), listOf(temperatureOnly, global)))?.id)
    }

    @Test
    fun `a provider whose forecast is too short is excluded`() {
        val shortRange = FakeWeatherProvider(
            id = "short",
            capabilities = FakeWeatherProvider.everyVariable(maximumForecastDays = 1),
        )
        val ranked = selector.rank(context(at(56.95, 24.10), listOf(shortRange, global)))
        assertFalse(ranked.single { it.provider.id == "short" }.eligible)
    }

    @Test
    fun `missing optional variables cost points but do not exclude`() {
        val essentialsOnly = FakeWeatherProvider(
            id = "essentials",
            capabilities = ProviderCapabilities(
                variables = setOf(WeatherVariable.HOURLY, WeatherVariable.PRECIPITATION),
                maximumForecastDays = 7,
                resolutionKm = 11.0,
                updateIntervalHours = 1.0,
            ),
        )
        val ranked = selector.rank(context(at(0.0, 0.0), listOf(essentialsOnly, global)))
        val essentials = ranked.single { it.provider.id == "essentials" }

        assertTrue(essentials.eligible)
        assertTrue(essentials.score < ranked.single { it.provider.id == "global" }.score)
        assertEquals("global", ranked.first().provider.id)
    }

    // --- failure and recovery --------------------------------------------------

    @Test
    fun `a resting provider loses its place to the fallback`() {
        val health = mapOf(
            "regional" to ProviderHealth(
                providerId = "regional",
                lastFailure = now.minus(Duration.ofMinutes(1)),
                consecutiveFailures = 2,
            ),
        )
        assertEquals("global", selector.select(context(at(56.95, 24.10), health = health))?.id)
    }

    @Test
    fun `a single failure is treated as noise and does not cost the lead`() {
        val health = mapOf(
            "regional" to ProviderHealth(
                providerId = "regional",
                lastFailure = now.minus(Duration.ofMinutes(1)),
                consecutiveFailures = 1,
            ),
        )
        assertEquals("regional", selector.select(context(at(56.95, 24.10), health = health))?.id)
    }

    @Test
    fun `once the cooldown has passed the preferred provider is used again`() {
        val health = mapOf(
            "regional" to ProviderHealth(
                providerId = "regional",
                lastFailure = now.minus(Duration.ofHours(6)),
                consecutiveFailures = 2,
            ),
        )
        assertEquals("regional", selector.select(context(at(56.95, 24.10), health = health))?.id)
    }

    @Test
    fun `a resting provider is still selectable when everything is resting`() {
        val resting = { id: String ->
            id to ProviderHealth(id, lastFailure = now.minus(Duration.ofMinutes(1)), consecutiveFailures = 3)
        }
        val health = mapOf(resting("regional"), resting("global"))
        // Nothing is excluded, so the app can recover on its own next refresh.
        assertNotNull(selector.select(context(at(56.95, 24.10), health = health)))
    }

    @Test
    fun `being offline is not held against a provider`() {
        val health = ProviderHealth("regional")
            .afterFailure(now, WeatherError.Offline)
            .afterFailure(now, WeatherError.Offline)
        assertEquals(0, health.consecutiveFailures)
        assertFalse(health.isResting(now))
    }

    @Test
    fun `an explicit retry-after is honoured ahead of the computed backoff`() {
        val health = ProviderHealth("regional")
            .afterFailure(now, WeatherError.ProviderRejected(429), retryAfter = Duration.ofHours(1))
        assertTrue(health.isResting(now.plus(Duration.ofMinutes(59))))
        assertFalse(health.isResting(now.plus(Duration.ofMinutes(61))))
    }

    // --- determinism and continuity --------------------------------------------

    @Test
    fun `the same inputs always produce the same ranking`() {
        val first = selector.rank(context(at(56.95, 24.10))).map { it.provider.id }
        val reordered = selector.rank(
            context(at(56.95, 24.10), providers = listOf(regional, global)),
        ).map { it.provider.id }

        assertEquals(first, reordered)
    }

    @Test
    fun `equal providers are ordered by id so ties never wobble`() {
        val b = FakeWeatherProvider(id = "b")
        val a = FakeWeatherProvider(id = "a")
        val ranked = selector.rank(context(at(0.0, 0.0), providers = listOf(b, a)))
        assertEquals(listOf("a", "b"), ranked.map { it.provider.id })
    }

    @Test
    fun `the current source is kept when it is only just behind`() {
        // At 0,0 both are eligible; global leads only on optional-variable parity,
        // so the gap is inside the sticky margin.
        val ranked = selector.rank(context(at(0.0, 0.0), incumbentId = "regional"))
        assertEquals("regional", ranked.first().provider.id)
        assertTrue(ranked.first().reasons.contains("Kept as the current source"))
    }

    @Test
    fun `the current source is dropped when it falls clearly behind`() {
        val health = mapOf(
            "regional" to ProviderHealth(
                providerId = "regional",
                lastFailure = now.minus(Duration.ofMinutes(1)),
                consecutiveFailures = 3,
            ),
        )
        val chosen = selector.select(
            context(at(56.95, 24.10), health = health, incumbentId = "regional"),
        )
        assertEquals("global", chosen?.id)
    }

    private fun strengthOf(providerId: String, location: WeatherLocation): Double {
        val provider = listOf(global, regional).single { it.id == providerId }
        return provider.coverage.regionalStrength(location.latitude, location.longitude)
    }
}
