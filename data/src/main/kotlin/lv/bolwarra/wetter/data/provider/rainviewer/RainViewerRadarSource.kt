// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
     * Empty since RainViewer withdrew their nowcast at the start of 2026; projection
     * is [lv.bolwarra.wetter.domain.radar.RadarNowcaster]'s job.
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
 * Radar observations from RainViewer: a global composite of national radar
 * networks, free and without a key. Observations only; projection happens here.
 *
 * There is no numeric endpoint: the rain field is read back out of PNG tile
 * colours ([RainViewerPalette]), reliable in shape and approximate in magnitude.
 * A block of tiles ([BLOCK_SIZE]) rather than one, so rain approaching from
 * upwind is seen before it arrives.
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
    ): Result<List<RadarField>> = frames(
        RadarGeometry.blockAround(latitude, longitude, ZOOM, BLOCK_SIZE),
        frames,
    )

    override suspend fun wideFrames(
        latitude: Double,
        longitude: Double,
        frames: Int,
    ): Result<List<RadarField>> = frames(
        RadarGeometry.blockAround(latitude, longitude, WIDE_ZOOM, WIDE_BLOCK_SIZE),
        frames,
    )

    private suspend fun frames(geometry: RadarGeometry, frames: Int): Result<List<RadarField>> =
        try {
            val index = index()
            val wanted = index.radar.past.takeLast(frames.coerceAtLeast(2))
            if (wanted.isEmpty() || index.host.isBlank()) {
                Result.failure(WeatherFailure(WeatherError.NoProviderAvailable))
            } else {
                // Frames fetched together, sharing one permit pool, so the burst is bounded by
                // requests in the air rather than by how many sweeps were asked for.
                val fields = coroutineScope {
                    wanted.map { frame ->
                        async { fetchFrame(index.host, frame, geometry) }
                    }.awaitAll()
                }
                Result.success(fields.filterNotNull())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Result.failure(WeatherFailure(failure.toWeatherError()))
        }

    /** One sweep, stitched from its tiles, fetched together. */
    private suspend fun fetchFrame(
        host: String,
        frame: RainViewerFrame,
        geometry: RadarGeometry,
    ): RadarField? = coroutineScope {
        val firstX = geometry.originX / RadarGeometry.TILE_SIZE
        val firstY = geometry.originY / RadarGeometry.TILE_SIZE
        val across = geometry.width / RadarGeometry.TILE_SIZE
        val down = geometry.height / RadarGeometry.TILE_SIZE
        val span = 1 shl geometry.zoom
        val tiles = buildList {
            for (row in 0 until down) {
                for (column in 0 until across) {
                    // Columns wrap at the antimeridian; the block never leaves
                    // the map vertically (RadarGeometry.blockAround).
                    val x = Math.floorMod(firstX + column, span)
                    val y = firstY + row
                    add(
                        Triple(
                            column,
                            row,
                            async { tile(host, frame.path, geometry.zoom, x, y) },
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
     * The index, held for a minute: a refresh asks for it twice in quick succession,
     * and a new one is published about every ten minutes.
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
     * How many tile requests may be in the air at once, so a thirteen-frame catch-up
     * does not put over a hundred requests on a volunteer service at once.
     */
    private val inFlight = Semaphore(MAX_TILES_IN_FLIGHT)

    /** One tile, or null if it is missing: the sweep is still worth having without it. */
    private suspend fun tile(host: String, path: String, zoom: Int, x: Int, y: Int): IntArray? =
        try {
            val size = RadarGeometry.TILE_SIZE
            val url = "$host$path/$size/$zoom/$x/$y/$COLOUR_SCHEME/$OPTIONS.png"
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
        const val ATTRIBUTION = "Radar by RainViewer"
        const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"

        /**
         * Zoom 7 puts a pixel at roughly 670 m at mid-latitudes, close to the kilometre the
         * composites are produced at.
         */
        const val ZOOM = 7

        /** A row and a bit of the block at once. */
        const val MAX_TILES_IN_FLIGHT = 9

        val INDEX_FRESH_FOR: Duration = Duration.ofMinutes(1)

        /** Three by three, centred on the place: about 510 km across at mid-latitudes. */
        const val BLOCK_SIZE = 3

        /**
         * The map's picture: a zoom out, four tiles by four, about 1,360 km across at
         * mid-latitudes. Coarser, because the map shows what comes from the next country.
         * One tile wider than the map shows, so every projected frame has observed sky
         * upwind of its edge.
         */
        const val WIDE_ZOOM = 6
        const val WIDE_BLOCK_SIZE = 4

        /**
         * The colour scheme and rendering options. Every scheme currently returns
         * identical tiles; smoothing is off, since an interpolated picture reads back worse
         * than a blocky one.
         */
        const val COLOUR_SCHEME = 0
        const val OPTIONS = "0_0"

        /** The composite is rebuilt every ten minutes. */
        val SWEEP_INTERVAL: Duration = Duration.ofMinutes(10)
    }
}
