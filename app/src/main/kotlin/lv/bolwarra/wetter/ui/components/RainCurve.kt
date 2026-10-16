// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.chart.MonotoneCurve
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.ui.format.formatMillimetres
import lv.bolwarra.wetter.ui.format.formatWeekdayShort
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.Emphasis
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The precipitation curve: how hard it will be raining over the coming hours,
 * scrubbable to read off any moment.
 *
 * It plots a rate (mm/h) at instants, which is legitimate to interpolate; values
 * between real samples carry a `≈`. The monotone spline cannot overshoot, so it
 * never draws rain nobody forecast ([MonotoneCurve]).
 *
 * [span] is what the chart holds and [window] is what fits on screen; the rest
 * scrolls sideways. Both scales are fixed: height is intensity and width is pixels
 * per hour, so the next two hours are never squeezed to fit a day.
 */
@Composable
fun RainCurve(
    hours: List<HourlyWeather>,
    zone: ZoneId,
    from: Instant,
    span: Duration,
    /** How much of [span] is on screen at once; the rest is scrolled to. */
    window: Duration,
    modifier: Modifier = Modifier,
    /**
     * The fused radar-and-model timeline, when there is one. It supersedes the
     * hourly rows, which it already includes.
     */
    fused: List<FusedPrecipitation> = emptyList(),
) {
    if (hours.size < 2 && fused.size < 2) return

    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val measurer = rememberTextMeasurer()

    val points = remember(hours, fused, from, span) {
        val built = if (fused.size >= 2) {
            fused.map {
                CurvePoint(
                    at = it.at,
                    millimetresPerHour = it.millimetresPerHour.toFloat().coerceAtLeast(0f),
                    // Here `≈` means modelled rather than observed by radar.
                    interpolated = it.radarShare < RADAR_BACKED,
                )
            }.smoothed()
        } else {
            resample(hours.sortedBy { it.timestamp })
        }
        built.clipTo(from, from.plus(span))
    }
    if (points.size < 2) return

    var scrubbed by remember { mutableStateOf<Int?>(null) }
    // Not keyed on anything: the points rebuild every minute, and the reader's
    // scroll position must survive that.
    val scroll = rememberScrollState()

    val axisStyle = WetterTheme.type.axis.copy(color = colors.textTertiary)

    // Resolved here because the draw scope cannot reach string resources. Each entry
    // is the bottom of its band.
    val guides = listOf(
        0f to stringResource(PrecipitationIntensity.LIGHT.labelRes()),
        heightFraction(PrecipitationIntensity.MODERATE_MM_PER_HOUR.toFloat()) to
            stringResource(PrecipitationIntensity.MODERATE.labelRes()),
        heightFraction(PrecipitationIntensity.HEAVY_MM_PER_HOUR.toFloat()) to
            stringResource(PrecipitationIntensity.HEAVY.labelRes()),
    )

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val viewport = maxWidth
        val track = trackWidth(
            viewport = viewport,
            covered = Duration.between(points.first().at, points.last().at),
            window = window,
        )

        // The track, rule and axis span the whole day and scroll as one, so a tick
        // never drifts from the point it labels.
        Column(Modifier.horizontalScroll(scroll).width(track)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TRACK_HEIGHT)
                    // Keyed on the count, not the series: the series rebuilds every minute and a
                    // hold in progress must survive it.
                    .pointerInput(points.size) {
                        // Hold to read, drag to travel. A drag scrolls the day, so reading a value
                        // starts from a long press, which a scroller ignores.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { scrubbed = indexAt(it.x, size.width, points.size) },
                            onDragEnd = { scrubbed = null },
                            onDragCancel = { scrubbed = null },
                            onDrag = { change, _ ->
                                scrubbed = indexAt(change.position.x, size.width, points.size)
                                change.consume()
                            },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
                    drawIntensityRules(guides, colors.gridline)
                    drawDayBreaks(points, zone, colors.gridline)
                    // Read inside the draw, so scrolling redraws the labels instead of recomposing.
                    drawIntensityLabels(
                        guides = guides,
                        measurer = measurer,
                        style = axisStyle,
                        rightEdge = scroll.value + viewport.toPx(),
                    )
                    val path = curvePath(points)
                    drawPath(
                        path = Path().apply {
                            addPath(path)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        },
                        brush = Brush.verticalGradient(
                            listOf(
                                colors.textPrimary.copy(alpha = FILL_TOP_ALPHA),
                                colors.textPrimary.copy(alpha = FILL_BOTTOM_ALPHA),
                            ),
                        ),
                    )
                    drawPath(
                        path = path,
                        brush = intensityBrush(colors.textTertiary, colors.textPrimary),
                        style = Stroke(width = STROKE.toPx(), cap = StrokeCap.Round),
                    )
                    scrubbed?.let { index ->
                        drawCursor(
                            index = index,
                            points = points,
                            lineColour = colors.textPrimary.copy(alpha = CURSOR_ALPHA),
                            dotColour = colors.textPrimary,
                            ringColour = colors.surfaceRaised,
                        )
                    }
                }
            }

            Spacer(Modifier.height(spacing.s))
            HairlineRule()
            Spacer(Modifier.height(spacing.xs))

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(AXIS_HEIGHT)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                drawTimeAxis(points, zone, measurer, axisStyle, colors.hairline)
                // Read inside the draw, like the band labels, so the axis dissolves at
                // whichever edge has more beyond it rather than cutting a label.
                val left = scroll.value.toFloat()
                fadeEdges(left, left + viewport.toPx(), AXIS_FADE.toPx())
            }
        }

        // Overlaid in the top of the heavy lane, which is empty in almost every forecast,
        // so it costs no height and the layout never jumps.
        Readout(
            point = scrubbed?.let(points::getOrNull),
            zone = zone,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/**
 * How wide the whole chart is: the scale set by the window, times what the
 * series actually covers, and never narrower than the screen.
 */
internal fun trackWidth(viewport: Dp, covered: Duration, window: Duration): Dp {
    if (window.isZero || window.isNegative) return viewport
    val screens = covered.toMillis().toFloat() / window.toMillis().toFloat()
    return viewport * screens.coerceAtLeast(1f)
}

/**
 * The window cut out of the spline after fitting, on the existing ten-minute
 * grid so the half-hour rules stay on the half hour.
 */
private fun List<CurvePoint>.clipTo(from: Instant, to: Instant): List<CurvePoint> =
    filter { !it.at.isBefore(from) && !it.at.isAfter(to) }

/**
 * Rounds the corners of an already-spaced series with the same monotone spline,
 * so the fused timeline is drawn as a curve rather than a polyline.
 */
private fun List<CurvePoint>.smoothed(): List<CurvePoint> {
    if (size < 2) return this
    val values = map { it.millimetresPerHour }
    val tangents = MonotoneCurve.tangents(values)

    val out = ArrayList<CurvePoint>(size * SMOOTH_STEPS + 1)
    for (i in 0 until size - 1) {
        val spanMillis = Duration.between(this[i].at, this[i + 1].at).toMillis()
        repeat(SMOOTH_STEPS) { step ->
            val t = step.toFloat() / SMOOTH_STEPS
            out += CurvePoint(
                at = this[i].at.plusMillis(spanMillis * step / SMOOTH_STEPS),
                millimetresPerHour = MonotoneCurve.valueAt(values, tangents, i, t)
                    .coerceAtLeast(0f),
                interpolated = this[i].interpolated,
            )
        }
    }
    out += last()
    return out
}

/** One moment on the curve. */
private data class CurvePoint(
    val at: Instant,
    val millimetresPerHour: Float,
    /** False only where this lands exactly on one of the forecast's own samples. */
    val interpolated: Boolean,
)

/**
 * Walks the spline between the forecast's samples at [STEP_MINUTES], so there is
 * something to scrub to and the line reads as a curve.
 */
private fun resample(hours: List<HourlyWeather>): List<CurvePoint> {
    val values = hours.map { (it.precipitation ?: 0.0).toFloat().coerceAtLeast(0f) }
    val tangents = MonotoneCurve.tangents(values)
    val sourceStep = Duration.between(hours[0].timestamp, hours[1].timestamp)
    val perInterval = (sourceStep.toMinutes() / STEP_MINUTES).toInt().coerceAtLeast(1)

    val points = ArrayList<CurvePoint>(values.size * perInterval + 1)
    for (i in 0 until values.size - 1) {
        repeat(perInterval) { step ->
            val t = step.toFloat() / perInterval
            points += CurvePoint(
                at = hours[i].timestamp.plusSeconds(sourceStep.seconds * step / perInterval),
                // Clamped at zero against floating-point undershoot.
                millimetresPerHour = MonotoneCurve.valueAt(values, tangents, i, t)
                    .coerceAtLeast(0f),
                interpolated = step != 0,
            )
        }
    }
    points += CurvePoint(hours.last().timestamp, values.last(), interpolated = false)
    return points
}

/**
 * The value under the finger, or the scale when nothing is pointed at: a fixed
 * row, so nothing covers the curve being read.
 */
@Composable
private fun Readout(point: CurvePoint?, zone: ZoneId, modifier: Modifier = Modifier) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors

    Row(
        modifier.fillMaxWidth().height(READOUT_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Blank but present while nothing is selected, so the layout holds its height.
        if (point == null) return@Row

        Text(
            // The time, and the word for what it will feel like.
            text = stringResource(
                R.string.curve_reading,
                clockOf(point.at, zone),
                stringResource(curveLevel(point.millimetresPerHour.toDouble()).labelRes()),
            ),
            style = WetterTheme.type.meta,
            color = colors.textTertiary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            // A tilde on values between the forecast's own samples.
            text = stringResource(
                if (point.interpolated) R.string.curve_rate_about else R.string.curve_rate,
                formatMillimetres(point.millimetresPerHour.toDouble(), units.precipitation),
                units.precipitation.label,
            ),
            style = WetterTheme.type.title,
            color = colors.textPrimary,
        )
    }
}

/**
 * The line's colour, deepening as it climbs into heavier rain: a vertical gradient
 * with a hard step at each band edge, so a line crossing a boundary changes tone
 * there. It runs in ink rather than the rain colour, which is reserved for
 * precipitation that is actually falling.
 */
private fun DrawScope.intensityBrush(muted: Color, full: Color): Brush {
    fun step(mix: Float) = lerp(muted, full, mix)

    // Gradient space runs down from the top, so a band's ceiling is its lower stop here.
    val heavyEdge = 1f - heightFraction(PrecipitationIntensity.HEAVY_MM_PER_HOUR.toFloat())
    val moderateEdge = 1f - heightFraction(PrecipitationIntensity.MODERATE_MM_PER_HOUR.toFloat())

    return Brush.verticalGradient(
        0f to step(HEAVY_MIX),
        heavyEdge to step(HEAVY_MIX),
        // A hair apart: stops must increase, and equal offsets are not guaranteed to draw as an
        // edge.
        (heavyEdge + SEAM) to step(MODERATE_MIX),
        moderateEdge to step(MODERATE_MIX),
        (moderateEdge + SEAM) to step(LIGHT_MIX),
        1f to step(LIGHT_MIX),
        startY = 0f,
        endY = size.height,
    )
}

/** Narrow enough to read as an edge, wide enough to survive rounding. */
private const val SEAM = 0.001f

/**
 * How far along the ink ramp each band sits; light a touch above the faintest
 * tone so a thin line stays visible.
 */
private const val LIGHT_MIX = 0.06f
private const val MODERATE_MIX = 0.5f
private const val HEAVY_MIX = 1f

/**
 * Faint rules and words at each band threshold, so the line's height reads as a
 * scale. The whole scale is always drawn, so the chart means the same thing every
 * day, and it sits behind the curve.
 */
private fun DrawScope.drawIntensityRules(guides: List<Pair<Float, String>>, rule: Color) {
    guides.forEach { (fraction, _) ->
        // The lowest band's boundary is the chart's floor, already drawn by the axis.
        if (fraction <= 0f) return@forEach
        val y = size.height * (1f - fraction)
        drawLine(
            color = rule,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = GUIDE_RULE_WIDTH.toPx(),
        )
    }
}

/**
 * The band words, held to the right edge of the screen while the chart scrolls
 * under them, and drawn on the chart's canvas so the line still passes over them.
 */
private fun DrawScope.drawIntensityLabels(
    guides: List<Pair<Float, String>>,
    measurer: TextMeasurer,
    style: TextStyle,
    rightEdge: Float,
) {
    guides.forEach { (fraction, label) ->
        val y = size.height * (1f - fraction)
        val measured = measurer.measure(label, style)
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                // Clamped to the content, so an overscroll cannot carry the words off the chart.
                (rightEdge - measured.size.width - GUIDE_LABEL_INSET.toPx())
                    .coerceIn(0f, size.width - measured.size.width),
                y - measured.size.height - GUIDE_LABEL_GAP.toPx(),
            ),
        )
    }
}

