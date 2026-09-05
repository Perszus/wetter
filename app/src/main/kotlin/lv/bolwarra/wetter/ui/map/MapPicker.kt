package lv.bolwarra.wetter.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * A map to put a point on.
 *
 * Deliberately not a map library. The app already owns Web Mercator arithmetic
 * for the radar and already draws on a Compose canvas, so a slippy map is a few
 * hundred lines rather than a native rendering engine, an API key and a
 * download several times the size of the rest of Wetter.
 *
 * ### What it may and may not do
 *
 * Tiles come from OpenStreetMap's own servers, which permit interactive viewing
 * and forbid pre-emptive fetching (see [lv.bolwarra.wetter.data.map.MapTileSource]).
 * That shapes this file: [visibleTiles] returns exactly the tiles the viewport
 * covers and nothing is ever requested speculatively - no ring of neighbours to
 * make panning smoother, no pyramid warmed on open. Panning is therefore a
 * little bare at the edges, which is the correct trade for using somebody's
 * donated bandwidth.
 *
 * Attribution is drawn on the map, bottom-right, and not behind a toggle,
 * because the licence asks for exactly that.
 *
 * ### The pin does not move
 *
 * The crosshair is fixed at the centre and the map moves under it. Dragging a
 * marker means the thing you are aiming with is under your thumb at the moment
 * you need to see it; moving the map instead keeps the target visible
 * throughout, and the point being chosen is always the middle of the screen.
 */
@Composable
fun MapPicker(
    centre: Coordinates,
    onCentreChanged: (Coordinates) -> Unit,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val density = LocalDensity.current.density

    var zoom by remember { mutableStateOf(START_ZOOM) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // The centre in world pixels, which is the coordinate system every tile
    // calculation below is in. Kept rather than derived from the latitude and
    // longitude each frame, so a drag is a pixel addition and not a round trip
    // through two trigonometric conversions.
    var world by remember { mutableStateOf(Mercator.worldOf(centre, zoom)) }

    LaunchedEffect(zoom) { world = Mercator.worldOf(centre, zoom) }

    LaunchedEffect(Unit) {
        snapshotFlow { world to zoom }.collectLatest { (at, level) ->
            onCentreChanged(Mercator.coordinatesOf(at, level))
        }
    }

    val loaded = remember { mutableStateOf<Map<TileKey, ImageBitmap>>(emptyMap()) }
    val wanted = if (size == IntSize.Zero) {
        emptyList()
    } else {
        visibleTiles(world, zoom, size)
    }

    LaunchedEffect(wanted) {
        wanted.forEach { key ->
            if (loaded.value[key] == null) {
                tiles.load(key)?.let { image ->
                    loaded.value = loaded.value + (key to image)
                }
            }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .background(colors.surfaceSunken)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1f) {
                        val next = (zoom + zoomStepOf(gestureZoom)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        if (next != zoom) {
                            val here = Mercator.coordinatesOf(world, zoom)
                            zoom = next
                            world = Mercator.worldOf(here, next)
                        }
                    }
                    // The map follows the finger, so the world moves the other
                    // way: dragging right shows what was to the left.
                    world = WorldPoint(world.x - pan.x, world.y - pan.y)
                        .clampedTo(zoom)
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawTiles(wanted, loaded.value, world, size)
        }
        Crosshair(density)
        Attribution()
    }
}

/**
 * Where the point being chosen is, which is always the middle.
 *
 * Drawn in fixed black and white rather than in any palette tone, and this is
 * the whole reason it needs a comment. The app's tones are solved for contrast
 * against its own plate, and the first version used `textPrimary` - which in
 * dark mode is very nearly white, and which was therefore invisible against a
 * basemap that is light whatever theme the phone is in. A map is not our
 * surface: it is somebody else's picture, mostly pale, with dark roads and
 * black labels running through it, and nothing that varies with our theme can
 * be relied on to show up on it.
 *
 * So the mark carries its own contrast: a white ring with a dark ring inside it,
 * which reads on pale fields, on grey streets and on the black of a label.
 */
@Composable
private fun BoxScope.Crosshair(density: Float) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val centre = androidx.compose.ui.geometry.Offset(
            this.size.width / 2f,
            this.size.height / 2f,
        )
        val stroke = PIN_STROKE_DP * density
        val radius = PIN_RADIUS_DP * density

        // A ring rather than a filled dot: the point being chosen is the ground
        // underneath, and a solid marker hides the thing it is marking.
        drawCircle(
            color = androidx.compose.ui.graphics.Color.White,
            radius = radius + stroke,
            center = centre,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke * 2f),
        )
        drawCircle(
            color = PIN_INK,
            radius = radius,
            center = centre,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawCircle(
            color = androidx.compose.ui.graphics.Color.White,
            radius = PIN_DOT_DP * density + stroke * 0.6f,
            center = centre,
        )
        drawCircle(color = PIN_INK, radius = PIN_DOT_DP * density, center = centre)
    }
}

/**
 * The credit the licence requires, where it requires it.
 *
 * Rule 8 says the machinery is our problem and not the reader's, and this is the
 * one exception in the app: it is a licence term attached to the map rather than
 * an explanation of how Wetter works, and it must be on the map and not hidden.
 */
