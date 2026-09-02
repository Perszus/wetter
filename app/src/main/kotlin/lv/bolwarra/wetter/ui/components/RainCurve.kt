package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.ui.chart.MonotoneCurve
import lv.bolwarra.wetter.ui.format.formatMillimetres
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The precipitation curve: how hard it will be raining over the next few hours,
 * scrubbable to read off any moment of it.
 *
 * The one place in this app where `Canvas` is the right answer, and it is
 * confined here rather than becoming the foundation everything else is drawn on.
 *
 * ### It is a rate curve, and that is what makes the fine reading honest
 *
 * A provider reports millimetres accumulated across an hour. Reading a value off
 * a smooth line through those totals and calling it "the rain at 01:20" would be
 * inventing a measurement. Reading it as a *rate* — millimetres per hour, at
 * that moment — is a legitimate thing to interpolate, which is why the readout
 * is `mm/h` and why the curve is plotted at instants rather than as blocks
 * sitting over intervals.
 *
 * Points between the forecast's own samples are still interpolated, so they wear
 * a `≈`. The monotone spline guarantees such a point lies between the two real
 * samples either side of it, so the mark is a statement about provenance rather
 * than a warning about the number.
 *
 * ### The curve cannot overshoot
 *
 * Ordinary smoothing dips below zero approaching a shower and bulges above the
 * peak inside it, drawing rainfall nobody forecast. See [MonotoneCurve].
 */
