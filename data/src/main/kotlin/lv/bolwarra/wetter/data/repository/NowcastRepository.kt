package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.bolwarra.wetter.data.provider.openmeteo.OpenMeteoEnsemble
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
 * ### Why the cache is short and in memory
 *
 * The composites update every ten minutes, so anything fresher than that would
 * be refetching the same sweep. It is not written to disk: a radar nowcast is
 * worthless within the hour and there is no point keeping one across a restart -
 * unlike a forecast, which is still worth showing a day later. Twenty tiles of
 * PNG in the database would buy nothing and cost a migration.
 */
class NowcastRepository internal constructor(
    private val source: RadarSource,
    private val ensembles: OpenMeteoEnsemble,
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
        val at: Instant,
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
        val held = cached
        if (held != null &&
            held.location.sameGrid(location) &&
            Duration.between(held.at, now) < freshFor
        ) {
            return@withLock held.nowcast
        }

        val frames = source.recentFrames(location.latitude, location.longitude, FRAMES)
            .getOrNull()
            .orEmpty()
        val nowcast = if (frames.size < 2) null else RadarNowcaster.nowcast(frames, LEADS)
        cached = Cached(location, now, nowcast)
        nowcast
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

        /** Ten-minute steps out to two hours, matching the composites' own cadence. */
        val LEADS: List<Duration> = (1..12).map { Duration.ofMinutes(it * 10L) }

        val STEP: Duration = Duration.ofMinutes(10)

        /** Six hours, which is what the Today page draws. */
        const val DEFAULT_STEPS = 36

        /** The composites publish every ten minutes; asking more often gets the same sweep. */
        val DEFAULT_FRESH_FOR: Duration = Duration.ofMinutes(5)

        /** Model runs publish hourly, so anything fresher gets the same numbers. */
        val ENSEMBLE_FRESH_FOR: Duration = Duration.ofHours(1)

        /** About a kilometre, the scale a radar pixel already averages over. */
        private const val GRID_TOLERANCE_DEGREES = 0.01
    }
}
