package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.bolwarra.wetter.domain.forecast.EnsembleSource
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.forecast.ModelEnsemble
import lv.bolwarra.wetter.domain.forecast.PrecipitationFusion
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.radar.RadarNowcast
import lv.bolwarra.wetter.domain.radar.RadarNowcaster
import lv.bolwarra.wetter.domain.radar.RadarSource

/**
 * The precipitation timeline, radar and model together.
 *
 * Radar is fetched, extrapolated and blended here rather than in the screen,
 * because every part of that is expensive or slow and none of it should happen
 * again because a list scrolled.
 *
 * ### The cache is keyed on the sweep, not on the clock
 *
 * An elapsed-time cache is the obvious approach and it wastes most of what it
 * saves. Hold a sweep for five minutes against a product published every ten and
 * it re-downloads roughly three times for every two genuinely new images; hold
 * it for ten and it misses the new one for up to ten minutes, which defeats the
 * point of carrying radar at all. Either way it is guessing at something the
 * source will simply say.
 *
 * So it asks. The index naming the available frames is a fraction of the size of
 * the imagery, so the expensive half of the transaction happens only when the
 * cheap half reports something new. Tiles are then fetched exactly once per
 * sweep.
 *
 * The index is not polled either. Having taken a sweep, the next is not due for
 * [RadarSource.sweepInterval], and nothing is asked in that window at all -
 * otherwise a per-minute index check would quietly cost more over an hour than
 * the tiles it was saving. Only once a sweep is overdue does it start looking,
 * and then it finds it within a minute of publication.
 *
 * ### Why it is in memory
 *
 * A radar nowcast is worthless within the hour, so there is nothing to keep
 * across a restart - unlike a forecast, which is still worth showing a day
 * later. Twenty tiles of PNG in the database would buy nothing and cost a
 * migration.
 */
