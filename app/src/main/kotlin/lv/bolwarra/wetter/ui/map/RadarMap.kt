// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.radar.RadarGeometry
import lv.bolwarra.wetter.domain.radar.RadarMapFrame
import lv.bolwarra.wetter.ui.WetterViewModels
import lv.bolwarra.wetter.ui.components.TurningArc
import lv.bolwarra.wetter.ui.format.formatDuration
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.theme.WetterColors
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The rain around you, and where it is going. Opened from the rain chart: the
 * chart answers "here", this answers "what is coming, and from where".
 *
 * The hour behind is the sweeps the radar took; the two hours ahead are the
 * chart's projection run over a wider, coarser grid. No further, because past
 * that the models say it better (docs/providers.md).
 *
 * Time is the axis, not the frame: frames are unevenly spaced, so playback
 * advances a clock at a fixed rate and the scrubber is laid out in minutes.
 *
 * The view is the middle of the block the radar reads, about a thousand
 * kilometres across, and does not pan. The block is a tile wider on every side, so
 * rain crossing into view in the projection was seen before it arrived.
 *
 * A panel over the forecast, which stays visible behind it, blurred; a tap
 * outside puts it away.
 */
@Composable
fun RadarMapRoute(
    onClose: () -> Unit,
    viewModel: RadarMapViewModel = viewModel(factory = WetterViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface.copy(alpha = BACKDROP_SCRIM))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        RadarMapScreen(
            state = state,
            tiles = viewModel.tiles,
            attribution = viewModel.attribution,
            onClose = onClose,
            modifier = Modifier
                .padding(horizontal = spacing.l)
                .clip(RoundedCornerShape(PANEL_RADIUS))
                .background(colors.surfaceRaised)
                // Taps on the panel are the panel's; only the backdrop closes.
                .pointerInput(Unit) { detectTapGestures { } },
        )
    }
}

@Composable
fun RadarMapScreen(
    state: RadarMapState,
    tiles: TileLoader,
    attribution: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val frames = state.frames
    val zone = state.location?.zone ?: ZoneId.systemDefault()

    Column(modifier = modifier.fillMaxWidth()) {
        Header(title = state.location?.name.orEmpty(), onClose = onClose)

        if (frames.isEmpty()) {
            // Opened before the radar has landed is the ordinary first second, not an error.
            // Once the fetch has answered with nothing, say so plainly.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(colors.surfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!state.loaded) {
                        TurningArc(
                            colour = colors.textSecondary,
                            modifier = Modifier.size(LOADING_MARK),
                        )
                        Spacer(Modifier.height(spacing.m))
                    }
                    Text(
                        text = stringResource(
                            if (state.loaded) R.string.radar_nothing else R.string.radar_fetching,
                        ),
                        style = WetterTheme.type.meta,
                        color = colors.textSecondary,
                    )
                }
            }
            ControlsPlaceholder()
            return@Column
        }

        val start = frames.first().at
        // The newest thing actually seen. The map opens on it.
        val present = frames.lastOrNull { it.observed }?.at ?: start
        // While the projection is still being worked out the axis is already laid out to
        // its end, so the thumb opens at now and does not jump when the rest lands.
        val end = frames.last().at
        val axisEnd = if (state.projecting) present.plus(state.horizon) else end

        // Keyed on the present, not on the frames, so the projection arriving does not
        // move the thumb.
        var position by remember(present) { mutableStateOf(present) }
        var playing by remember { mutableStateOf(false) }

        LaunchedEffect(playing, frames) {
            if (!playing) return@LaunchedEffect
            if (!position.isBefore(end)) position = start
            var last = withFrameNanos { it }
            while (true) {
                val tick = withFrameNanos { it }
                val elapsedMs = (tick - last) / NANOS_PER_MILLI
                last = tick
                val next = position.plusMillis((elapsedMs * PLAYBACK_RATE).toLong())
                if (next.isAfter(end)) {
                    // Rest on the last frame, then go round from the oldest, so a loop shows rain
                    // arriving as well as leaving.
                    position = end
                    delay(END_HOLD_MS)
                    position = start
                    last = withFrameNanos { it }
                } else {
                    position = next
                }
            }
        }

        val shown = frames.lastOrNull { !it.at.isAfter(position) } ?: frames.first()

        RadarCanvas(
            frames = frames,
            shown = shown,
            present = present,
            latitude = state.location?.latitude,
            longitude = state.location?.longitude,
            tiles = tiles,
            colors = colors,
            attribution = attribution,
        )

        Spacer(Modifier.height(spacing.l))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayButton(playing = playing, onToggle = { playing = !playing })
            Spacer(Modifier.width(spacing.l))
            Column {
                Text(
                    text = formatTime(shown.at, zone),
                    style = WetterTheme.type.headline,
                    color = colors.textPrimary,
                )
                Text(
                    text = relativeLabel(shown.at, present),
                    style = WetterTheme.type.meta,
                    color = colors.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(spacing.m))

        Scrubber(
            frames = frames,
            position = position,
            present = present,
            axisEnd = axisEnd,
            projecting = state.projecting,
            zone = zone,
            onScrub = {
                playing = false
                // Nothing past what exists yet can be looked at.
                position = if (it.isAfter(end)) end else it
            },
            modifier = Modifier.padding(horizontal = spacing.l),
        )
        Spacer(Modifier.height(spacing.l))
    }
}