@Composable
private fun BoxScope.Attribution() {
    val colors = WetterTheme.colors
    androidx.compose.material3.Text(
        text = lv.bolwarra.wetter.data.map.MapTileSource.ATTRIBUTION,
        style = WetterTheme.type.meta,
        color = colors.textTertiary,
        textAlign = TextAlign.End,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .background(colors.surface.copy(alpha = ATTRIBUTION_SCRIM))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun DrawScope.drawTiles(
    wanted: List<TileKey>,
    loaded: Map<TileKey, ImageBitmap>,
    world: WorldPoint,
    size: IntSize,
) {
    val left = world.x - size.width / 2f
    val top = world.y - size.height / 2f
    wanted.forEach { key ->
        val image = loaded[key] ?: return@forEach
        drawImage(
            image = image,
            dstOffset = IntOffset(
                (key.x * TILE - left).roundToInt(),
                (key.y * TILE - top).roundToInt(),
            ),
            dstSize = IntSize(TILE, TILE),
        )
    }
}

/**
 * Exactly the tiles the viewport covers.
 *
 * Exactly, and not one more. The obvious kindness here is to fetch a ring around
 * the edge so a pan has something to show, and that is precisely the
 * pre-emptive fetching the tile policy prohibits.
 */
internal fun visibleTiles(world: WorldPoint, zoom: Int, size: IntSize): List<TileKey> {
    val left = world.x - size.width / 2f
    val top = world.y - size.height / 2f
    val span = 1 shl zoom

    val firstX = floor(left / TILE).toInt()
    val lastX = floor((left + size.width) / TILE).toInt()
    val firstY = floor(top / TILE).toInt().coerceAtLeast(0)
    val lastY = floor((top + size.height) / TILE).toInt().coerceAtMost(span - 1)

    val keys = mutableListOf<TileKey>()
    for (y in firstY..lastY) {
        for (x in firstX..lastX) {
            // Longitude wraps and latitude does not: the world is a cylinder,
            // so a tile column off one edge is a real column on the other, while
            // a row off the top is simply not part of the earth.
            keys += TileKey(zoom, Math.floorMod(x, span), y)
        }
    }
    return keys
}

/** How much of a zoom level a pinch is worth. */
private fun zoomStepOf(gestureZoom: Float): Int = when {
    gestureZoom > ZOOM_IN_AT -> 1
    gestureZoom < ZOOM_OUT_AT -> -1
    else -> 0
}

/** A tile, by the three numbers that name one. */
data class TileKey(val zoom: Int, val x: Int, val y: Int)

/** Fetches and decodes one tile, or returns null if it could not be had. */
fun interface TileLoader {
    suspend fun load(key: TileKey): ImageBitmap?
}

/** A position on the world pixel plane at some zoom. */
data class WorldPoint(val x: Float, val y: Float) {
    /**
     * Kept on the map.
     *
     * Vertically only. Scrolling past the pole would show empty space below a
     * map that cannot continue; scrolling past the antimeridian is just going
     * round, which the tile lookup already handles by wrapping.
     */
    fun clampedTo(zoom: Int): WorldPoint {
        val span = (TILE shl zoom).toFloat()
        return WorldPoint(x, y.coerceIn(0f, span))
    }
}

/**
 * Web Mercator, in world pixels.
 *
 * The same projection [lv.bolwarra.wetter.domain.radar.RadarGeometry] uses for
 * radar, expressed for a moving viewport rather than a fixed grid window: this
 * needs the inverse as well, because the whole point is to read a coordinate
 * back off wherever somebody has dragged to.
 */
internal object Mercator {

    fun worldOf(at: Coordinates, zoom: Int): WorldPoint {
        val span = (TILE shl zoom).toDouble()
        val clamped = at.latitude.coerceIn(-LIMIT, LIMIT)
        val sin = kotlin.math.sin(Math.toRadians(clamped))
        return WorldPoint(
            x = ((at.longitude + 180.0) / 360.0 * span).toFloat(),
            y = ((0.5 - kotlin.math.ln((1 + sin) / (1 - sin)) / (4 * Math.PI)) * span).toFloat(),
        )
    }

    fun coordinatesOf(point: WorldPoint, zoom: Int): Coordinates {
        val span = (TILE shl zoom).toDouble()
        val longitude = (wrap(point.x.toDouble(), span) / span) * 360.0 - 180.0
        val n = Math.PI - 2.0 * Math.PI * point.y / span
        val latitude = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(n)))
        return Coordinates(
            latitude = latitude.coerceIn(-LIMIT, LIMIT),
            longitude = longitude.coerceIn(-180.0, 180.0),
        )
    }

    private const val LIMIT = 85.05112878
}

/**
 * Longitude going round, in world pixels.
 *
 * Not [Math.floorMod], which takes integers - and which an extension of the same
 * name does not shadow, so the call silently resolved to the integer overload
 * and would not compile. Hence a name of its own.
 */
private fun wrap(value: Double, span: Double): Double = ((value % span) + span) % span

internal const val TILE = 256

/** Close enough to read streets, far enough to know where you are. */
private const val START_ZOOM = 13
private const val MIN_ZOOM = 3
private const val MAX_ZOOM = 17

private const val ZOOM_IN_AT = 1.15f
private const val ZOOM_OUT_AT = 0.87f

/** Not quite black, so it reads as ink on a map rather than a hole in it. */
private val PIN_INK = androidx.compose.ui.graphics.Color(0xFF1A1A1A)

private const val PIN_RADIUS_DP = 11f
private const val PIN_STROKE_DP = 2f
private const val PIN_DOT_DP = 2.5f

private const val ATTRIBUTION_SCRIM = 0.7f