class NowcastRepository internal constructor(
    private val source: RadarSource,
    private val ensembles: EnsembleSource,
    private val clock: Clock = Clock.systemUTC(),
    private val freshFor: Duration = DEFAULT_FRESH_FOR,
) {

    /** Shown wherever radar contributes. Several sources require it. */
    val attribution: String get() = source.attribution

    private val mutex = Mutex()
    private var cached: Cached? = null

    private val ensembleMutex = Mutex()
    private var cachedEnsemble: CachedEnsemble? = null

    private data class CachedEnsemble(
        val location: WeatherLocation,
        val at: Instant,
        val ensemble: ModelEnsemble?,
    )

    private data class Cached(
        val location: WeatherLocation,
        /** The sweep this was built from - the cache key that matters. */
        val sweepAt: Instant?,
        /** When the source was last asked, so a fruitless ask is not repeated. */
        val checkedAt: Instant,
        val nowcast: RadarNowcast?,
    )

    /**
     * The radar projection for a place, or null when radar has nothing to say.
     *
     * Null is a normal answer, not a failure: outside radar coverage, or with too
     * little echo to track, there is genuinely no radar-based forecast to give,
     * and the caller falls back to the model.
     */
    suspend fun nowcast(location: WeatherLocation): RadarNowcast? = mutex.withLock {
        val now = Instant.now(clock)
        val held = cached?.takeIf { it.location.sameGrid(location) }

        if (held != null && !worthAsking(held, now)) return@withLock held.nowcast

        // The cheap half: has anything new been published? A failure here is not
        // an error worth surfacing - it just means falling back to the elapsed
        // clock, so a source with a sick index degrades to the old behaviour
        // rather than to no radar at all.
        val latest = source.latestSweep().getOrNull()
        if (held != null && latest != null && latest == held.sweepAt) {
            cached = held.copy(checkedAt = now)
            return@withLock held.nowcast
        }

        val frames = source.recentFrames(location.latitude, location.longitude, FRAMES)
            .getOrNull()
            .orEmpty()
        val nowcast = if (frames.size < 2) null else RadarNowcaster.nowcast(frames, LEADS)
        cached = Cached(
            location = location,
            sweepAt = frames.lastOrNull()?.at ?: latest,
            checkedAt = now,
            nowcast = nowcast,
        )
        nowcast
    }

    /**
     * Whether it is worth troubling the source at all.
     *
     * No, while the sweep in hand is younger than the interval - nothing else
     * exists yet, so any request would return what is already held. After that,
     * yes, but no more often than [RECHECK_INTERVAL], so an overdue sweep is
     * found promptly without the waiting itself becoming the expense.
     *
     * Holding no sweep at all means the last attempt failed outright, and that
     * backs off much further. Retrying a broken source every minute is how an
     * app ends up spending more battery when a service is down than when it is
     * up.
     */
    private fun worthAsking(held: Cached, now: Instant): Boolean {
        val sweepAt = held.sweepAt
            ?: return Duration.between(held.checkedAt, now) >= freshFor
        val due = sweepAt.plus(source.sweepInterval)
        if (now.isBefore(due)) return false
        return Duration.between(held.checkedAt, now) >= RECHECK_INTERVAL
    }

    /**
     * Several models over the same hours, for measuring how hard each one is to
     * forecast. Cached for an hour, which is how often the runs it summarises
     * are published.
     */
    suspend fun ensemble(location: WeatherLocation): ModelEnsemble? = ensembleMutex.withLock {
        val now = Instant.now(clock)
        val held = cachedEnsemble
        if (held != null &&
            held.location.sameGrid(location) &&
            Duration.between(held.at, now) < ENSEMBLE_FRESH_FOR
        ) {
            return@withLock held.ensemble
        }
        val fetched = ensembles.ensemble(location).getOrNull()?.takeUnless { it.isEmpty }
        cachedEnsemble = CachedEnsemble(location, now, fetched)
        fetched
    }

    /**
     * The fused timeline for a place: radar where it is worth having, model
     * throughout, and each weighted by how much it deserves rather than by which
     * one it is.
     */
    suspend fun timeline(
        forecast: WeatherForecast,
        from: Instant,
        step: Duration = STEP,
        steps: Int = DEFAULT_STEPS,
    ): List<FusedPrecipitation> {
        val radar = nowcast(forecast.location)
            ?.seriesAt(forecast.location.latitude, forecast.location.longitude)
            .orEmpty()
        return PrecipitationFusion.fuse(
            hourly = forecast.hourly,
            radar = radar,
            from = from,
            step = step,
            steps = steps,
            ensemble = ensemble(forecast.location),
        )
    }

    /**
     * Two places share a radar answer when they land on the same tile block.
     *
     * Moving across a room must not refetch twenty tiles, and at these zooms a
     * pixel is most of a kilometre, so anything closer than that is the same
     * reading anyway.
     */
    private fun WeatherLocation.sameGrid(other: WeatherLocation): Boolean =
        kotlin.math.abs(latitude - other.latitude) < GRID_TOLERANCE_DEGREES &&
            kotlin.math.abs(longitude - other.longitude) < GRID_TOLERANCE_DEGREES

    companion object {
        /**
         * Three sweeps: two for the motion, a third so the intensity trend has
         * something to be measured across.
         */
        const val FRAMES = 3

        /**
         * Now, then ten-minute steps out to two hours.
         *
         * The series starts at zero on purpose. That step is the latest sweep
         * itself rather than a projection of it - rain that is falling, observed
         * - and it is the most trustworthy number in the whole timeline.
         */
        val LEADS: List<Duration> = (0..12).map { Duration.ofMinutes(it * 10L) }

        val STEP: Duration = Duration.ofMinutes(10)

        /** Six hours, which is what the Today page draws. */
        const val DEFAULT_STEPS = 36

        /**
         * How long to wait between asks once a sweep is overdue.
         *
         * A minute, matching how often the screen rebuilds its timeline, so a
         * newly published sweep is on screen within a minute of existing. This
         * only applies in the short window after one is due; for the ten minutes
         * before that, nothing is asked.
         */
        val RECHECK_INTERVAL: Duration = Duration.ofMinutes(1)

        /**
         * How long to leave a source alone after it failed to give anything at
         * all. Everything that succeeds keys on the sweep itself instead.
         */
        val DEFAULT_FRESH_FOR: Duration = Duration.ofMinutes(5)

        /** Model runs publish hourly, so anything fresher gets the same numbers. */
        val ENSEMBLE_FRESH_FOR: Duration = Duration.ofHours(1)

        /** About a kilometre, the scale a radar pixel already averages over. */
        private const val GRID_TOLERANCE_DEGREES = 0.01
    }
}
