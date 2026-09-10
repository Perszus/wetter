package lv.bolwarra.wetter.data.provider.rainviewer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.data.provider.toWeatherError
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.provider.WeatherFailure
import lv.bolwarra.wetter.domain.radar.RadarField
import lv.bolwarra.wetter.domain.radar.RadarGeometry
import lv.bolwarra.wetter.domain.radar.RadarSource

/** The index of available frames. */
@Serializable
internal data class RainViewerIndex(
    val host: String = "",
    val radar: RainViewerRadar = RainViewerRadar(),
)

@Serializable
internal data class RainViewerRadar(
    val past: List<RainViewerFrame> = emptyList(),
    /**
     * Empty since RainViewer withdrew their own nowcast at the start of 2026.
     * Kept so the absence is visible in the type rather than being a surprise:
     * everything past the present moment is [lv.bolwarra.wetter.domain.radar.RadarNowcaster]'s
     * job now.
     */
    val nowcast: List<RainViewerFrame> = emptyList(),
)

@Serializable
internal data class RainViewerFrame(val time: Long = 0, val path: String = "")

/** Decodes a PNG tile to packed ARGB. Separated so the palette can be tested without Android. */
internal interface TileDecoder {
    fun decode(bytes: ByteArray): IntArray?
}

/**
 * Radar observations from RainViewer.
 *
 * A global composite of national radar networks, free and without a key. Used as
 * an *observation* source only: their own forecast frames were withdrawn at the
 * start of 2026, so anything past the present moment is produced here.
 *
 * ### The tiles are pictures, and that is the whole difficulty
 *
 * There is no numeric endpoint. The rain field arrives as PNG map tiles and has
 * to be read back out of the colours ([RainViewerPalette]), which is why the
 * scale had to be established by measurement and why the rates it yields are
 * reliable in shape and only approximate in magnitude.
 *
 * ### Why a block of tiles rather than one
 *
 * Motion is measured by matching a sweep against the one before it. A single
 * tile around the user would let a shower move in from outside the frame with no
 * warning at all, since there is no data upwind to have seen it coming. A block
 * buys the surrounding country in every direction, which at these zooms is the
 * couple of hours of approach the nowcast needs. It costs about 45 KB.
 */