@Composable
fun RainCurve(hours: List<HourlyWeather>, zone: ZoneId, modifier: Modifier = Modifier) {
    if (hours.size < 2) return

    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val measurer = rememberTextMeasurer()

    val points = remember(hours) { resample(hours.sortedBy { it.timestamp }) }
    val ceiling = remember(points) { ceilingFor(points) }

    var scrubbed by remember { mutableStateOf<Int?>(null) }

    val axisStyle = WetterTheme.type.axis.copy(color = colors.textTertiary)

    Column(modifier.fillMaxWidth()) {
        Readout(point = scrubbed?.let(points::getOrNull), zone = zone)
        Spacer(Modifier.height(spacing.s))

        Box(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .pointerInput(points) {
                    // The value appears on touch-down, so a tap reads a moment.
                    // After that direction decides the winner: cross the slop
                    // horizontally and this consumes the pointer and scrubs, move
                    // vertically and the page scroll takes it. Claiming the
                    // pointer outright would make the chart a dead zone you
                    // cannot scroll past.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        scrubbed = indexAt(down.position.x, size.width, points.size)

                        val crossed = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }
                        if (crossed != null) {
                            horizontalDrag(crossed.id) { change ->
                                scrubbed = indexAt(change.position.x, size.width, points.size)
                                change.consume()
                            }
                        }
                        scrubbed = null
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
                val path = curvePath(points, ceiling)
                drawPath(
                    path = Path().apply {
                        addPath(path)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    },
                    brush = Brush.verticalGradient(
                        listOf(
                            colors.precipitation.copy(alpha = FILL_TOP_ALPHA),
                            colors.precipitation.copy(alpha = FILL_BOTTOM_ALPHA),
                        ),
                    ),
                )
                drawPath(
                    path = path,
                    color = colors.precipitation,
                    style = Stroke(width = STROKE.toPx(), cap = StrokeCap.Round),
                )
                val ceilingLabel = measurer.measure(formatMillimetres(ceiling), axisStyle)
                drawText(
                    ceilingLabel,
                    topLeft = Offset(size.width - ceilingLabel.size.width, 0f),
                )

                scrubbed?.let { index ->
                    drawCursor(
                        index = index,
                        points = points,
                        ceiling = ceiling,
                        lineColour = colors.textPrimary.copy(alpha = CURSOR_ALPHA),
                        dotColour = colors.precipitation,
                        ringColour = colors.surfaceRaised,
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.s))
        HairlineRule()
        Spacer(Modifier.height(spacing.xs))

        Canvas(Modifier.fillMaxWidth().height(AXIS_HEIGHT)) {
            drawTimeAxis(points, zone, measurer, axisStyle, colors.hairline)
        }
    }
}

/** One moment on the curve. */
private data class CurvePoint(
    val at: Instant,
    val millimetresPerHour: Float,
    /** False only where this lands exactly on one of the forecast's own samples. */
    val interpolated: Boolean,
)

/**
 * Fills in the gaps between the forecast's samples at [STEP_MINUTES].
 *
 * The spline is already continuous; this walks it at a fixed step so the chart
 * has something to scrub to and so the drawn line is dense enough to read as a
 * curve rather than a set of joined corners.
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
                // Clamped at zero: the spline cannot overshoot, but floating point
                // can still land a hair below it.
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
 * The value under the finger, or the scale when nothing is being pointed at.
 *
 * A fixed row rather than a floating tooltip: a bubble over the curve covers the
 * thing being read, and its arrival and departure are two more animations to get
 * wrong.
 */
@Composable
private fun Readout(point: CurvePoint?, zone: ZoneId) {
    val colors = WetterTheme.colors

    Row(
        Modifier.fillMaxWidth().height(READOUT_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Blank while nothing is selected, rather than absent: the row holds its
        // height so the layout does not jump the moment a finger lands.
        if (point == null) return@Row

        Text(
            text = clockOf(point.at, zone),
            style = WetterTheme.type.meta,
            color = colors.textTertiary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            // A tilde on anything between the forecast's own samples. The spline
            // guarantees it sits between the two real values either side, so this
            // marks where the number came from rather than warning about it.
            text = stringResource(
                if (point.interpolated) R.string.curve_rate_about else R.string.curve_rate,
                formatMillimetres(point.millimetresPerHour.toDouble()),
            ),
            style = WetterTheme.type.title,
            color = colors.precipitation,
        )
    }
}

/**
 * The time axis: a label on the hour, a tick on the half hour.
 *
 * Eleven labels reading "1:30" do not fit across a phone - they collided at both
 * ends, and clamping the outermost ones inside the bounds only pushed them into
 * their neighbours. A tick carries the half hour perfectly well: its position is
 * the information, and the hour either side of it says what it is.
 */
private fun DrawScope.drawTimeAxis(
    points: List<CurvePoint>,
    zone: ZoneId,
    measurer: TextMeasurer,
    style: TextStyle,
    tick: Color,
) {
    points.forEachIndexed { index, point ->
        val minute = point.at.atZone(zone).minute
        if (minute % LABEL_EVERY_MINUTES != 0) return@forEachIndexed

        val x = xOf(index, points.size)
        val onTheHour = minute == 0

        drawLine(
            color = tick,
            start = Offset(x, 0f),
            end = Offset(x, (if (onTheHour) TICK_HOUR else TICK_HALF).toPx()),
            strokeWidth = if (onTheHour) 2f else 1f,
        )

        if (!onTheHour) return@forEachIndexed

        val measured = measurer.measure(clockOf(point.at, zone), style)
        // Nudged inside the bounds so the first and last labels are not clipped
        // by the edge of the tile.
        val left = (x - measured.size.width / 2f)
            .coerceIn(0f, size.width - measured.size.width)
        drawText(measured, topLeft = Offset(left, LABEL_TOP.toPx()))
    }
}

private fun DrawScope.drawCursor(
    index: Int,
    points: List<CurvePoint>,
    ceiling: Double,
    lineColour: Color,
    dotColour: Color,
    ringColour: Color,
) {
    val x = xOf(index, points.size)
    val y = yOf(points[index].millimetresPerHour, ceiling, size.height)

    drawLine(
        color = lineColour,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = CURSOR_WIDTH.toPx(),
    )
    // A ring in the tile's own ground, so the dot reads as sitting on the curve
    // rather than as a bead lost in the fill.
    drawCircle(ringColour, radius = DOT_RADIUS.toPx() + DOT_RING.toPx(), center = Offset(x, y))
    drawCircle(dotColour, radius = DOT_RADIUS.toPx(), center = Offset(x, y))
}

private fun DrawScope.curvePath(points: List<CurvePoint>, ceiling: Double): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = xOf(index, points.size)
        val y = yOf(point.millimetresPerHour, ceiling, size.height)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/**
 * Points sit at their own instant, edge to edge.
 *
 * A rate has a value at a moment, so it belongs on the axis at that moment —
 * unlike an accumulation, which describes a span and would belong at the centre
 * of one.
 */
private fun DrawScope.xOf(index: Int, count: Int): Float =
    if (count <= 1) 0f else size.width * index / (count - 1)

private fun yOf(value: Float, ceiling: Double, height: Float): Float =
    height - (value / ceiling.toFloat()).coerceIn(0f, 1f) * height

private fun indexAt(x: Float, width: Int, count: Int): Int {
    if (width <= 0 || count <= 1) return 0
    val step = width.toFloat() / (count - 1)
    return ((x + step / 2f) / step).toInt().coerceIn(0, count - 1)
}

/** "1:30" — no leading zero, because thirteen labels have to fit across a phone. */
private fun clockOf(instant: Instant, zone: ZoneId): String {
    val time = instant.atZone(zone)
    return "%d:%02d".format(time.hour, time.minute)
}

/**
 * The ceilings the chart is allowed to use, in millimetres per hour: the tops of
 * the conventional intensity bands, so the scale only ever lands somewhere that
 * means something meteorologically.
 */
private val CEILINGS = listOf(0.5, 2.5, 8.0, 50.0)

/**
 * A fixed ceiling is honest and, on most days, useless — 0.2 mm of drizzle draws
 * as a flat line and the chart says nothing. Scaling to the peak is the usual
 * fix and is worse, because it makes drizzle and downpour draw identically. So
 * the ceiling steps between fixed values and the chart states which one it is
 * using. The scale can change; it can never change silently.
 */
private fun ceilingFor(points: List<CurvePoint>): Double {
    val peak = points.maxOfOrNull { it.millimetresPerHour }?.toDouble() ?: 0.0
    if (peak <= 0.0) return CEILINGS[1]
    return CEILINGS.firstOrNull { it >= peak } ?: CEILINGS.last()
}

/** How finely the curve is walked between the forecast's own samples. */
private const val STEP_MINUTES = 10L

/** How often the axis is labelled. */
private const val LABEL_EVERY_MINUTES = 30

private val TRACK_HEIGHT = 96.dp
private val READOUT_HEIGHT = 24.dp
private val AXIS_HEIGHT = 26.dp
private val STROKE = 2.dp
private val CURSOR_WIDTH = 1.5.dp
private val DOT_RADIUS = 4.dp
private val DOT_RING = 2.dp
private val TICK_HOUR = 5.dp
private val TICK_HALF = 3.dp
private val LABEL_TOP = 8.dp

private const val FILL_TOP_ALPHA = 0.34f
private const val FILL_BOTTOM_ALPHA = 0.02f
private const val CURSOR_ALPHA = 0.45f
