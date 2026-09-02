package lv.bolwarra.wetter.domain.provider

import java.time.Instant

/**
 * Where a forecast came from, travelling with the forecast itself.
 *
 * This is attached by the provider that produced the data, stored alongside the
 * cached forecast, and read back offline — so "Source: MET Norway · updated
 * 06:00" stays true even when nothing can be fetched (docs/providers.md).
 *
 * It is shown in the Advanced section and nowhere else. The main screen never
 * mentions a provider: someone looking at a rain timeline is deciding whether to
 * take a coat, not auditing a data supply chain (docs/providers.md).
 */
data class ProviderMetadata(
    /** Stable identifier. Persisted, so it must not change once released. */
    val id: String,
    /** Human-readable name, as the provider's terms require it to be written. */
    val name: String,
    /** The meteorological model behind the numbers, when it is published. */
    val model: String?,
    val resolutionKm: Double?,
    /** When the provider generated this forecast run — not when Wetter fetched it. */
    val forecastGeneratedAt: Instant?,
    /** Required attribution text, shown verbatim in About. */
    val attribution: String,
)
