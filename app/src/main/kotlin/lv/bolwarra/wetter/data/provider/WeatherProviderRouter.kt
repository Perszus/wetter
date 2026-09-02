package lv.bolwarra.wetter.data.provider

import android.util.Log
import lv.bolwarra.wetter.BuildConfig
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
import java.time.Clock
import java.time.Instant

/**
 * Chooses a provider, asks it, and falls back when that was the wrong answer.
 *
 * Everything above this sees one method that returns one forecast. The router is
 * the only component that knows more than one provider exists (docs/providers.md).
 *
 * It also makes sure there is always an hourly timeline to draw. The provider
 * that wins on geography is often not the one with the longest hourly reach, so
 * when the winner stops being hourly well short of the horizon Wetter needs, the
 * router asks the next candidate for the rest and joins the two — see
 * [ForecastStitcher] for why that is done this way and not by stretching the
 * coarse steps.
 *
 * There is deliberately no retry against the same provider inside a single
 * refresh. A provider that just timed out will most likely time out again a
 * second later, and the alternative — a fallback that is probably healthy — is
 * already ranked and waiting. Backoff happens between refreshes instead, through
 * [ProviderHealthRegistry], which is what keeps Wetter from hammering a service
 * that is having a bad afternoon (docs/providers.md).
 */
class WeatherProviderRouter(
    private val providers: List<WeatherProvider>,
    private val selector: WeatherProviderSelector = ScoringProviderSelector(),
    private val health: ProviderHealthRegistry = ProviderHealthRegistry(),
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

        var firstError: WeatherError? = null

        for (candidate in candidates) {
            val provider = candidate.provider
            val result = provider.getForecast(location)

            result.onSuccess { forecast ->
                health.recordSuccess(provider.id, Instant.now(clock))
                return Result.success(extendIfShort(forecast, location, ranked))
            }

            val error = result.exceptionOrNull()?.asWeatherError() ?: WeatherError.Unknown()
            if (firstError == null) firstError = error

            health.recordFailure(
                providerId = provider.id,
                now = Instant.now(clock),
                error = error,
                retryAfter = result.exceptionOrNull()?.retryAfter(),
            )
            log("${provider.id} failed: $error")

            if (!shouldFailOver(error)) {
                return Result.failure(WeatherFailure(error))
            }
        }

        return Result.failure(WeatherFailure(firstError ?: WeatherError.Unknown()))
    }

    /**
     * Fills in the hourly timeline past the point the chosen provider stops
     * being hourly.
     *
     * At most one extra request, and it is entirely optional: if the second
     * provider fails, the user still gets the forecast that already succeeded.
     * Extending a forecast must never be able to cost somebody one.
     *
     * The candidate is taken from the same ranking that chose the primary, so
     * the second-best provider for the location is also the one that extends it.
     */
    private suspend fun extendIfShort(
        forecast: WeatherForecast,
        location: WeatherLocation,
        ranked: List<ProviderScore>,
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

        log("${forecast.provider.id} is hourly for ${coveredHours}h; extending with ${candidate.id}")

        val result = candidate.getForecast(location)
        val extension = result.getOrNull()
        if (extension == null) {
            val error = result.exceptionOrNull()?.asWeatherError() ?: WeatherError.Unknown()
            health.recordFailure(
                providerId = candidate.id,
                now = Instant.now(clock),
                error = error,
                retryAfter = result.exceptionOrNull()?.retryAfter(),
            )
            log("could not extend with ${candidate.id}: $error")
            return forecast
        }

        health.recordSuccess(candidate.id, Instant.now(clock))
        return ForecastStitcher.stitch(forecast, extension)
    }

    /**
     * Whether the next provider is worth trying.
     *
     * The question is whether the failure was about *this* provider. A timeout, a
     * server error or a rate limit are; being offline and a rejected request are
     * not — no second provider fixes a device with no connection, and a request
     * the first service considered malformed is a bug that another service will
     * most likely also reject, so trying again would be two wasted calls rather
     * than one (docs/providers.md).
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
            log("  ${score.provider.id}: $state — ${score.reasons.joinToString("; ")}")
        }
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
