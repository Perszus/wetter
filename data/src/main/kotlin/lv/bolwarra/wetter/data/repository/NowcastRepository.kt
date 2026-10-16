// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * The precipitation timeline: radar fetched, extrapolated and blended with the
 * models, off the screen so none of it repeats on a recomposition.
 *
 * The cache is keyed on the sweep, not on elapsed time. The source's frame index
 * is small, so tiles are fetched only when the index names a newer sweep, and the
 * index itself is not asked until a sweep is due ([RadarSource.sweepInterval]).
 */
class NowcastRepository internal constructor(
    private val source: RadarSource,
    private val ensembles: EnsembleSource,
    private val clock: Clock = Clock.systemUTC(),
    private val freshFor: Duration = DEFAULT_FRESH_FOR,
    /** Where the last projection is kept between runs; optional for tests. */
    private val seriesStore: RadarSeriesStore? = null,
    private val ensembleStore: EnsembleStore? = null,
    /**
     * Where projections are scored. Optional, and a scoring failure never costs a
     * forecast.
     */
    private val verification: VerificationRepository? = null,
    /**
     * Where a refresh is sent after the screen has been answered from disk.
     * Without it nothing sharpens while somebody is watching.
     */
    private val scope: CoroutineScope? = null,
) {

    /** Shown wherever radar contributes. */
    val attribution: String get() = source.attribution

    private val mutex = Mutex()

    /**
     * The newest projection, replaced whole under [mutex] and read without it, so
     * drawing never waits behind a refresh that is downloading.
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
        /** The sweep this was built from: the cache key that matters. */
        val sweepAt: Instant?,
        /** When the source was last asked, so a fruitless ask is not repeated. */
        val checkedAt: Instant,
        val nowcast: RadarNowcast?,
    )

    /**
     * The radar projection for a place, or null when radar has nothing to say:
     * outside coverage, or too little echo to track.
     */
    suspend fun nowcast(location: WeatherLocation): RadarNowcast? = refreshed(location)?.nowcast

    /**
     * When the newest sweep held for a place was taken, from memory or disk, without
     * asking the source. The background schedule is timed from this.
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

        // Has anything new been published? If the index fails, fall back to elapsed
        // time rather than to no radar.
        val latest = source.latestSweep().getOrNull()
        trace { "index ${ms()}ms, latest $latest" }
        if (held != null && latest != null && latest == held.sweepAt) {
            return@withLock held.copy(checkedAt = now).also { cached = it }
        }

        // Only what the projection needs: two sweeps for the motion, a third for the
        // trend. Reaching further back for scoring is [settleOutstanding]'s job.
        val frames = source
            .recentFrames(location.latitude, location.longitude, FRAMES)
            .getOrNull()
            .orEmpty()
        trace { "frames ${frames.size} at ${ms()}ms" }
        // The heaviest arithmetic in the app, kept off the main thread.
        val nowcast = if (frames.size < 2) {
            null
        } else {
            // Projected around this place only (RadarNowcaster.nowcastAround); the map makes
            // its own projection from its own wider sweeps.
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
        // Kept so the next launch starts from something. Only the samples under this
        // place are stored, not the grid.
        val series = nowcast?.seriesAt(location.latitude, location.longitude).orEmpty()

        val store = seriesStore
        if (nowcast != null && sweepAt != null && store != null) {
            runCatching { store.write(cacheKeyOf(location), sweepAt, series) }
        }

        // Score the last projection against the sweeps just fetched before writing new
        // claims for the same times. Wrapped: scoring never costs the answer.
        val scorer = verification
        if (nowcast != null && sweepAt != null && scorer != null) {
            runCatching {
                // Every frame in hand is an observation at a known time, so every one settles
                // the claims made for it, at every lead.
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
     * What the rain map plays: the last hour observed over the surrounding
     * countries, then two hours projected from the newest sweep, using the source's
     * wider frames ([RadarSource.wideFrames]).
     *
     * Emitted in two parts: the observed sweeps as soon as they download, then the
     * projection once it is computed. Held until [releaseMap], keyed on the sweep.
     * Empty when no sweep has any echo, which on this source means no coverage.
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
            // Zero lead is the newest sweep itself, already in [seen].
            val ahead = projection?.steps.orEmpty()
                .filter { !it.lead.isZero }
                .map { RadarMapFrame(it.at, it.field, observed = false) }
            val all = seen + ahead
            mapHeld = MapHeld(location, observed.last().at, all)
            emit(RadarMapPicture(all, projecting = false))
        }
    }

    /** Let go of the map's grids; the next opening fetches afresh. */
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
        // A day: an hourly ensemble is still a better first frame than none.
        ensembleStore?.prune(now.minus(ENSEMBLE_KEEP_FOR))
    }

    /**
     * Score the claims the newest sweeps cannot reach by fetching further back.
     * Claims run two hours ahead, so ones that came due while the device dozed need
     * the sweeps of their own time. Called by the background job after its pass,
     * never on a drawing path.
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

    /** Timing of a radar refresh, enabled with `adb shell setprop log.tag.Nowcast DEBUG`. */
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

    /** Emits whenever a newer radar sweep has been stored for this place. */
    fun sweeps(location: WeatherLocation): Flow<Instant?> =
        seriesStore?.sweeps(cacheKeyOf(location)) ?: emptyFlow()

    /**
     * The disk key for a place's kept series: three decimals, about 110 m. Unlike
     * the in-memory projection, a kept series is samples at one point and cannot be
     * re-aimed, so places further apart than a radar pixel get their own row.
     */
    private fun cacheKeyOf(location: WeatherLocation): String = keyOf(location)

    /**
     * Whether to ask the source at all: not while the held sweep is younger than the
     * interval, then no more often than [RECHECK_INTERVAL]. After an outright failure,
     * back off for [DEFAULT_FRESH_FOR].
     */
    private fun worthAsking(held: Cached, now: Instant): Boolean {
        val sweepAt = held.sweepAt
            ?: return Duration.between(held.checkedAt, now) >= freshFor
        val due = sweepAt.plus(source.sweepInterval)
        if (now.isBefore(due)) return false
        return Duration.between(held.checkedAt, now) >= RECHECK_INTERVAL
    }

    /**
     * Several models over the same hours, for measuring how hard each hour is to
     * forecast. Cached for an hour, the cadence of the runs.
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

        // Off disk first, so a fresh process has a model median from its first frame
        // rather than one provider alone.
        val kept = held ?: ensembleStore?.read(cacheKeyOf(location))?.let {
            CachedEnsemble(location, it.fetchedAt, it.ensemble)
        }
        if (kept != null && kept !== held) cachedEnsemble = kept

        val fresh = kept != null && Duration.between(kept.at, now) < ENSEMBLE_FRESH_FOR
        if (fresh) return@withLock kept.ensemble

        // Nothing current: return what was kept and fetch a new one behind it rather
        // than make the caller wait.
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
                // A worker or the widget: nothing is waiting on a frame, and a job launched
                // behind it could be frozen with the process, so fetch here.
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

    /** Guards against queueing a fetch on every tick. */
    private var ensembleRefreshing = false

    /**
     * What the radar says about a place, from whatever is quickest: the projection
     * in memory, the series kept on disk, then the network. When the answer comes
     * from memory or disk and a newer sweep is due, the fetch goes out behind it.
     * A kept projection is used only for the part still in the future.
     *
     * @param fresh wait for the newest sweep rather than answer from what is held;
     * for the background worker and the widget, which exist to collect it.
     */
    private suspend fun radarSeries(location: WeatherLocation, fresh: Boolean): List<RadarSample> {
        if (fresh) return fetched(location)

        val background = scope
        val now = Instant.now(clock)
        // Without the lock (see [cached]): what is held is drawn now.
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

        // No scope to fetch behind: answer from disk, or else fetch now.
        if (background == null) return stillAhead.ifEmpty { fetched(location) }

        // The newest sweep is fetched behind the answer. With nothing kept, the chart
        // briefly shows the models alone rather than wait for the tiles.
        background.launch { runCatching { nowcast(location) } }
        return stillAhead
    }

    private suspend fun fetched(location: WeatherLocation): List<RadarSample> =
        nowcast(location)?.seriesAt(location.latitude, location.longitude).orEmpty()

    /**
     * The fused timeline for a place: radar where it is worth having, the models
     * throughout, each weighted by how much it deserves.
     */
    suspend fun timeline(
        forecast: WeatherForecast,
        from: Instant,
        step: Duration = STEP,
        steps: Int = DEFAULT_STEPS,
        /** See [radarSeries]. */
        fresh: Boolean = false,
    ): List<FusedPrecipitation> {
        // Concurrently: when fresh both go to the network, and they do not depend on
        // each other.
        val (radar, ensemble) = coroutineScope {
            val radar = async { radarSeries(forecast.location, fresh) }
            val ensemble = async { ensemble(forecast.location, fresh) }
            radar.await() to ensemble.await()
        }
        // Off the main thread: a few hundred interpolations per step, on every tick.
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
     * Two places share a radar answer when they land within a radar pixel of each
     * other, so moving across a room does not refetch the tiles.
     */
    private fun WeatherLocation.sameGrid(other: WeatherLocation): Boolean =
        kotlin.math.abs(latitude - other.latitude) < GRID_TOLERANCE_DEGREES &&
            kotlin.math.abs(longitude - other.longitude) < GRID_TOLERANCE_DEGREES

    companion object {
        /** The disk key rule, on the companion so a test can check it without an instance. */
        internal fun keyOf(location: WeatherLocation): String = String.format(
            java.util.Locale.ROOT,
            "%.3f,%.3f",
            location.latitude,
            location.longitude,
        )

        /** Two sweeps for the motion and a third for the intensity trend. */
        const val FRAMES = 3

        /**
         * Five-minute steps for the first half hour, ten-minute steps after.
         *
         * Step zero is the latest sweep itself, the most trustworthy number in the
         * timeline. The finer steps near the front keep a value within a few minutes of
         * any instant, since a sweep can be up to ten minutes old when it is read.
         */
        val LEADS: List<Duration> =
            (0..6).map { Duration.ofMinutes(it * 5L) } +
                (4..12).map { Duration.ofMinutes(it * 10L) }

        val STEP: Duration = Duration.ofMinutes(10)

        /**
         * A day, which is what the Today page holds. The fused series covers everything
         * the chart can be scrolled to, so radar and model are never joined mid-line;
         * past radar range every step is the model alone.
         */
        const val DEFAULT_STEPS = 144

        /**
         * How often to ask once a sweep is overdue, so a new sweep is on screen within a
         * minute of being published.
         */
        val RECHECK_INTERVAL: Duration = Duration.ofMinutes(1)

        /**
         * How long to leave a source alone after it gave nothing at all. Everything that
         * succeeds keys on the sweep instead.
         */
        val DEFAULT_FRESH_FOR: Duration = Duration.ofMinutes(5)

        /** Past this a kept projection has nothing left in the future to draw. */
        val KEEP_FOR: Duration = Duration.ofHours(3)

        /** Model runs publish hourly. */
        val ENSEMBLE_FRESH_FOR: Duration = Duration.ofHours(1)

        /**
         * How long a kept ensemble is worth opening with: far longer than it is worth
         * trusting, but still better than one provider alone.
         */
        val ENSEMBLE_KEEP_FOR: Duration = Duration.ofDays(1)

        /** How far ahead the map's loop reaches: the last projected step. */
        val MAP_HORIZON: Duration get() = LEADS.last()

        /**
         * An hour of sweeps behind the map's present, so it opens on rain already
         * moving, and gives the motion estimate a longer baseline.
         */
        private const val MAP_HISTORY = 7

        /** What [FRAMES] alone already covers, so no catching up is needed. */
        private val ROUTINE_REACH: Duration = Duration.ofMinutes(30)

        private const val TRACE_TAG = "Nowcast"

        /** RainViewer publishes on a ten-minute cadence. */
        private const val SWEEP_MINUTES = 10L

        /**
         * Two hours of sweeps, the horizon claims are made over; each costs a block of
         * tiles.
         */
        private const val MAX_FRAMES = 13

        /** About a kilometre, the scale a radar pixel already averages over. */
        private const val GRID_TOLERANCE_DEGREES = 0.01
    }
}