/**
 * Where one day ends, drawn full height and as faint as a band rule; the axis
 * names the day that starts there.
 */
private fun DrawScope.drawDayBreaks(points: List<CurvePoint>, zone: ZoneId, colour: Color) {
    forEachDayBreak(points, zone) { x ->
        drawLine(
            color = colour,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = GUIDE_RULE_WIDTH.toPx(),
        )
    }
}

/** Every local midnight inside the drawn window, positioned from the clock. */
private inline fun DrawScope.forEachDayBreak(
    points: List<CurvePoint>,
    zone: ZoneId,
    action: (Float) -> Unit,
) {
    if (points.size < 2) return
    val start = points.first().at
    val end = points.last().at
    val span = Duration.between(start, end).toMillis().toFloat()
    if (span <= 0f) return

    var day = start.atZone(zone).toLocalDate()
    while (true) {
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant()
        if (midnight.isAfter(end)) return
        action(Duration.between(start, midnight).toMillis() / span * size.width)
        day = day.plusDays(1)
    }
}

/**
 * The hour marks under the chart, a label on the hour and a tick on the half
 * hour, positioned from the clock rather than from the samples so they never
 * depend on which minute the series was built at.
 */
private fun DrawScope.drawTimeAxis(
    points: List<CurvePoint>,
    zone: ZoneId,
    measurer: TextMeasurer,
    style: TextStyle,
    tick: Color,
) {
    if (points.size < 2) return
    val start = points.first().at
    val end = points.last().at
    val span = Duration.between(start, end).toMillis().toFloat()
    if (span <= 0f) return

    val step = Duration.ofMinutes(LABEL_EVERY_MINUTES.toLong())
    // The first boundary at or after the window opens.
    var mark = start.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
    while (mark.isBefore(start)) mark = mark.plus(step)

    while (!mark.isAfter(end)) {
        val x = Duration.between(start, mark).toMillis() / span * size.width
        val local = mark.atZone(zone)
        val onTheHour = local.minute == 0

        drawLine(
            color = tick,
            start = Offset(x, 0f),
            end = Offset(x, (if (onTheHour) TICK_HOUR else TICK_HALF).toPx()),
            strokeWidth = if (onTheHour) 2f else 1f,
        )

        if (onTheHour) {
            // Midnight is labelled with the day it opens rather than "0:00".
            val label = if (local.hour == 0) {
                formatWeekdayShort(local.toLocalDate())
            } else {
                clockOf(mark, zone)
            }
            val measured = measurer.measure(label, style)
            // Nudged inside the bounds so the first and last labels are not clipped.
            val left = (x - measured.size.width / 2f)
                .coerceIn(0f, size.width - measured.size.width)
            drawText(measured, topLeft = Offset(left, LABEL_TOP.toPx()))
        }
        mark = mark.plus(step)
    }
}

