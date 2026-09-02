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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.ZoneId
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.ui.chart.MonotoneCurve
import lv.bolwarra.wetter.ui.format.formatMillimetres
import lv.bolwarra.wetter.ui.format.formatMillimetresWithUnit
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The precipitation curve: a flowing trace of how hard it will be raining,
 * scrubbable to read off any point of it.
 *
 * The one place in this app where `Canvas` is the right answer — a filled area
 * under a smooth line is not something `Box` heights can express — and it is
 * confined here rather than becoming the foundation everything else is drawn on.
 *
 * ### Two things it will not do
 *
 * **It will not overshoot.** The curve is a monotone cubic spline, so the trace
 * between two samples always lies between them. The usual smoothing would dip
 * below zero approaching a shower and bulge above the peak inside it, drawing
 * rainfall nobody forecast. See [MonotoneCurve].
 *
 * **It will not claim a resolution it does not have.** Scrubbing reads out the
 * interval a sample covers, not the instant under the finger. An hourly forecast
 * says `08:00-09:00 · 0.3 mm`, because that is the shape of what was forecast:
 * three tenths of a millimetre spread across an hour, not a value that existed
 * at 08:23. Where a provider genuinely supplies sub-hourly data the interval
 * narrows on its own and the readout follows.
 */
@Composable
fun RainCurve(hours: List<HourlyWeather>, zone: ZoneId, modifier: Modifier = Modifier) {
    if (hours.size < 2) return

    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val ordered = remember(hours) { hours.sortedBy { it.timestamp } }

    val values = remember(ordered) {
        ordered.map { (it.precipitation ?: 0.0).toFloat().coerceAtLeast(0f) }
    }
    val tangents = remember(values) { MonotoneCurve.tangents(values) }
    val ceiling = remember(values) { ceilingFor(values) }

    var scrubbed by remember { mutableStateOf<Int?>(null) }

    Column(modifier.fillMaxWidth()) {
        Readout(
            hour = scrubbed?.let(ordered::getOrNull),
            interval = ordered.interval(),
            zone = zone,
            ceiling = ceiling,
        )
        Spacer(Modifier.height(spacing.s))

        Box(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT)
                .pointerInput(ordered) {
                    // One gesture, not two competing ones, and it has to share
                    // the pointer with the page's vertical scroll.
                    //
                    // The value appears on touch-down, so a tap reads a point.
                    // After that the winner is decided by direction: cross the
                    // slop horizontally and this consumes the pointer and
                    // scrubs; move vertically instead and the scroll takes it
                    // and the readout clears. Claiming the pointer outright
                    // would make the chart a dead zone you cannot scroll past.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        scrubbed = indexAt(down.position.x, size.width, ordered.size)

                        val crossed = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }
                        if (crossed != null) {
                            horizontalDrag(crossed.id) { change ->
                                scrubbed = indexAt(change.position.x, size.width, ordered.size)
                                change.consume()
                            }
                        }
                        scrubbed = null
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
                drawNight(ordered, colors.night.copy(alpha = NIGHT_ALPHA))

                val path = curvePath(values, tangents, size, ceiling)
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
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = STROKE.toPx(),
                    ),
                )

                scrubbed?.let { index ->
                    drawCursor(
                        index = index,
                        count = values.size,
                        value = values[index],
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
        Spacer(Modifier.height(spacing.s))
        HourAxis(ordered, zone)
    }
}

/**
 * The value under the finger, or the scale when nothing is being pointed at.
 *
 * A fixed row rather than a floating tooltip: a bubble that appears over the
 * curve covers the thing being read, and its arrival and departure are two more
 * animations to get wrong.
 */
@Composable
private fun Readout(hour: HourlyWeather?, interval: Duration, zone: ZoneId, ceiling: Double) {
    val colors = WetterTheme.colors

    Row(
        Modifier.fillMaxWidth().height(READOUT_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hour == null) {
            Text(
                text = stringResource(R.string.curve_scale, formatMillimetres(ceiling)),
                style = WetterTheme.type.meta,
                color = colors.textTertiary,
            )
            return@Row
        }

        val from = formatTime(hour.timestamp, zone)
        val to = formatTime(hour.timestamp.plus(interval), zone)
        Text(
            // The interval, not the instant. Scrubbing to 08:23 on hourly data
            // and being told "08:23" would be the app inventing a precision the
            // forecast does not have.
            text = stringResource(R.string.curve_reading, from, to),
            style = WetterTheme.type.meta,
            color = colors.textTertiary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatMillimetresWithUnit(hour.precipitation ?: 0.0),
            style = WetterTheme.type.title,
            color = colors.precipitation,
        )
    }
}

