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
import lv.bolwarra.wetter.domain.chart.MonotoneCurve
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.ui.format.formatMillimetres
import lv.bolwarra.wetter.ui.format.labelRes
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
fun RainCurve(
    hours: List<HourlyWeather>,
    zone: ZoneId,
    from: Instant,
    span: Duration,
    modifier: Modifier = Modifier,
    /**
     * The fused radar-and-model timeline, when there is one. It supersedes the
     * hourly rows entirely: it already carries them, blended with whatever the
     * radar could add, at the ten-minute spacing this chart draws at.
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
                    // Here the mark means "modelled rather than observed", which
                    // is a more useful thing for it to say than "between two
                    // samples": within radar range the near hours are read off
                    // rain that exists, and past that they are a prediction.
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
                scrubbed?.let { index ->
                    drawCursor(
                        index = index,
                        points = points,
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

/**
 * The stretch actually being asked about.
 *
 * The spline is fitted over whole hours because that is what it has to fit; the
 * window shown is then cut out of it. Cutting first would fit the curve to a
 * truncated first hour and bend its left end.
 *
 * The cut lands on the existing ten-minute grid rather than exactly on [from],
 * which keeps the half-hour rules underneath on the half hour. Ten minutes of
 * slack at the left edge is the price, against the hour it replaces.
 */
private fun List<CurvePoint>.clipTo(from: Instant, to: Instant): List<CurvePoint> =
    filter { !it.at.isBefore(from) && !it.at.isAfter(to) }

/**
 * Rounds the corners off a series that is already at its final spacing.
 *
 * The hourly path gets its smoothness from [resample], which walks a spline
 * between the forecast's own samples. The fused timeline arrives already spaced
 * at ten minutes and so skipped that entirely - and was then drawn as straight
 * segments between those points, which put a visible corner at every one of
 * them. Rain does not start and stop on a corner, and a chart that says it does
 * reads as a series of cuts rather than as weather.
 *
 * Same monotone spline as the hourly path, so it still cannot overshoot below
 * zero approaching a shower or bulge above the peak inside one.
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
            // The time, and what it will actually feel like. The word is the
            // part anybody can act on.
            text = stringResource(
                R.string.curve_reading,
                clockOf(point.at, zone),
                stringResource(
                    PrecipitationIntensity.ofRate(point.millimetresPerHour.toDouble()).labelRes(),
                ),
            ),
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
    // A ring in the tile's own ground, so the dot reads as sitting on the curve
    // rather than as a bead lost in the fill.
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

/**
 * Points sit at their own instant, edge to edge.
 *
 * A rate has a value at a moment, so it belongs on the axis at that moment —
 * unlike an accumulation, which describes a span and would belong at the centre
 * of one.
 */
private fun DrawScope.xOf(index: Int, count: Int): Float =
    if (count <= 1) 0f else size.width * index / (count - 1)

private fun yOf(value: Float, height: Float): Float =
    height - heightFraction(value).coerceIn(0f, 1f) * height

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
 * Height is intensity, and the scale is fixed.
 *
 * This is the most important decision in the chart, and it took getting wrong
 * twice to find. Millimetres per hour is a unit almost nobody has a feel for -
 * told there is 3 mm of rain outside, most people, including people who work
 * near meteorology, cannot picture it. So nobody reads a value off this chart.
 * They read a shape: high means pouring, low means drizzle.
 *
 * For that reading to be true the scale cannot move. An adaptive ceiling - which
 * this had, and which seemed reasonable while the axis carried a number - draws
 * a drizzly morning at full height, which under the only mental model anybody
 * actually uses says "downpour". It was worse than the problem it solved.
 *
 * A fixed *linear* scale is no good either: against a ceiling high enough for a
 * real downpour, drizzle is two percent of the height and reads as nothing at
 * all, when it is precisely the difference between nothing and take a coat.
 *
 * So the axis is anchored to the conventional intensity bands, with each band
 * given a slice of the height wide enough to see. It is not linear in
 * millimetres, and it does not pretend to be - there is no number on it. What it
 * is linear in is how wet you get, which is what the chart is for.
 */
/**
 * Where each intensity sits on the track.
 *
 * Dry is flat on the floor and torrential fills it, so the reading stays the one
 * anybody actually uses: high means pouring, low means drizzle.
 *
 * The step from dry to a trace is deliberately large - nothing to nearly a
 * quarter of the height - because the question the chart is most often asked is
 * not how hard it will rain but *whether* it will. A trace drawn a tenth of the
 * way up was a bump you could scroll straight past on a track this short, which
 * meant the chart quietly failed at its main job. Everything above the trace
 * keeps its spacing, so the difference between light and heavy is as legible as
 * it was.
 */
private val BAND_HEIGHTS = listOf(
    0.0 to 0.00f,
    PrecipitationIntensity.TRACE_MM_PER_HOUR to 0.24f,
    PrecipitationIntensity.LIGHT_MM_PER_HOUR to 0.44f,
    PrecipitationIntensity.MODERATE_MM_PER_HOUR to 0.64f,
    PrecipitationIntensity.HEAVY_MM_PER_HOUR to 0.83f,
    PrecipitationIntensity.VIOLENT_MM_PER_HOUR to 1.00f,
)

/** Where a rate sits on the track, as a fraction of its height. */
private fun heightFraction(millimetresPerHour: Float): Float {
    val mm = millimetresPerHour.toDouble()
    if (mm <= 0.0) return 0f
    for (i in 0 until BAND_HEIGHTS.size - 1) {
        val (lowMm, lowY) = BAND_HEIGHTS[i]
        val (highMm, highY) = BAND_HEIGHTS[i + 1]
        if (mm <= highMm) {
            val t = ((mm - lowMm) / (highMm - lowMm)).toFloat()
            // Eased across each band rather than run straight through it. The
            // anchors are what carry the meaning - trace here, heavy there - but
            // joining them with straight segments puts a slope change at every
            // boundary, so a perfectly smooth rate still drew a visibly kinked
            // line wherever it crossed from one intensity into the next.
            return lowY + (highY - lowY) * smoothStep(t)
        }
    }
    return 1f
}

/**
 * Smoothstep: flat at both ends, steepest in the middle.
 *
 * Chosen over a spline through the anchors because it is local. A spline would
 * let a change to one band's height shift the curve inside its neighbours,
 * which would make the bands stop meaning exactly what they say.
 */
private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** How finely the curve is walked between the forecast's own samples. */
/** Above this share, the point is carried by radar rather than by the model. */
private const val RADAR_BACKED = 0.5

/** Sub-steps drawn between two fused points, purely to round the corners. */
private const val SMOOTH_STEPS = 5

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