/** Fades the content out towards each edge of the visible window that has more beyond it. */
private fun DrawScope.fadeEdges(left: Float, right: Float, fade: Float) {
    if (left > 0f) {
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black),
                left,
                left + fade,
            ),
            topLeft = Offset(left, 0f),
            size = Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
    }
    if (right < size.width) {
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Black, Color.Transparent),
                right - fade,
                right,
            ),
            topLeft = Offset(right - fade, 0f),
            size = Size(fade, size.height),
            blendMode = BlendMode.DstIn,
        )
    }
}

private fun DrawScope.drawCursor(
    index: Int,
    points: List<CurvePoint>,
    lineColour: Color,
    dotColour: Color,
    ringColour: Color,
) {
    val x = xOf(index, points.size)
    val y = yOf(points[index].millimetresPerHour, size.height)

    drawLine(
        color = lineColour,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = CURSOR_WIDTH.toPx(),
    )
    // A ring in the tile's ground, so the dot sits on the curve rather than in the fill.
    drawCircle(ringColour, radius = DOT_RADIUS.toPx() + DOT_RING.toPx(), center = Offset(x, y))
    drawCircle(dotColour, radius = DOT_RADIUS.toPx(), center = Offset(x, y))
}

private fun DrawScope.curvePath(points: List<CurvePoint>): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = xOf(index, points.size)
        val y = yOf(point.millimetresPerHour, size.height)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/** Points sit at their own instant, edge to edge: a rate has a value at a moment. */
