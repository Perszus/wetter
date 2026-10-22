// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.provider

import android.util.Log
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import lv.bolwarra.wetter.data.BuildConfig
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ForecastRequirements
import lv.bolwarra.wetter.domain.provider.ForecastStitcher
import lv.bolwarra.wetter.domain.provider.ProviderHealthRegistry
import lv.bolwarra.wetter.domain.provider.ProviderScore
import lv.bolwarra.wetter.domain.provider.ProviderSelectionContext
import lv.bolwarra.wetter.domain.provider.ScoringProviderSelector
import lv.bolwarra.wetter.domain.provider.WeatherFailure
import lv.bolwarra.wetter.domain.provider.WeatherProvider
import lv.bolwarra.wetter.domain.provider.WeatherProviderSelector
import lv.bolwarra.wetter.domain.provider.asWeatherError

/**
 * Chooses a provider, asks it, and falls back when that was the wrong answer. The
 * only component that knows more than one provider exists (docs/providers.md).
 *
 * When the winner stops being hourly short of the horizon, the router asks the
 * next candidate for the rest and joins the two ([ForecastStitcher]).
 *
 * No retry against the same provider within a refresh: a fallback is already
 * ranked and waiting. Backoff happens between refreshes, through
 * [ProviderHealthRegistry].
 */