/**
 * The controls with nothing in them, so the panel keeps its height when the
 * radar lands under a finger reaching for play.
 */
@Composable
private fun ControlsPlaceholder() {
    val spacing = WetterTheme.spacing
    Spacer(Modifier.height(spacing.l))
    Row(
        modifier = Modifier.padding(horizontal = spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(spacing.touchTarget))
        Column {
            Text(" ", style = WetterTheme.type.headline)
            Text(" ", style = WetterTheme.type.meta)
        }
    }
    Spacer(Modifier.height(spacing.m))
    Spacer(Modifier.height(SCRUBBER_HEIGHT))
    Text(" ", style = WetterTheme.type.meta)
    Spacer(Modifier.height(spacing.l))
}

@Composable
private fun Header(title: String, onClose: () -> Unit) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.l, end = spacing.xs, top = spacing.xs, bottom = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = WetterTheme.type.title,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        val close = stringResource(R.string.action_close)
        Text(
            text = "×",
            style = WetterTheme.type.headline,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(spacing.touchTarget)
                .clip(CircleShape)
                .clickable(onClickLabel = close, onClick = onClose)
                .semantics { contentDescription = close }
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/**
 * The basemap, the rain over it, and the reader's place. Each field is painted to
 * a bitmap once, the present first, then outwards from it.
 */
@Composable
private fun RadarCanvas(
    frames: List<RadarMapFrame>,
    shown: RadarMapFrame,
    present: Instant,
    latitude: Double?,
    longitude: Double?,
    tiles: TileLoader,
    colors: WetterColors,
    attribution: String,
) {
    // The grid the radar was read over, and the part of it shown. The difference is
    // observed sky upwind of every edge.
    val geometry = frames.first().field.geometry
    val view = remember(geometry) { viewOf(geometry) }
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Keyed on the frame, so the projection arriving paints only what is new. Frames
    // no longer in play are dropped.
    val painted = remember(geometry) { mutableStateMapOf<RadarMapFrame, ImageBitmap>() }
    LaunchedEffect(frames) {
        painted.keys.retainAll(frames.toSet())
        frames.sortedBy { Duration.between(it.at, present).abs() }.forEach { frame ->
            if (frame in painted) return@forEach
            painted[frame] = withContext(Dispatchers.Default) {
                RadarPainter.paint(frame.field, view).asImageBitmap()
            }
        }
    }

    // Basemap tiles as close to one tile pixel per screen pixel as the grid allows,
    // so place names stay legible.
    val basemapZoom = remember(size.width, view) { basemapZoomFor(view, size.width) }
    val basemap = remember(basemapZoom, view) { basemapKeys(view, basemapZoom) }
    val loaded = remember(basemap) { mutableStateMapOf<TileKey, ImageBitmap>() }
    LaunchedEffect(basemap) {
        basemap.forEach { key -> tiles.load(key)?.let { loaded[key] = it } }
    }
    val basemapFilter = remember(colors.isLight) { basemapFilter(colors.isLight) }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(view.width.toFloat() / view.height)
            .clipToBounds()
            .background(colors.surfaceSunken)
            .onSizeChanged { size = it },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = this.size.width / view.width
            drawBasemap(basemap, loaded, view, basemapZoom, scale, basemapFilter)
            (painted[shown] ?: nearestPainted(painted, shown.at))?.let {
                drawImage(
                    image = it,
                    dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        if (latitude != null && longitude != null && size.width > 0) {
            view.pointOf(latitude, longitude)?.let { point ->
                val scale = size.width.toFloat() / view.width
                Pin(at = Offset(point.x * scale, point.y * scale), density = density)
            }
        }
        Text(
            text = attribution,
            style = WetterTheme.type.meta,
            color = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(colors.surface.copy(alpha = ATTRIBUTION_SCRIM))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * While a frame is still being painted, the nearest in time stands in rather than
 * the rain vanishing for a beat.
 */
private fun nearestPainted(painted: Map<RadarMapFrame, ImageBitmap>, at: Instant): ImageBitmap? =
    painted.minByOrNull { Duration.between(it.key.at, at).abs() }?.value

private fun DrawScope.drawBasemap(
    keys: List<TileKey>,
    loaded: Map<TileKey, ImageBitmap>,
    geometry: RadarGeometry,
    zoom: Int,
    scale: Float,
    filter: ColorFilter,
) {
    val factor = 1 shl (zoom - geometry.zoom)
    val tileInGrid = TILE.toFloat() / factor
    val side = tileInGrid * scale
    // Tile keys are wrapped for the request; placement needs the unwrapped column
    // nearest the grid.
    val firstX = Math.floorDiv(geometry.originX * factor, TILE)
    val span = 1 shl zoom
    keys.forEach { key ->
        val image = loaded[key] ?: return@forEach
        val column = firstX + Math.floorMod(key.x - firstX, span)
        val left = (column * tileInGrid - geometry.originX) * scale
        val top = (key.y * tileInGrid - geometry.originY) * scale
        drawImage(
            image = image,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            // Rounded from both edges rather than from the width, so adjacent tiles meet
            // without a hairline.
            dstSize = IntSize(
                (left + side).roundToInt() - left.roundToInt(),
                (top + side).roundToInt() - top.roundToInt(),
            ),
            colorFilter = filter,
            filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * The basemap zoom whose tiles land nearest one pixel to one pixel, capped two
 * levels above the radar's own: past that the tile count grows fourfold a step for
 * detail the rain cannot use.
 */
internal fun basemapZoomFor(geometry: RadarGeometry, widthPx: Int): Int {
    if (widthPx <= 0) return geometry.zoom
    val ratio = widthPx.toDouble() / geometry.width
    val steps = ceil(ln(ratio) / ln(2.0) - ZOOM_SLACK).toInt().coerceIn(0, MAX_EXTRA_ZOOM)
    return geometry.zoom + steps
}

/**
 * Exactly the basemap tiles under the radar block, nothing around it (see
 * MapTileSource).
 */
internal fun basemapKeys(geometry: RadarGeometry, zoom: Int): List<TileKey> {
    val factor = 1 shl (zoom - geometry.zoom)
    // Every tile the view touches: the view need not sit on tile boundaries.
    val firstX = Math.floorDiv(geometry.originX * factor, TILE)
    val firstY = Math.floorDiv(geometry.originY * factor, TILE)
    val lastX = Math.floorDiv((geometry.originX + geometry.width) * factor - 1, TILE)
    val lastY = Math.floorDiv((geometry.originY + geometry.height) * factor - 1, TILE)
    val span = 1 shl zoom
    return buildList {
        for (y in firstY..lastY) {
            if (y < 0 || y >= span) continue
            for (x in firstX..lastX) add(TileKey(zoom, Math.floorMod(x, span), y))
        }
    }
}

/**
 * The part of the radar grid the map shows: the middle [VIEW_PX] square, or
 * the whole grid where it is no larger than that.
 */
internal fun viewOf(geometry: RadarGeometry): RadarGeometry {
    val width = minOf(VIEW_PX, geometry.width)
    val height = minOf(VIEW_PX, geometry.height)
    return geometry.copy(
        originX = geometry.originX + (geometry.width - width) / 2,
        originY = geometry.originY + (geometry.height - height) / 2,
        width = width,
        height = height,
    )
}

/** Three tiles across: about a thousand kilometres at zoom 6 in the mid-latitudes. */
private const val VIEW_PX = 3 * TILE

/**
 * The basemap in greys, inverted on the dark plate, with contrast pulled in: rain
 * stays the only saturated colour (docs/design-principles.md) and the ground reads
 * as ground.
 */
private fun basemapFilter(isLight: Boolean): ColorFilter {
    val gain = if (isLight) LIGHT_GAIN else -DARK_GAIN
    val offset = if (isLight) LIGHT_OFFSET else DARK_OFFSET
    val r = LUMA_R * gain
    val g = LUMA_G * gain
    val b = LUMA_B * gain
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                r, g, b, 0f, offset,
                r, g, b, 0f, offset,
                r, g, b, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

@Composable
private fun PlayButton(playing: Boolean, onToggle: () -> Unit) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val label = stringResource(if (playing) R.string.radar_pause else R.string.radar_play)
    val ink = colors.onSurfaceStrong
    Canvas(
        Modifier
            .size(spacing.touchTarget)
            .clip(CircleShape)
            .background(colors.surfaceStrong)
            .clickable(onClickLabel = label, onClick = onToggle)
            .semantics { contentDescription = label },
    ) {
        val unit = size.minDimension
        if (playing) {
            val barWidth = unit * 0.11f
            val barHeight = unit * 0.36f
            val top = (size.height - barHeight) / 2f
            val gap = unit * 0.1f
            listOf(center.x - gap / 2f - barWidth, center.x + gap / 2f).forEach { left ->
                drawRect(color = ink, topLeft = Offset(left, top), size = Size(barWidth, barHeight))
            }
        } else {
            val half = unit * 0.2f
            // Nudged right: a triangle centred on its box looks left of centre.
            val nudge = unit * 0.035f
            val path = Path().apply {
                moveTo(center.x - half * 0.8f + nudge, center.y - half)
                lineTo(center.x + half + nudge, center.y)
                lineTo(center.x - half * 0.8f + nudge, center.y + half)
                close()
            }
            drawPath(path, ink)
        }
    }
}

/**
 * Time, laid out in minutes, with every frame marked and the present set apart.
 * Dragging or tapping stops playback.
 */
@Composable
private fun Scrubber(
    frames: List<RadarMapFrame>,
    position: Instant,
    present: Instant,
    axisEnd: Instant,
    projecting: Boolean,
    zone: ZoneId,
    onScrub: (Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val start = frames.first().at
    val end = axisEnd
    val spanMs = Duration.between(start, end).toMillis().coerceAtLeast(1L).toFloat()
    fun fractionOf(at: Instant) = Duration.between(start, at).toMillis() / spanMs
    fun instantAt(x: Float, width: Float): Instant =
        start.plusMillis(((x / width).coerceIn(0f, 1f) * spanMs).toLong())

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(SCRUBBER_HEIGHT)
                .pointerInput(frames) {
                    detectTapGestures { onScrub(instantAt(it.x, size.width.toFloat())) }
                }
                .pointerInput(frames) {
                    detectHorizontalDragGestures { change, _ ->
                        onScrub(instantAt(change.position.x, size.width.toFloat()))
                    }
                },
        ) {
            val midY = size.height / 2f
            val stroke = 2.dp.toPx()
            val nowX = fractionOf(present) * size.width
            // Behind, what was seen, on the quiet line; ahead, the projection, in the rain
            // colour.
            drawLine(colors.hairline, Offset(0f, midY), Offset(nowX, midY), stroke)
            drawLine(
                // Grey until there is a projection to play.
                if (projecting) {
                    colors.hairline
                } else {
                    RadarPainter.lightInk.copy(
                        alpha = AHEAD_LINE_ALPHA,
                    )
                },
                Offset(nowX, midY),
                Offset(size.width, midY),
                stroke,
            )
            val tick = 3.dp.toPx()
            frames.forEach {
                val x = fractionOf(it.at) * size.width
                drawLine(
                    colors.gridline,
                    Offset(x, midY - tick),
                    Offset(x, midY + tick),
                    1.dp.toPx(),
                )
            }
            val nowHalf = 9.dp.toPx()
            drawLine(
                colors.textSecondary,
                Offset(nowX, midY - nowHalf),
                Offset(nowX, midY + nowHalf),
                stroke,
            )
            drawCircle(
                color = RadarPainter.lightInk,
                radius = 7.dp.toPx(),
                center = Offset(fractionOf(position).coerceIn(0f, 1f) * size.width, midY),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTime(start, zone),
                style = WetterTheme.type.meta,
                color = colors.textTertiary,
            )
            if (projecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurningArc(colour = colors.textTertiary)
                    Spacer(Modifier.width(WetterTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.radar_projecting),
                        style = WetterTheme.type.meta,
                        color = colors.textTertiary,
                    )
                }
            }
            Text(formatTime(end, zone), style = WetterTheme.type.meta, color = colors.textTertiary)
        }
    }
}

@Composable
private fun relativeLabel(at: Instant, present: Instant): String {
    val offset = Duration.between(present, at)
    return when {
        offset.abs() < Duration.ofMinutes(1) -> stringResource(R.string.radar_now)
        offset.isNegative -> stringResource(R.string.radar_ago, formatDuration(offset.abs()))
        else -> stringResource(R.string.radar_ahead, formatDuration(offset))
    }
}

/** Ten minutes of weather in about six tenths of a second: three hours in eleven. */
private const val PLAYBACK_RATE = 1_000.0
private const val END_HOLD_MS = 1_200L
private const val NANOS_PER_MILLI = 1_000_000.0

/** Enough to push the forecast back, not so much that it disappears. */
private const val BACKDROP_SCRIM = 0.35f
private val PANEL_RADIUS = 24.dp
private const val ATTRIBUTION_SCRIM = 0.7f

private val SCRUBBER_HEIGHT = 36.dp
private val LOADING_MARK = 18.dp
private const val AHEAD_LINE_ALPHA = 0.6f

/** Accept a slightly soft basemap before paying four times the tiles for a sharp one. */
private const val ZOOM_SLACK = 0.25
private const val MAX_EXTRA_ZOOM = 2

private const val LUMA_R = 0.2126f
private const val LUMA_G = 0.7152f
private const val LUMA_B = 0.0722f
private const val LIGHT_GAIN = 0.7f
private const val LIGHT_OFFSET = 76f
private const val DARK_GAIN = 0.6f
private const val DARK_OFFSET = 200f
