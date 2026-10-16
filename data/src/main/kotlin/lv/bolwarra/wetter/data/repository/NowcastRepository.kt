package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lv.bolwarra.wetter.domain.forecast.EnsembleSource
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.forecast.ModelEnsemble
import lv.bolwarra.wetter.domain.forecast.PrecipitationFusion
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.radar.RadarMapFrame
import lv.bolwarra.wetter.domain.radar.RadarMapPicture
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
    private val ensembleStore: EnsembleStore? = null,
    /**
     * Where the projection is held to its word.
     *
     * Optional, because scoring is not required for the app to work and a
     * failure to record must never cost somebody their forecast.
     */
    private val verification: VerificationRepository? = null,
    /**
     * Where a refresh is sent when the screen has already been answered from
     * disk. Optional: without it the repository simply behaves as it did, which
     * is correct but never sharpens while somebody is watching.
     */
    private val scope: CoroutineScope? = null,
) {

    /** Shown wherever radar contributes. Several sources require it. */
    val attribution: String get() = source.attribution

    private val mutex = Mutex()

    /**
     * The newest projection, replaced whole under [mutex] and read without it.
     *
     * Volatile and immutable so a reader can take it at any moment. The lock is
     * held for the whole of a refresh - the download and the arithmetic, which
     * can run to tens of seconds on a slow connection - and the quick reads used
     * to queue behind it: the chart and the widget, both meant to draw what is
     * held at once, sat waiting for a fetch they had not asked for. On a phone
     * that was long enough for the system to call the app unresponsive and kill
     * it (a background broadcast gets a minute).
     */
    @Volatile
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
    suspend fun nowcast(location: WeatherLocation): RadarNowcast? = refreshed(location)?.nowcast

    /**
     * When the newest sweep held for a place was taken, without asking anybody.
     *
     * What the background schedule is timed from: the next sweep is due one
     * [sweepInterval] after this, and waking any earlier only re-reads the same
     * picture. Memory first, then the projection kept on disk, so a process
     * started only to wake up still knows where the radar's clock stands.
     */
    suspend fun latestSweep(location: WeatherLocation): Instant? {
        cached?.takeIf { it.location.sameGrid(location) }?.sweepAt?.let { return it }
        return runCatching { seriesStore?.read(cacheKeyOf(location))?.sweepAt }.getOrNull()
    }

    /** How often the radar source publishes a new sweep. */
    val sweepInterval: Duration get() = source.sweepInterval

    /** The held projection, re-read from the source when a newer sweep is due. */
    private suspend fun refreshed(location: WeatherLocation): Cached? = mutex.withLock {
        val now = Instant.now(clock)
        val held = cached?.takeIf { it.location.sameGrid(location) }

        if (held != null && !worthAsking(held, now)) return@withLock held

        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000

        // The cheap half: has anything new been published? A failure here is not
        // an error worth surfacing - it just means falling back to the elapsed
        // clock, so a source with a sick index degrades to the old behaviour
        // rather than to no radar at all.
        val latest = source.latestSweep().getOrNull()
        trace { "index ${ms()}ms, latest $latest" }
        if (held != null && latest != null && latest == held.sweepAt) {
            return@withLock held.copy(checkedAt = now).also { cached = it }
        }

        // Exactly what the projection needs: two sweeps for the motion and a
        // third so the trend has something to be measured across.
        //
        // This used to reach back up to two hours whenever scoring had claims
        // outstanding - after any quiet spell, thirteen sweeps of tiles - and
        // the projection of the next half hour, the one thing people open the
        // app for, waited behind that bookkeeping: measured at over fifty
        // seconds on a phone. The reach-back is [settleOutstanding] now, run
        // where nothing is waiting on it.
        val frames = source
            .recentFrames(location.latitude, location.longitude, FRAMES)
            .getOrNull()
            .orEmpty()
        trace { "frames ${frames.size} at ${ms()}ms" }
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
            // Around this place only: the series and its coverage check are all
            // that is read from it (RadarNowcaster.nowcastAround). The map makes
            // its own whole-grid projection from its own wider sweeps.
            withContext(Dispatchers.Default) {
                RadarNowcaster.nowcastAround(frames, LEADS, location.latitude, location.longitude)
            }
        }
        trace { "projection at ${ms()}ms" }
        val sweepAt = frames.lastOrNull()?.at ?: latest
        val fresh = Cached(
            location = location,
            sweepAt = sweepAt,
            checkedAt = now,
            nowcast = nowcast,
        )
        cached = fresh
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
                // Every sweep in hand is an observation at a known time. Marking only the latest meant
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
        fresh
    }

    /**
     * What the rain map plays: the last hour as observed over the surrounding
     * countries, then two hours projected forward from the newest sweep.
     *
     * Its own picture, not the chart's. The chart reads a district finely and
     * the map reads the neighbouring countries coarsely ([RadarSource.wideFrames]);
     * the same engine projects both, from the same sweeps.
     *
     * Emitted in two parts, because they take very different times: the
     * observed sweeps as soon as they have downloaded, and the projection once
     * it has been worked out - the heaviest arithmetic in the app, over the
     * widest grid. Somebody who opened the map sees the sky at once and the
     * forecast a moment later, rather than a spinner for the sum of both.
     *
     * Held until [releaseMap], keyed on the sweep, so reopening the map or a
     * rebuild with nothing new published costs nothing. Empty when no sweep
     * reached, or none has any echo in it - which on this source is
     * indistinguishable from no coverage.
     */
    fun radarMap(location: WeatherLocation): Flow<RadarMapPicture> = flow {
        mapMutex.withLock {
            val held = mapHeld?.takeIf { it.location.sameGrid(location) }
            val latest = source.latestSweep().getOrNull()
            if (held != null) {
                emit(RadarMapPicture(held.frames, projecting = false))
                if (latest == null || latest == held.sweepAt) return@withLock
            }

            val observed = source
                .wideFrames(location.latitude, location.longitude, MAP_HISTORY)
                .getOrNull()
                .orEmpty()
                .sortedBy { it.at }
            if (observed.none { it.wetFraction() > 0f }) {
                if (held == null) emit(RadarMapPicture(emptyList(), projecting = false))
                return@withLock
            }
            val seen = observed.map { RadarMapFrame(it.at, it, observed = true) }
            emit(RadarMapPicture(seen, projecting = observed.size >= 2))

            val projection = if (observed.size < 2) {
                null
            } else {
                withContext(Dispatchers.Default) { RadarNowcaster.nowcast(observed, LEADS) }
            }
            // Zero lead is the newest sweep itself, which is already in [seen].
            val ahead = projection?.steps.orEmpty()
                .filter { !it.lead.isZero }
                .map { RadarMapFrame(it.at, it.field, observed = false) }
            val all = seen + ahead
            mapHeld = MapHeld(location, observed.last().at, all)
            emit(RadarMapPicture(all, projecting = false))
        }
    }

    /**
     * Let go of the map's picture. Tens of megabytes of grids that nothing
     * needs once the map is shut; the next opening fetches afresh.
     */
    fun releaseMap() {
        mapHeld = null
    }

    private val mapMutex = Mutex()

    @Volatile
    private var mapHeld: MapHeld? = null

    private data class MapHeld(
        val location: WeatherLocation,
        val sweepAt: Instant,
        val frames: List<RadarMapFrame>,
    )

    /** Drop projections too old to contain anything still ahead. */
    suspend fun prune() {
        val now = Instant.now(clock)
        seriesStore?.prune(now.minus(KEEP_FOR))
        // A day, not three hours: an ensemble is hourly and a stale one is still
        // a better first frame than no models at all.
        ensembleStore?.prune(now.minus(ENSEMBLE_KEEP_FOR))
    }

    /**
     * How many sweeps to ask for: the projection's own needs, plus whatever it
     * takes to reach the oldest claim still waiting to be marked.
     *
     * Capped, because each sweep is a block of tiles fetched and decoded. Two
     * hours is the horizon claims are made over, so nothing older is worth
     * reaching for - beyond it the claim is unmarkable whatever we fetch.
     */
    /**
     * Mark the claims the newest sweeps cannot reach: fetch further back, for
     * scoring only.
     *
     * Claims run two hours ahead, so anything that came due while the device
     * dozed is only markable against the sweeps of that time - and without this
     * the record thins out exactly at the long leads where the error is largest.
     * It can mean a dozen sweeps of tiles, so it is never on a drawing path: the
     * background job calls it after its pass, when nothing is waiting.
     */
    suspend fun settleOutstanding(location: WeatherLocation) {
        val scorer = verification ?: return
        val now = Instant.now(clock)
        val needed = framesNeeded(now)
        if (needed <= FRAMES) return
        val frames = source
            .recentFrames(location.latitude, location.longitude, needed)
            .getOrNull()
            .orEmpty()
        runCatching {
            frames.forEach { frame ->
                frame.rateAt(location.latitude, location.longitude)?.let { observed ->
                    scorer.settleFromRadar(
                        location = location,
                        observedAt = frame.at,
                        observed = observed.toDouble(),
                    )
                }
            }
        }
    }

    /**
     * Where the time in a radar refresh goes, for profiling on a device:
     * `adb shell setprop log.tag.Nowcast DEBUG`. Off otherwise, and free.
     */
    private inline fun trace(message: () -> String) {
        if (android.util.Log.isLoggable(TRACE_TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(TRACE_TAG, message())
        }
    }

    private suspend fun framesNeeded(now: Instant): Int {
        val oldest = verification?.oldestUnsettledNowcast(now) ?: return FRAMES
        val behind = Duration.between(oldest, now)
        if (behind <= ROUTINE_REACH) return FRAMES

        val extra = (behind.toMinutes() / SWEEP_MINUTES).toInt()
        return (FRAMES + extra).coerceAtMost(MAX_FRAMES)
    }

    /**
     * Which kept series belongs to a place, to about a hundred metres.
     *
     * This is not the same question the in-memory cache asks, and rounding it
     * the same way was wrong. That cache holds a *projection* - a field over a
     * whole block of tiles - so two places a kilometre apart may share one and
     * still each be sampled at their own coordinates by `seriesAt`. What this
     * key addresses is the opposite: a list of samples already taken at one
     * point, with no way to re-aim it.
     *
     * At two decimals - about 1.1 km - a pin moved a few streets kept the same
     * key, so opening the app drew the *previous* pin's radar until the fetch
     * behind it finished. Exactly the "somewhere near you" the map was added to
     * get away from.
     *
     * Three decimals is roughly 110 m, comfortably inside the kilometre a radar
     * pixel already averages over, so two points that still collide are reading
     * the same pixel and their samples are genuinely interchangeable. Anything
     * further apart now gets its own row.
     */
    /**
     * Emits whenever a newer radar sweep has been stored for this place.
     *
     * The screen used to find out by asking again every minute. That is a poll
     * dressed as a clock: the answer changes when a sweep lands, which is a
     * thing the database already knows the moment it happens, and waiting a
     * minute to be told meant the chart was on average half a minute behind
     * data the app was already holding.
     */
    fun sweeps(location: WeatherLocation): Flow<Instant?> =
        seriesStore?.sweeps(cacheKeyOf(location)) ?: emptyFlow()

    private fun cacheKeyOf(location: WeatherLocation): String = keyOf(location)

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
    suspend fun ensemble(
        location: WeatherLocation,
        /** Fetch in line when stale, as [radarSeries] does for radar. */
        fresh: Boolean = false,
    ): ModelEnsemble? = ensembleMutex.withLock {
        val now = Instant.now(clock)
        val held = cachedEnsemble?.takeIf { it.location.sameGrid(location) }
        if (held != null && Duration.between(held.at, now) < ENSEMBLE_FRESH_FOR) {
            return@withLock held.ensemble
        }

        // Off disk before anything else, so a fresh process has models to take a
        // median over from the first frame it draws.
        //
        // This is the whole of what used to make an opening chart wait. The
        // forecast and the radar projection were both kept across a restart and
        // this was not, so every cold start filled everything past the first
        // hour from the one chosen provider until a fetch came back - which is
        // exactly how a wet evening six of seven models agreed on rendered flat
        // and dry.
        val kept = held ?: ensembleStore?.read(cacheKeyOf(location))?.let {
            CachedEnsemble(location, it.fetchedAt, it.ensemble)
        }
        if (kept != null && kept !== held) cachedEnsemble = kept

        val fresh = kept != null && Duration.between(kept.at, now) < ENSEMBLE_FRESH_FOR
        if (fresh) return@withLock kept?.ensemble

        // Nothing current: hand back whatever was kept and go and get a new one,
        // rather than making the caller wait. Nothing here is on a frame's
        // critical path that a slightly older spread would spoil.
        if (!ensembleRefreshing) {
            ensembleRefreshing = true
            val background = scope.takeUnless { fresh }
            if (background != null) {
                background.launch {
                    try {
                        fetchEnsemble(location)
                    } finally {
                        ensembleRefreshing = false
                    }
                }
            } else {
                // A worker or the widget: nothing waiting on a frame, and a job
                // launched behind it could be frozen with the process the moment
                // it returns. Fetched here and kept for everything after.
                try {
                    return@withLock fetchEnsemble(location)
                } finally {
                    ensembleRefreshing = false
                }
            }
        }
        kept?.ensemble
    }

    /** Fetch, keep in memory, and write to disk so the next start has it. */
    private suspend fun fetchEnsemble(location: WeatherLocation): ModelEnsemble? {
        val fetched = ensembles.ensemble(location).getOrNull()?.takeUnless { it.isEmpty }
        val at = Instant.now(clock)
        cachedEnsemble = CachedEnsemble(location, at, fetched)
        if (fetched != null) {
            runCatching { ensembleStore?.write(cacheKeyOf(location), at, fetched) }
        }
        return fetched
    }

    /** Guards against a tick a second queueing a fetch each time. */
    private var ensembleRefreshing = false

    /**
     * What the radar says about a place, from whatever is quickest to hand.
     *
     * Three places to answer from, fastest first: the projection already in
     * memory, the series kept on disk by an earlier process, and the network.
     * Whenever the answer comes from one of the first two and a newer sweep is
     * due, the fetch goes out behind it and lands on the next rebuild - so
     * nothing waits on the network, and the answer still sharpens.
     *
     * ### The one held in memory is an answer
     *
     * It used to be treated as if it were nothing. The in-memory projection
     * skipped the disk read, fell through to the cold branch and came back as
     * no radar at all - which, after the first fetch of a process, was every
     * rebuild. The chart drew the models alone for the rest of the session,
     * the one input that had actually looked at the sky thrown away because it
     * was already in hand.
     *
     * A kept projection is used only for the part of it still in the future.
     * One made twenty minutes ago is not wrong, only shorter.
     *
     * @param fresh wait for the newest sweep rather than answering from what is
     *   held. For callers with nobody watching a frame - the background worker,
     *   the widget - which exist to collect it. Still fetches nothing when the
     *   sweep in hand is the newest there is.
     */
    private suspend fun radarSeries(location: WeatherLocation, fresh: Boolean): List<RadarSample> {
        if (fresh) return fetched(location)

        val background = scope
        val now = Instant.now(clock)
        // Without the lock: see [cached]. What is held is drawn now, whatever a
        // refresh in progress is doing.
        val warm = cached?.takeIf { it.location.sameGrid(location) }
            ?.let { it to worthAsking(it, now) }
        if (warm != null) {
            val (held, due) = warm
            if (due) background?.launch { runCatching { nowcast(location) } }
            return held.nowcast
                ?.seriesAt(location.latitude, location.longitude)
                .orEmpty()
        }

        val kept = seriesStore?.read(cacheKeyOf(location))
        val stillAhead = kept?.samples?.filter { it.at.isAfter(now) }.orEmpty()

        // No scope means nothing to fetch behind: answer from disk if it can,
        // and otherwise go and get it.
        if (background == null) return stillAhead.ifEmpty { fetched(location) }

        // Whatever was or was not on disk, the newest sweep is fetched behind
        // the answer. With nothing kept that means answering with no radar at
        // all for a moment rather than holding the screen while twenty tiles
        // are fetched and decoded: every input for the models is already on
        // disk, and the one that is not must not hold the other two hostage.
        background.launch { runCatching { nowcast(location) } }
        return stillAhead
    }

    private suspend fun fetched(location: WeatherLocation): List<RadarSample> =
        nowcast(location)?.seriesAt(location.latitude, location.longitude).orEmpty()

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
        /** See [radarSeries]: wait for the newest sweep rather than answer from what is held. */
        fresh: Boolean = false,
    ): List<FusedPrecipitation> {
        // Together, not in turn. When fresh they each go to the network, and
        // the widget has a few seconds of a broadcast receiver to spend - the
        // radar and the models do not depend on each other, so neither should
        // wait for the other's round trip.
        val (radar, ensemble) = coroutineScope {
            val radar = async { radarSeries(forecast.location, fresh) }
            val ensemble = async { ensemble(forecast.location, fresh) }
            radar.await() to ensemble.await()
        }
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
         * The filing rule itself, on the companion so a test can hold it to
         * this without an instance and its half-dozen collaborators.
         */
        internal fun keyOf(location: WeatherLocation): String = String.format(
            java.util.Locale.ROOT,
            "%.3f,%.3f",
            location.latitude,
            location.longitude,
        )

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

        /**
         * A day, which is what the Today page holds.
         *
         * Six hours of it is what the page *shows*; the rest is reached by
         * pushing the chart sideways. The series has to cover the whole of what
         * can be scrolled to, because the alternative — fusing the visible part
         * and handing the tail to the provider's hourly rows — joins two
         * different measurements halfway along one line, and the seam would land
         * at whatever hour the radar happened to reach.
         *
         * Past radar range every step is the model alone, which is what the
         * curve's `≈` already says about it. The cost is arithmetic on a
         * background dispatcher once a minute, and it is small: the radar
         * lookups are a handful of samples and the model traces are built once
         * for the whole series rather than per step.
         */
        const val DEFAULT_STEPS = 144

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

        /**
         * How long a kept ensemble is worth opening with.
         *
         * Far longer than it is worth trusting. A spread from this morning is
         * not current and is still an enormously better first frame than one
         * provider alone, which is the alternative it replaced.
         */
        val ENSEMBLE_KEEP_FOR: Duration = Duration.ofDays(1)

        /** How far ahead the map's loop reaches: the last projected step. */
        val MAP_HORIZON: Duration get() = LEADS.last()

        /**
         * An hour of sweeps behind the map's present, so it opens on the rain
         * already moving - and a longer baseline for its motion estimate.
         */
        private const val MAP_HISTORY = 7

        /** What FRAMES alone already covers, so no catching up is needed. */
        private val ROUTINE_REACH: Duration = Duration.ofMinutes(30)

        private const val TRACE_TAG = "Nowcast"

        /** RainViewer publishes on a ten-minute cadence. */
        private const val SWEEP_MINUTES = 10L

        /**
         * Two hours of sweeps, which is the horizon claims are made over. Past
         * this there is nothing left to settle, and each frame costs a block of
         * tiles fetched and decoded.
         */
        private const val MAX_FRAMES = 13

        /** About a kilometre, the scale a radar pixel already averages over. */
        private const val GRID_TOLERANCE_DEGREES = 0.01
    }
}