internal class WeatherProviderRouter(
    private val providers: List<WeatherProvider>,
    private val selector: WeatherProviderSelector = ScoringProviderSelector(),
    private val health: ProviderHealthRegistry = ProviderHealthRegistry(),
    /**
     * Called after the registry changes, so what was learned survives a
     * restart. Optional and never awaited for a result: persistence failing is
     * not a reason to fail a forecast.
     */
    private val onHealthChanged: (suspend () -> Unit)? = null,
    private val requirements: ForecastRequirements = ForecastRequirements.Default,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    /**
     * @param incumbentId the provider that supplied the forecast already on
     *   screen, so a near-tie resolves in favour of not switching.
     */
    suspend fun getForecast(
        location: WeatherLocation,
        incumbentId: String? = null,
    ): Result<WeatherForecast> {
        val now = Instant.now(clock)
        val ranked = selector.rank(
            ProviderSelectionContext(
                location = location,
                providers = providers,
                health = health.snapshot(),
                now = now,
                requirements = requirements,
                incumbentId = incumbentId,
            ),
        )
        logRanking(location, ranked)

        val candidates = ranked.filter { it.eligible }.take(maxAttempts)
        if (candidates.isEmpty()) {
            return Result.failure(WeatherFailure(WeatherError.NoProviderAvailable))
        }

        return coroutineScope {
            // Asked alongside the first choice when its capabilities already say it stops
            // being hourly short of the horizon, so the two round trips are not in series.
            // The same answer is often the fallback too.
            val first = candidates.first().provider
            val reach = first.capabilities.hourlyHorizonHours
            val companion = extensionCandidate(first, reach.toLong(), ranked)
                ?.takeIf { reach < requirements.hourlyHorizon.toHours() }
            val early = companion?.let { EarlyAnswer(it.id, async { it.getForecast(location) }) }
            try {
                attempt(candidates, location, ranked, early)
            } finally {
                // Unused if the first choice turned out to reach far enough.
                early?.answer?.cancel()
            }
        }
    }

    /** A second provider's answer, requested before it was known to be needed. */
    private class EarlyAnswer(val providerId: String, val answer: Deferred<Result<WeatherForecast>>)

    private suspend fun attempt(
        candidates: List<ProviderScore>,
        location: WeatherLocation,
        ranked: List<ProviderScore>,
        early: EarlyAnswer?,
    ): Result<WeatherForecast> {
        var firstError: WeatherError? = null

        for (candidate in candidates) {
            val provider = candidate.provider
            val result = if (early != null && early.providerId == provider.id) {
                early.answer.await()
            } else {
                provider.getForecast(location)
            }

            result.onSuccess { forecast ->
                health.recordSuccess(provider.id, Instant.now(clock))
                rememberHealth()
                return Result.success(extendIfShort(forecast, location, ranked, early))
            }

            val error = result.exceptionOrNull()?.asWeatherError() ?: WeatherError.Unknown()
            if (firstError == null) firstError = error

            health.recordFailure(
                providerId = provider.id,
                now = Instant.now(clock),
                error = error,
                retryAfter = result.exceptionOrNull()?.retryAfter(),
            )
            rememberHealth()
            log("${provider.id} failed: $error")

            if (!shouldFailOver(error)) {
                return Result.failure(WeatherFailure(error))
            }
        }

        return Result.failure(WeatherFailure(firstError ?: WeatherError.Unknown()))
    }

    /**
     * Fills in the hourly timeline past where the chosen provider stops being hourly,
     * from the next provider in the same ranking. At most one extra request, and
     * optional: its failure never costs the forecast that already succeeded.
     */
    private suspend fun extendIfShort(
        forecast: WeatherForecast,
        location: WeatherLocation,
        ranked: List<ProviderScore>,
        early: EarlyAnswer? = null,
    ): WeatherForecast {
        val now = Instant.now(clock)
        if (!ForecastStitcher.needsExtending(forecast, requirements.hourlyHorizon, now)) {
            return forecast
        }

        val coveredHours = ForecastStitcher.hourlyCoverage(forecast, now).toHours()
        val candidate = ranked.firstOrNull { score ->
            score.eligible &&
                score.provider.id != forecast.provider.id &&
                score.provider.capabilities.hourlyHorizonHours > coveredHours
        }?.provider ?: return forecast

        log(
            "${forecast.provider.id} is hourly for ${coveredHours}h; extending with ${candidate.id}",
        )

        val result = if (early != null && early.providerId == candidate.id) {
            early.answer.await()
        } else {
            candidate.getForecast(location)
        }
        val extension = result.getOrNull()
        if (extension == null) {
            val error = result.exceptionOrNull()?.asWeatherError() ?: WeatherError.Unknown()
            health.recordFailure(
                providerId = candidate.id,
                now = Instant.now(clock),
                error = error,
                retryAfter = result.exceptionOrNull()?.retryAfter(),
            )
            rememberHealth()
            log("could not extend with ${candidate.id}: $error")
            return forecast
        }

        health.recordSuccess(candidate.id, Instant.now(clock))
        return ForecastStitcher.stitch(forecast, extension)
    }

    /** The best-ranked other provider that is hourly further than [coveredHours]. */
    private fun extensionCandidate(
        primary: WeatherProvider,
        coveredHours: Long,
        ranked: List<ProviderScore>,
    ): WeatherProvider? = ranked.firstOrNull { score ->
        score.eligible &&
            score.provider.id != primary.id &&
            score.provider.capabilities.hourlyHorizonHours > coveredHours
    }?.provider

    /**
     * Whether the next provider is worth trying: only when the failure was about this
     * provider (a timeout, a server error, a rate limit). No second provider fixes a
     * device that is offline, and a request one service rejected another most likely
     * will too (docs/providers.md).
     */
    private fun shouldFailOver(error: WeatherError): Boolean = when (error) {
        is WeatherError.Offline -> false
        is WeatherError.LocationUnavailable -> false
        is WeatherError.NoProviderAvailable -> false
        is WeatherError.Timeout -> true
        is WeatherError.MalformedResponse -> true
        is WeatherError.Unknown -> true
        is WeatherError.ProviderRejected ->
            error.status == TOO_MANY_REQUESTS || error.status >= FIRST_SERVER_ERROR
    }

    /** The current health of every provider, for the Advanced section. */
    fun healthSnapshot() = health.snapshot()

    private fun logRanking(location: WeatherLocation, ranked: List<ProviderScore>) {
        if (!BuildConfig.DEBUG) return
        // Coordinates are rounded before they reach the log. A debug log is still
        // a file on a device, and there is no reason for it to record where
        // somebody lives to five decimal places (docs/providers.md).
        val where = "%.1f, %.1f".format(location.latitude, location.longitude)
        log("ranking for $where")
        ranked.forEach { score ->
            val state = if (score.eligible) "%.1f".format(score.score) else "excluded"
            log("  ${score.provider.id}: $state: ${score.reasons.joinToString("; ")}")
        }
    }

    /**
     * Write the registry down, swallowing anything that goes wrong: a provider's
     * reputation is worth nothing compared to the forecast.
     */
    private suspend fun rememberHealth() {
        onHealthChanged?.let { runCatching { it() } }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "WeatherProviderRouter"

        /**
         * Preferred, then one fallback. A third attempt would mean a user waiting
         * through three timeouts, by which point the cached forecast they are
         * already looking at is the better answer.
         */
        const val DEFAULT_MAX_ATTEMPTS = 2

        private const val TOO_MANY_REQUESTS = 429
        private const val FIRST_SERVER_ERROR = 500
    }
}
