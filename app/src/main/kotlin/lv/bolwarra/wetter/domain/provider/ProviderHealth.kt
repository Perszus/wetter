package lv.bolwarra.wetter.domain.provider

import lv.bolwarra.wetter.domain.model.WeatherError
import java.time.Duration
import java.time.Instant
import kotlin.math.min

/**
 * What recently happened when Wetter talked to a provider.
 *
 * Deliberately not monitoring. Its whole purpose is to stop Wetter reaching for
 * a provider that just failed twice in a row, and to let it go back once the
 * cooldown expires. Nothing is permanent and nothing is aggregated
 * (docs/providers.md).
 */
data class ProviderHealth(
    val providerId: String,
    val lastSuccess: Instant? = null,
    val lastFailure: Instant? = null,
    val consecutiveFailures: Int = 0,
    /**
     * Set when a provider asked to be left alone — an HTTP 429, or a
     * Retry-After. Honoured ahead of the computed backoff.
     */
    val cooldownUntil: Instant? = null,
) {
    val isHealthy: Boolean get() = consecutiveFailures == 0

    /** True while the provider should be passed over in favour of a fallback. */
    fun isResting(now: Instant): Boolean {
        cooldownUntil?.let { if (now.isBefore(it)) return true }
        val until = backoffUntil() ?: return false
        return now.isBefore(until)
    }

    private fun backoffUntil(): Instant? {
        if (consecutiveFailures < FAILURES_BEFORE_BACKOFF) return null
        val failedAt = lastFailure ?: return null
        val steps = consecutiveFailures - FAILURES_BEFORE_BACKOFF
        val minutes = min(
            BASE_BACKOFF_MINUTES shl min(steps, MAX_BACKOFF_SHIFT),
            MAX_BACKOFF_MINUTES,
        )
        return failedAt.plus(Duration.ofMinutes(minutes.toLong()))
    }

    fun afterSuccess(now: Instant) = copy(
        lastSuccess = now,
        consecutiveFailures = 0,
        cooldownUntil = null,
    )

    /**
     * A failure that says nothing about the provider — no network on this device
     * — must not count against it, or a week on a train would demote every
     * provider Wetter has.
     */
    fun afterFailure(now: Instant, error: WeatherError, retryAfter: Duration? = null): ProviderHealth {
        if (error is WeatherError.Offline) return this
        return copy(
            lastFailure = now,
            consecutiveFailures = consecutiveFailures + 1,
            cooldownUntil = retryAfter?.let { now.plus(it) } ?: cooldownUntil,
        )
    }

    companion object {
        /**
         * One failure is noise — a dropped connection, a lost packet. Backoff
         * starts at the second consecutive one.
         */
        const val FAILURES_BEFORE_BACKOFF = 2
        const val BASE_BACKOFF_MINUTES = 5
        const val MAX_BACKOFF_SHIFT = 4
        const val MAX_BACKOFF_MINUTES = 120
    }
}

/**
 * Health for every provider, addressed by id.
 *
 * Kept in memory: it describes the last few minutes, and a forecast app that has
 * been closed long enough to be evicted should start again from a clean slate
 * rather than resume a stale grudge. If that turns out to be wrong, this is the
 * one type that has to change.
 */
class ProviderHealthRegistry {

    private val health = mutableMapOf<String, ProviderHealth>()

    fun of(providerId: String): ProviderHealth =
        health[providerId] ?: ProviderHealth(providerId)

    fun recordSuccess(providerId: String, now: Instant) {
        health[providerId] = of(providerId).afterSuccess(now)
    }

    fun recordFailure(
        providerId: String,
        now: Instant,
        error: WeatherError,
        retryAfter: Duration? = null,
    ) {
        health[providerId] = of(providerId).afterFailure(now, error, retryAfter)
    }

    fun snapshot(): Map<String, ProviderHealth> = health.toMap()
}
