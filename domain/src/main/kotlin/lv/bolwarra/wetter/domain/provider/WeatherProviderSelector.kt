package lv.bolwarra.wetter.domain.provider

import java.time.Instant
import lv.bolwarra.wetter.domain.model.WeatherLocation

/** Everything the selector is allowed to look at. Nothing else may influence it. */
data class ProviderSelectionContext(
    val location: WeatherLocation,
    val providers: List<WeatherProvider>,
    val health: Map<String, ProviderHealth>,
    val now: Instant,
    val requirements: ForecastRequirements = ForecastRequirements.Default,
    /**
     * The provider that supplied the forecast currently on screen. Used only to
     * break near-ties in its own favour, so a refresh does not silently swap
     * sources for a point of score (docs/providers.md).
     */
    val incumbentId: String? = null,
)

/**
 * A provider's suitability, with its reasoning attached.
 *
 * The reasons exist so a decision can be explained — in a log line during
 * development, and eventually in the Advanced section. A selector that cannot
 * say why it chose is one nobody can debug (docs/providers.md).
 */
data class ProviderScore(
    val provider: WeatherProvider,
    val score: Double,
    val eligible: Boolean,
    val reasons: List<String>,
)

/**
 * Ranks providers for a location. The only place that decision is made —
 * not in view models, composables, the repository, the clients, or the location
 * code (docs/providers.md).
 */
interface WeatherProviderSelector {
    /** Highest first. Ineligible providers come last, and are never returned by [select]. */
    fun rank(context: ProviderSelectionContext): List<ProviderScore>
}

/** The provider to try first, or null when nothing can serve this location. */
fun WeatherProviderSelector.select(context: ProviderSelectionContext): WeatherProvider? =
    rank(context).firstOrNull { it.eligible }?.provider

/**
 * Scores each provider out of roughly ninety and sorts.
 *
 * The weights below are the whole policy. They are constants in one place rather
 * than a chain of `if (country == ...)`, so adding a provider is a data change
 * and adjusting the policy is an edit to six numbers (docs/providers.md).
 *
 * The ranking is a pure function of its context: the same location, providers
 * and health always produce the same order, with ties broken by provider id so
 * that even equal scores are stable (docs/providers.md).
 */
class ScoringProviderSelector : WeatherProviderSelector {

    override fun rank(context: ProviderSelectionContext): List<ProviderScore> {
        val scored = context.providers.map { score(it, context) }

        val ordered = scored.sortedWith(
            compareByDescending<ProviderScore> { it.eligible }
                .thenByDescending { it.score }
                .thenBy { it.provider.id },
        )

        return applyIncumbentPreference(ordered, context.incumbentId)
    }