internal class RainViewerRadarSource(
    private val client: HttpClient,
    private val decoder: TileDecoder,
    private val indexUrl: String = INDEX_URL,
) : RadarSource {

    override val id: String = ID

    /** Required by their terms, and shown wherever the radar is used. */
    override val attribution: String = ATTRIBUTION

    override val sweepInterval: Duration = SWEEP_INTERVAL

    override suspend fun latestSweep(): Result<Instant?> = try {
        val index = index()
        Result.success(index.radar.past.lastOrNull()?.let { Instant.ofEpochSecond(it.time) })
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    override suspend fun recentFrames(
        latitude: Double,
        longitude: Double,
        frames: Int,
    ): Result<List<RadarField>> = try {
        val index = index()
        val wanted = index.radar.past.takeLast(frames.coerceAtLeast(2))
        if (wanted.isEmpty() || index.host.isBlank()) {
            Result.failure(WeatherFailure(WeatherError.NoProviderAvailable))
        } else {
            val (tileX, tileY) = RadarGeometry.tileOf(latitude, longitude, ZOOM)
            val geometry = RadarGeometry.ofTileBlock(
                zoom = ZOOM,
                tileX = tileX - BLOCK_RADIUS,
                tileY = tileY - BLOCK_RADIUS,
                tilesAcross = BLOCK_SIZE,
                tilesDown = BLOCK_SIZE,
            )
            // Frames together, not one after another.
            //
            // Each frame is nine tiles that were already fetched in parallel,
            // and then the frames themselves were awaited in turn - so three
            // sweeps cost three round trips and the catch-up case, which asks
            // for thirteen, cost thirteen. They do not depend on each other in
            // any way; the only reason they were sequential is that `map` is.
            //
            // The whole set shares one permit pool rather than each frame
            // holding its own, so the burst is bounded by how many requests are
            // in the air at once instead of by how many sweeps were asked for.
            val fields = coroutineScope {
                wanted.map { frame ->
                    async { fetchFrame(index.host, frame, tileX, tileY, geometry) }
                }.awaitAll()
            }
            Result.success(fields.filterNotNull())
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(WeatherFailure(failure.toWeatherError()))
    }

    /**
     * One sweep, stitched from its tiles.
     *
     * The tiles are fetched together rather than in turn: nine sequential round
     * trips is most of a second of latency for no reason, and they are
     * independent.
     */
    private suspend fun fetchFrame(
        host: String,
        frame: RainViewerFrame,
        tileX: Int,
        tileY: Int,
        geometry: RadarGeometry,
    ): RadarField? = coroutineScope {
        val tiles = buildList {
            for (row in 0 until BLOCK_SIZE) {
                for (column in 0 until BLOCK_SIZE) {
                    val x = tileX - BLOCK_RADIUS + column
                    val y = tileY - BLOCK_RADIUS + row
                    add(
                        Triple(
                            column,
                            row,
                            async { tile(host, frame.path, x, y) },
                        ),
                    )
                }
            }
        }

        val values = FloatArray(geometry.width * geometry.height)
        var decodedAny = false
        for ((column, row, deferred) in tiles) {
            val pixels = deferred.await() ?: continue
            decodedAny = true
            val originX = column * RadarGeometry.TILE_SIZE
            val originY = row * RadarGeometry.TILE_SIZE
            for (y in 0 until RadarGeometry.TILE_SIZE) {
                val target = (originY + y) * geometry.width + originX
                val source = y * RadarGeometry.TILE_SIZE
                for (x in 0 until RadarGeometry.TILE_SIZE) {
                    values[target + x] = RainViewerPalette.rateOf(pixels[source + x])
                }
            }
        }

        if (!decodedAny) {
            null
        } else {
            RadarField(Instant.ofEpochSecond(frame.time), geometry, values)
        }
    }

    /**
     * One tile, or null if it is missing.
     *
     * A missing tile is not an error. The service returns a tiny fully
     * transparent PNG where nothing is falling, and occasionally nothing at all;
     * either way the surrounding sweep is still worth having, and the block is
     * left as zero there.
     */
    /**
     * The index, kept for a moment.
     *
     * A refresh asks for it twice - once to learn the latest sweep and once to
     * list the frames - and they are the same question a few milliseconds apart.
     * RainViewer publishes a new index about every ten minutes, so holding the
     * answer for one minute cannot serve a stale sweep and removes a round trip
     * from every refresh.
     */
    private suspend fun index(): RainViewerIndex {
        val now = Instant.now()
        held.withLock {
            val kept = cached
            if (kept != null && Duration.between(kept.first, now) < INDEX_FRESH_FOR) {
                return kept.second
            }
        }
        val fetched: RainViewerIndex = client.get(indexUrl).body()
        held.withLock { cached = now to fetched }
        return fetched
    }

    private val held = Mutex()
    private var cached: Pair<Instant, RainViewerIndex>? = null

    /**
     * How many tile requests may be in the air at once.
     *
     * Nine is one frame's worth. Without a bound, asking for the thirteen
     * frames of a catch-up would put a hundred and seventeen requests on a
     * volunteer service in one go, which is rude and, on a phone radio, slower
     * than doing fewer at a time.
     */
    private val inFlight = Semaphore(MAX_TILES_IN_FLIGHT)

    private suspend fun tile(host: String, path: String, x: Int, y: Int): IntArray? = try {
        val url = "$host$path/${RadarGeometry.TILE_SIZE}/$ZOOM/$x/$y/$COLOUR_SCHEME/$OPTIONS.png"
        val response: HttpResponse = inFlight.withPermit { client.get(url) }
        val bytes = response.readRawBytes()
        withContext(Dispatchers.Default) { decoder.decode(bytes) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    internal companion object {
        const val ID = "rainviewer"
        const val ATTRIBUTION = "Weather data by RainViewer"
        const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"

        /**
         * Zoom 7 puts a pixel at roughly 670 m over the Baltic - close to the
         * kilometre the underlying composites are produced at, so going finer
         * would only be interpolating someone else's interpolation.
         */
        const val ZOOM = 7

        /** Three by three, centred on the user's tile. */
        const val MAX_TILES_IN_FLIGHT = 9

        val INDEX_FRESH_FOR: Duration = Duration.ofMinutes(1)

        const val BLOCK_SIZE = 3
        const val BLOCK_RADIUS = BLOCK_SIZE / 2

        /**
         * The colour scheme and rendering options. Every scheme currently returns
         * byte-identical tiles, so this is the shape their API documents rather
         * than a choice with an effect; smoothing is off because an interpolated
         * picture is a worse thing to read numbers back out of than a blocky one.
         */
        const val COLOUR_SCHEME = 0
        const val OPTIONS = "0_0"

        /** The composite is rebuilt every ten minutes. Measured, and stated in their API. */
        val SWEEP_INTERVAL: Duration = Duration.ofMinutes(10)
    }
}
