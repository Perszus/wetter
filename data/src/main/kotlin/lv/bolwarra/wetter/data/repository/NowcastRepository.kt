package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lv.bolwarra.wetter.domain.forecast.EnsembleSource
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.forecast.ModelEnsemble
import lv.bolwarra.wetter.domain.forecast.PrecipitationFusion
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.radar.RadarNowcast
import lv.bolwarra.wetter.domain.radar.RadarNowcaster
import lv.bolwarra.wetter.domain.radar.RadarSample
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
    /**
     * Where the last projection is kept between runs. Optional so the cache
     * behaviour can be tested without a database.
     */
    private val seriesStore: RadarSeriesStore? = null,
    /**
     * Where the projection is held to its word.
     *
     * Optional, because scoring is not required for the app to work and a
     * failure to record must never cost somebody their forecast.
     */
    private val verification: VerificationRepository? = null,
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
        // Off the main thread, because this is the heaviest arithmetic in the
        // app by a wide margin: two block-match passes over the whole grid, then
        // a bilinear advection of every pixel for each of sixteen steps.
        //
        // It was running wherever the caller happened to be, and the caller is a
        // view model - so on the main thread. The dial's light integrates its
        // angle in withFrameNanos, so it stalls the instant anything blocks a
        // frame, and coming back from the background is exactly when this work
        // happens. The animation stuttering was the arithmetic, not the network.
        val nowcast = if (frames.size < 2) {
            null
        } else {
            withContext(Dispatchers.Default) { RadarNowcaster.nowcast(frames, LEADS) }
        }
        val sweepAt = frames.lastOrNull()?.at ?: latest
        cached = Cached(
            location = location,
            sweepAt = sweepAt,
            checkedAt = now,
            nowcast = nowcast,
        )
        // Kept so the next launch starts from something rather than nothing.
        // Only the samples under this place are stored; the grid they came from
        // is megabytes and nothing reads it.
        val series = nowcast?.seriesAt(location.latitude, location.longitude).orEmpty()

        val store = seriesStore
        if (nowcast != null && sweepAt != null && store != null) {
            runCatching { store.write(cacheKeyOf(location), sweepAt, series) }
        }

        // Mark the last projection's homework, then set the next.
        //
        // The order matters: the sweep that just landed is the observation that
        // answers what was claimed for this moment, and it has to settle those
        // claims before the new projection writes its own for the same times.
        //
        // Wrapped, because none of this is worth a forecast. A scoring failure
        // should cost the app its self-knowledge, never its answer.
        val scorer = verification
        if (nowcast != null && sweepAt != null && scorer != null) {
            runCatching {
                // Settled against every frame in hand, not just the newest.
                //
                // Each fetch brings back two hours of sweeps, and every one is
                // an observation at a known time. Marking only the latest meant
                // a claim was settled only when a sweep happened to land on the
                // minute it was about - and the background worker wakes every
                // thirty minutes, so in ordinary use the only leads ever scored
                // would have been 30, 60, 90 and 120. The near leads, which is
                // where this app now puts its weight, would never have been
                // checked at all.
                //
                // One run now settles every outstanding claim of the last two
                // hours, at every lead. Nothing can settle itself: claims are
                // future-dated from their sweep and frames are never newer than
                // the sweep they came with.
                frames.forEach { frame ->
                    frame.rateAt(location.latitude, location.longitude)?.let { observed ->
                        scorer.settleFromRadar(
                            location = location,
                            observedAt = frame.at,
                            observed = observed.toDouble(),
                        )
                    }
                }
                scorer.recordNowcast(location, sweepAt, series)
            }
        }
        nowcast
    }

    /** Drop projections too old to contain anything still ahead. */
    suspend fun prune() {
        seriesStore?.prune(Instant.now(clock).minus(KEEP_FOR))
    }

    /**
     * Two nearby places share a kept projection, matching how the in-memory
     * cache already treats them.
     */
    private fun cacheKeyOf(location: WeatherLocation): String = String.format(
        java.util.Locale.ROOT,
        "%.2f,%.2f",
        location.latitude,
        location.longitude,
    )

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
     * What the radar says about a place, from whatever is quickest to hand.
     *
     * The first read in a fresh process comes off disk and costs nothing, which
     * is the point: opening the app used to start from the model's smooth hourly
     * guess and only sharpen once twenty-odd tiles had been fetched, decoded and
     * matched. A kept projection is on screen immediately and the fetched one
     * replaces it on the next tick.
     *
     * A kept projection is used only for the part of it still in the future.
     * One made twenty minutes ago is not wrong, only shorter.
     */
    private suspend fun radarSeries(location: WeatherLocation): List<RadarSample> {
        val now = Instant.now(clock)

        val warm = mutex.withLock { cached?.takeIf { it.location.sameGrid(location) } }
        val store = seriesStore
        if (warm == null && store != null) {
            val kept = store.read(cacheKeyOf(location))
            val stillAhead = kept?.samples?.filter { it.at.isAfter(now) }.orEmpty()
            if (stillAhead.isNotEmpty()) return stillAhead
        }

        return nowcast(location)
            ?.seriesAt(location.latitude, location.longitude)
            .orEmpty()
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
        val radar = radarSeries(forecast.location)
        val ensemble = ensemble(forecast.location)
        // Lighter than the nowcast but still a few hundred interpolations and
        // agreement calculations per step, and it runs on every clock tick.
        return withContext(Dispatchers.Default) {
            PrecipitationFusion.fuse(
                hourly = forecast.hourly,
                radar = radar,
                from = from,
                step = step,
                steps = steps,
                ensemble = ensemble,
            )
        }
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
         * Five-minute steps for the first half hour, ten-minute steps after.
         *
         * The series starts at zero on purpose. That step is the latest sweep
         * itself rather than a projection of it - rain that is falling,
         * observed - and it is the most trustworthy number in the whole
         * timeline.
         *
         * ### Why the near steps are finer
         *
         * Because zero is not *now*. Sweeps land about every ten minutes, so by
         * the time somebody looks, the observation can be nine minutes old and
         * the rain has moved on without it. On ten-minute steps the closest
         * thing to the present moment could be five minutes away from it, which
         * is a long time in a shower.
         *
         * Halving the step near the front puts a value within two and a half
         * minutes of any instant, and getting there costs the smallest
         * extrapolation this app ever makes - a couple of minutes of drift from
         * a sweep that has just landed. It is the most reliable projection
         * available and it is spent on the number people actually read: what is
         * happening right now.
         *
         * It costs six more advection passes per nowcast. That is the trade,
         * and it is a good one, because the passes are cheap and the minutes
         * near zero are the ones the app is for.
         */
        val LEADS: List<Duration> =
            (0..6).map { Duration.ofMinutes(it * 5L) } +
                (4..12).map { Duration.ofMinutes(it * 10L) }

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

        /**
         * Past this a kept projection has nothing left in the future worth
         * drawing, so there is no reason to carry it.
         */
        val KEEP_FOR: Duration = Duration.ofHours(3)

        /** Model runs publish hourly, so anything fresher gets the same numbers. */
        val ENSEMBLE_FRESH_FOR: Duration = Duration.ofHours(1)

        /** About a kilometre, the scale a radar pixel already averages over. */
        private const val GRID_TOLERANCE_DEGREES = 0.01
    }
}