    private fun score(provider: WeatherProvider, context: ProviderSelectionContext): ProviderScore {
        val reasons = mutableListOf<String>()
        val latitude = context.location.latitude
        val longitude = context.location.longitude

        if (!provider.coverage.serves(latitude, longitude)) {
            return ProviderScore(
                provider,
                0.0,
                eligible = false,
                reasons = listOf("Outside coverage"),
            )
        }

        val capabilities = provider.capabilities
        if (!capabilities.satisfies(context.requirements)) {
            val missing = (context.requirements.required - capabilities.variables)
                .joinToString { it.name.lowercase().replace('_', ' ') }
            val why = if (missing.isNotBlank()) {
                "Cannot supply $missing"
            } else {
                "Forecasts only ${capabilities.maximumForecastDays} days"
            }
            return ProviderScore(provider, 0.0, eligible = false, reasons = listOf(why))
        }

        var total = GEOGRAPHIC_BASE
        reasons += "Covers this location"

        val regional = provider.coverage.regionalStrength(latitude, longitude)
        if (regional > 0.0) {
            total += GEOGRAPHIC_REGIONAL * regional
            val region = provider.coverage.strongestRegionName(latitude, longitude)
            reasons += if (region != null) "Regional source for $region" else "Regional source"
        }

        val desired = capabilities.desiredCoverage(context.requirements)
        total += CAPABILITY * desired
        reasons += when {
            desired >= 1.0 -> "Supplies every variable Wetter uses"
            desired >= 0.6 -> "Supplies most variables Wetter uses"
            else -> "Supplies the essentials only"
        }

        val resolutionKm = provider.coverage
            .resolutionKmAt(latitude, longitude, capabilities.resolutionKm)
        total += RESOLUTION * resolutionScore(resolutionKm)
        resolutionKm?.let { reasons += "Resolution about $it km here" }

        total += FRESHNESS * freshnessScore(capabilities.updateIntervalHours)

        val health = context.health[provider.id] ?: ProviderHealth(provider.id)
        when {
            health.isResting(context.now) -> {
                total -= RESTING_PENALTY
                reasons += "Recently unavailable — resting"
            }

            health.consecutiveFailures > 0 -> {
                total -= FAILURE_PENALTY * health.consecutiveFailures
                reasons += "${health.consecutiveFailures} recent failure(s)"
            }
        }

        // A resting provider stays eligible on purpose. If every provider is
        // resting the app must still try the least-bad one, or an outage on both
        // sides of a fallback pair would leave Wetter unable to recover on its
        // own (docs/providers.md — no permanent demotion).
        return ProviderScore(provider, total, eligible = true, reasons = reasons)
    }

    private fun applyIncumbentPreference(
        ordered: List<ProviderScore>,
        incumbentId: String?,
    ): List<ProviderScore> {
        if (incumbentId == null) return ordered
        val leader = ordered.firstOrNull { it.eligible } ?: return ordered
        if (leader.provider.id == incumbentId) return ordered

        val incumbent = ordered.firstOrNull { it.eligible && it.provider.id == incumbentId }
            ?: return ordered
        if (leader.score - incumbent.score > STICKY_MARGIN) return ordered

        val kept = incumbent.copy(reasons = incumbent.reasons + "Kept as the current source")
        return listOf(kept) + ordered.filterNot { it.provider.id == incumbentId }
    }

    private fun resolutionScore(resolutionKm: Double?): Double {
        if (resolutionKm == null) return UNKNOWN_SCORE
        return ((COARSEST_USEFUL_KM - resolutionKm) / (COARSEST_USEFUL_KM - FINEST_KM))
            .coerceIn(0.0, 1.0)
    }

    private fun freshnessScore(updateIntervalHours: Double?): Double {
        if (updateIntervalHours == null) return UNKNOWN_SCORE
        return ((STALEST_USEFUL_HOURS - updateIntervalHours) / STALEST_USEFUL_HOURS)
            .coerceIn(0.0, 1.0)
    }

    companion object {
        /** Serving the location at all. Every eligible provider earns this. */
        const val GEOGRAPHIC_BASE = 20.0

        /** Being a regional speciality where the user actually is. The largest single term. */
        const val GEOGRAPHIC_REGIONAL = 30.0

        /** Supplying the optional variables the screen can use. */
        const val CAPABILITY = 25.0

        /** Model grid spacing — it decides whether a shower exists at all. */
        const val RESOLUTION = 10.0

        /** How often a new run is published. Small: every candidate updates hourly. */
        const val FRESHNESS = 5.0

        /** Enough to lose to any healthy provider, not enough to make it unusable. */
        const val RESTING_PENALTY = 100.0
        const val FAILURE_PENALTY = 5.0

        /**
         * How far behind the leader the current source may fall and still be
         * kept. Wide enough to absorb a resolution or capability difference,
         * narrow enough that a resting provider always loses its place.
         */
        const val STICKY_MARGIN = 8.0

        private const val FINEST_KM = 1.0
        private const val COARSEST_USEFUL_KM = 25.0
        private const val STALEST_USEFUL_HOURS = 12.0

        /** What an unstated resolution or update interval is worth: slightly below average. */
        private const val UNKNOWN_SCORE = 0.4
    }
}