private fun DrawScope.xOf(index: Int, count: Int): Float =
    if (count <= 1) 0f else size.width * index / (count - 1)

private fun yOf(value: Float, height: Float): Float =
    height - heightFraction(value).coerceIn(0f, 1f) * height

private fun indexAt(x: Float, width: Int, count: Int): Int {
    if (width <= 0 || count <= 1) return 0
    val step = width.toFloat() / (count - 1)
    return ((x + step / 2f) / step).toInt().coerceIn(0, count - 1)
}

/** "1:30", without a leading zero, so the labels fit across a phone. */
private fun clockOf(instant: Instant, zone: ZoneId): String {
    val time = instant.atZone(zone)
    return "%d:%02d".format(time.hour, time.minute)
}

/**
 * The band geometry, shared with the widget through `:domain`. Height is
 * intensity on a fixed scale anchored to the conventional bands, each given an
 * equal slice, so a drizzle never looks like a downpour and still shows.
 */
private fun curveLevel(millimetresPerHour: Double): PrecipitationIntensity =
    RainCurveBands.levelOf(millimetresPerHour)

/** Where a rate sits on the track, as a fraction of its height. */
private fun heightFraction(millimetresPerHour: Float): Float =
    RainCurveBands.heightFraction(millimetresPerHour)