@Composable
private fun HourAxis(hours: List<HourlyWeather>, zone: ZoneId) {
    val colors = WetterTheme.colors
    Row(Modifier.fillMaxWidth()) {
        hours.forEach { hour ->
            val localHour = hour.timestamp.atZone(zone).hour
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (localHour % AXIS_LABEL_EVERY == 0) {
                    Text(
                        text = "%02d".format(localHour),
                        style = WetterTheme.type.axis,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

/** The night wash, drawn as one rectangle per unbroken stretch of darkness. */
private fun DrawScope.drawNight(
    hours: List<HourlyWeather>,
    colour: androidx.compose.ui.graphics.Color,
) {
    val step = size.width / hours.size
    var start: Int? = null
    hours.forEachIndexed { index, hour ->
        if (!hour.isDay && start == null) start = index
        val ends = hour.isDay || index == hours.lastIndex
        if (ends) {
            start?.let { from ->
                val to = if (hour.isDay) index else index + 1
                drawRect(
                    color = colour,
                    topLeft = Offset(from * step, 0f),
                    size = Size((to - from) * step, size.height),
                )
            }
            start = null
        }
    }
}

private fun DrawScope.drawCursor(
    index: Int,
    count: Int,
    value: Float,
    ceiling: Double,
    lineColour: androidx.compose.ui.graphics.Color,
    dotColour: androidx.compose.ui.graphics.Color,
    ringColour: androidx.compose.ui.graphics.Color,
) {
    val x = sampleX(index, count, size.width)
    val y = size.height - (value / ceiling.toFloat()).coerceIn(0f, 1f) * size.height

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

/** Samples sit at the centre of the interval they describe, not at its edge. */
private fun sampleX(index: Int, count: Int, width: Float): Float {
    val step = width / count
    return step * index + step / 2f
}

private fun indexAt(x: Float, width: Int, count: Int): Int {
    if (width <= 0) return 0
    val step = width.toFloat() / count
    return (x / step).toInt().coerceIn(0, count - 1)
}

private fun curvePath(
    values: List<Float>,
    tangents: FloatArray,
    size: Size,
    ceilingMm: Double,
): Path {
    val ceiling = ceilingMm.toFloat()
    fun y(v: Float) = size.height - (v / ceiling).coerceIn(0f, 1f) * size.height

    val path = Path()
    path.moveTo(sampleX(0, values.size, size.width), y(values[0]))
    for (i in 0 until values.size - 1) {
        val (c0, c1) = MonotoneCurve.controlPoints(values, tangents, i)
        val x0 = sampleX(i, values.size, size.width)
        val x1 = sampleX(i + 1, values.size, size.width)
        val third = (x1 - x0) / 3f
        path.cubicTo(x0 + third, y(c0), x1 - third, y(c1), x1, y(values[i + 1]))
    }
    return path
}

/** How long each sample covers. Hourly today; narrower when a provider supplies it. */
private fun List<HourlyWeather>.interval(): Duration =
    if (size < 2) Duration.ofHours(1) else Duration.between(this[0].timestamp, this[1].timestamp)

/**
 * The ceilings the chart is allowed to use, in millimetres in the hour.
 *
 * These are the tops of the conventional intensity bands - light, moderate,
 * heavy, violent - so the scale only ever lands on a boundary that means
 * something meteorologically, rather than on whatever the day happened to peak
 * at.
 */
private val CEILINGS = listOf(0.5, 2.5, 8.0, 50.0)

/**
 * The scale for a day, and the reason it is not fixed.
 *
 * A fixed 8 mm ceiling is honest and, on the overwhelming majority of days,
 * useless: 0.2 mm of drizzle draws as a flat line against the baseline and the
 * chart says nothing at all. Scaling to the day's own peak is the usual fix and
 * is worse - it makes a morning of drizzle and an afternoon of downpour draw
 * identically, which is a lie told in the one place this app exists to be
 * truthful.
 *
 * So the ceiling steps between fixed, meaningful values, and the chart states
 * which one it is using in words above the trace. The scale can change; it can
 * never change silently.
 */
private fun ceilingFor(values: List<Float>): Double {
    val peak = values.maxOrNull()?.toDouble() ?: 0.0
    // A dry day gets the moderate band rather than the smallest one, so that a
    // flat line reads as "nothing much" instead of being magnified into drama.
    if (peak <= 0.0) return CEILINGS[1]
    return CEILINGS.firstOrNull { it >= peak } ?: CEILINGS.last()
}

private val TRACK_HEIGHT = 116.dp
private val READOUT_HEIGHT = 24.dp
private val STROKE = 2.dp
private val CURSOR_WIDTH = 1.5.dp
private val DOT_RADIUS = 4.dp
private val DOT_RING = 2.dp

private const val FILL_TOP_ALPHA = 0.34f
private const val FILL_BOTTOM_ALPHA = 0.02f
private const val NIGHT_ALPHA = 0.55f
private const val CURSOR_ALPHA = 0.45f
private const val AXIS_LABEL_EVERY = 3
