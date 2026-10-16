// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * A map to put a point on.
 *
 * Not a map library: the app already owns Web Mercator arithmetic and draws on a
 * Compose canvas, so a slippy map costs a few hundred lines rather than a native
 * engine and an API key.
 *
 * Tiles come from OpenStreetMap's servers, which permit interactive viewing and
 * forbid pre-emptive fetching (see [lv.bolwarra.wetter.data.map.MapTileSource]),
 * so only the tiles the viewport covers are ever requested. The attribution is
 * drawn on the map, as the licence asks.
 *
 * Panning and zooming choose nothing; a tap places the pin, which then stays on
 * that ground while the map moves under it.
 */
@Composable
fun MapPicker(
    centre: Coordinates,
    chosen: Coordinates?,
    onPick: (Coordinates) -> Unit,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val density = LocalDensity.current.density

    var zoom by remember { mutableStateOf(START_ZOOM) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // The centre in world pixels, which every tile calculation uses, kept so a drag
    // is a pixel addition rather than a round trip through trigonometry.
    var world by remember { mutableStateOf(Mercator.worldOf(centre, zoom)) }

    // Re-centred when the caller moves the map, as "use my current location" does.
    // Keyed on the coordinate, so pressing it again brings the map back.
    LaunchedEffect(centre) { world = Mercator.worldOf(centre, zoom) }

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
                    // The world moves opposite to the finger.
                    world = WorldPoint(world.x - pan.x, world.y - pan.y)
                        .clampedTo(zoom)
                }
            }
            // A separate handler, so the end of a drag is not read as a tap: the handler
            // above consumes anything that moved. Keyed on nothing, since the state below is
            // read through delegates and a changing key would restart detection every frame of
            // a pan.
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    onPick(Mercator.coordinatesOf(screenToWorld(at, world, size), zoom))
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawTiles(wanted, loaded.value, world, size)
        }
        if (chosen != null) {
            Pin(at = worldToScreen(Mercator.worldOf(chosen, zoom), world, size), density = density)
        }
        ZoomControls(
            canZoomIn = zoom < MAX_ZOOM,
            canZoomOut = zoom > MIN_ZOOM,
            onZoom = { step ->
                val next = (zoom + step).coerceIn(MIN_ZOOM, MAX_ZOOM)
                if (next != zoom) {
                    // Zoom about the middle of the screen.
                    val here = Mercator.coordinatesOf(world, zoom)
                    zoom = next
                    world = Mercator.worldOf(here, next)
                }
            },
        )
        Attribution()
    }
}

/** Where a point on the screen falls on the world plane. */
private fun screenToWorld(at: Offset, world: WorldPoint, size: IntSize): WorldPoint =
    WorldPoint(world.x - size.width / 2f + at.x, world.y - size.height / 2f + at.y)

/** And back, so a chosen place is drawn wherever the map has carried it. */
private fun worldToScreen(point: WorldPoint, world: WorldPoint, size: IntSize): Offset =
    Offset(point.x - (world.x - size.width / 2f), point.y - (world.y - size.height / 2f))

/**
 * Plus and minus, for a phone held in one hand. Bottom-left, opposite the
 * attribution.
 */
@Composable
private fun BoxScope.ZoomControls(canZoomIn: Boolean, canZoomOut: Boolean, onZoom: (Int) -> Unit) {
    Column(
        modifier = Modifier.align(Alignment.BottomStart).padding(ZOOM_INSET),
        verticalArrangement = Arrangement.spacedBy(ZOOM_GAP),
    ) {
        ZoomButton(stringResource(R.string.map_zoom_in), "+", canZoomIn) { onZoom(1) }
        ZoomButton(stringResource(R.string.map_zoom_out), "−", canZoomOut) { onZoom(-1) }
    }
}

@Composable
private fun ZoomButton(description: String, glyph: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Text(
        text = glyph,
        style = WetterTheme.type.headline,
        // Fixed inks: a basemap is somebody else's picture, and nothing that varies with
        // the theme can be relied on to show up on it.
        color = if (enabled) PIN_INK else PIN_INK.copy(alpha = ZOOM_DISABLED),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .size(ZOOM_BUTTON)
            .clip(RoundedCornerShape(ZOOM_RADIUS))
            .background(Color.White.copy(alpha = ZOOM_SCRIM))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

/**
 * The point that has been chosen, drawn where it actually is. A white ring with a
 * dark ring inside, in fixed inks rather than theme tones, so it reads on pale
 * fields, grey streets and black labels alike.
 */
@Composable
internal fun BoxScope.Pin(at: Offset, density: Float) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val centre = at
        val stroke = PIN_STROKE_DP * density
        val radius = PIN_RADIUS_DP * density

        // A ring rather than a filled dot, so the marker does not hide the ground it
        // marks.
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
 * The credit the licence requires, on the map itself: a licence term, not an
 * explanation of how Wetter works.
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
 * Exactly the tiles the viewport covers. A ring around the edge for smoother
 * panning would be the pre-emptive fetching the tile policy prohibits.
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
            // Longitude wraps and latitude does not: a column off one edge is real on the
            // other, a row off the top is not part of the earth.
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
     * Kept on the map vertically only: past the pole is empty, while past the
     * antimeridian the tile lookup wraps.
     */
    fun clampedTo(zoom: Int): WorldPoint {
        val span = (TILE shl zoom).toFloat()
        return WorldPoint(x, y.coerceIn(0f, span))
    }
}

/**
 * Web Mercator in world pixels: the projection
 * [lv.bolwarra.wetter.domain.radar.RadarGeometry] uses, with the inverse as well,
 * to read a coordinate back off wherever the map was dragged.
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
 * Longitude going round, in world pixels. Not [Math.floorMod], which takes
 * integers.
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

private val ZOOM_BUTTON = 40.dp
private val ZOOM_RADIUS = 8.dp
private val ZOOM_INSET = 8.dp
private val ZOOM_GAP = 6.dp

/** Opaque enough to read a glyph on, sheer enough to keep the map underneath. */
private const val ZOOM_SCRIM = 0.86f
private const val ZOOM_DISABLED = 0.3f