/** Above this share, the point is carried by radar rather than by the model. */
private const val RADAR_BACKED = 0.5

private val GUIDE_LABEL_GAP = 2.dp
private val GUIDE_RULE_WIDTH = 1.dp
private val GUIDE_LABEL_INSET = 4.dp

/** Sub-steps drawn between two fused points, purely to round the corners. */
private const val SMOOTH_STEPS = 5

private const val STEP_MINUTES = 10L

/** How often the axis is labelled. */
private const val LABEL_EVERY_MINUTES = 30

/** Tall enough for every band to hold its word. */
private val TRACK_HEIGHT = 148.dp
private val READOUT_HEIGHT = 24.dp
private val AXIS_HEIGHT = 26.dp

/** How far the time axis dissolves into each scrolling edge. */
private val AXIS_FADE = 24.dp

private val STROKE = 2.dp
private val CURSOR_WIDTH = 1.5.dp
private val DOT_RADIUS = 4.dp
private val DOT_RING = 2.dp
private val TICK_HOUR = 5.dp
private val TICK_HALF = 3.dp
private val LABEL_TOP = 8.dp

private const val FILL_TOP_ALPHA = Emphasis.MUTED
private const val FILL_BOTTOM_ALPHA = 0f
private const val CURSOR_ALPHA = Emphasis.MUTED
