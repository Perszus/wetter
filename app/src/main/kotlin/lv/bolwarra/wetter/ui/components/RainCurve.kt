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
import androidx.compose.ui.graphics.lerp
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
import java.time.temporal.ChronoUnit
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.chart.MonotoneCurve
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.ui.format.formatMillimetres
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.Emphasis
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

    // Resolved here rather than in the draw scope, which has no way to reach a
    // string resource. The chart speaks in three levels; each entry is the
    // bottom of its band, so light's is the floor and needs no rule of its own.
    val guides = listOf(
        0f to stringResource(PrecipitationIntensity.LIGHT.labelRes()),
        heightFraction(PrecipitationIntensity.MODERATE_MM_PER_HOUR.toFloat()) to
            stringResource(PrecipitationIntensity.MODERATE.labelRes()),
        heightFraction(PrecipitationIntensity.HEAVY_MM_PER_HOUR.toFloat()) to
            stringResource(PrecipitationIntensity.HEAVY.labelRes()),
    )

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
                drawIntensityGuides(
                    guides = guides,
                    measurer = measurer,
                    style = axisStyle,
                    rule = colors.gridline,
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
                            colors.precipitation.copy(alpha = FILL_TOP_ALPHA),
                            colors.precipitation.copy(alpha = FILL_BOTTOM_ALPHA),
                        ),
                    ),
                )
                drawPath(
                    path = path,
                    brush = intensityBrush(colors.precipitationMuted, colors.precipitation),
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
                stringResource(curveLevel(point.millimetresPerHour.toDouble()).labelRes()),
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
/**
 * The line's colour, deepening as it climbs into heavier rain.
 *
 * A vertical gradient rather than a path cut into coloured segments, and that
 * falls out of what the chart already is: height *is* intensity here, so a
 * colour that varies with height varies with intensity by construction. Cutting
 * the path at each threshold would do the same job with hard joins, an
 * off-by-one at every boundary, and a seam wherever the line grazes a level
 * without settling in it.
 *
 * It stays inside the one hue precipitation already owns, running from the
 * palette's light-precipitation tone up to its full one. Reaching for a warning
 * colour at the top would put a second meaning on the chart and break the rule
 * that this app has exactly one loud colour - and the guide labels behind the
 * line already say which level it is in words. This is reinforcement, not the
 * message.
 *
 * Stops are given as one minus the height, because a vertical gradient measures
 * from the top of the canvas downwards while intensity is measured up from the
 * floor.
 *
 * ### Why the steps are hard
 *
 * The first version ramped smoothly from one tone to the other, on the reasoning
 * that a blend has no off-by-one at a boundary and no seam where the line grazes
 * a level without settling in it. Measured on a real curve it ran from
 * rgb(21,129,185) at the peak to rgb(110,178,214) at the floor - a real
 * difference, and an invisible one: with the change spread over the whole track
 * there is nothing to notice at the moment it matters, and the line reads as one
 * blue that happens to be paler at the bottom.
 *
 * So each band now holds one flat tone and changes at its edge, which is the
 * only place the change carries any information. A stroke takes its colour from
 * where its pixels are, so a line climbing through a boundary changes colour
 * exactly where it crosses - the part in moderate is the moderate tone, and the
 * part below it is not.
 *
 * The seam that worried the first version is real and turns out to be the point:
 * a line grazing a level should show it.
 */
private fun DrawScope.intensityBrush(muted: Color, full: Color): Brush {
    fun step(mix: Float) = lerp(muted, full, mix)

    // Gradient space runs down from the top, so a band's ceiling in intensity is
    // its lower bound here.
    val heavyEdge = 1f - heightFraction(PrecipitationIntensity.HEAVY_MM_PER_HOUR.toFloat())
    val moderateEdge = 1f - heightFraction(PrecipitationIntensity.MODERATE_MM_PER_HOUR.toFloat())

    return Brush.verticalGradient(
        0f to step(HEAVY_MIX),
        heavyEdge to step(HEAVY_MIX),
        // A hair further down, because stops have to keep increasing and two at
        // the same offset are not guaranteed to be drawn as an edge.
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
 * How far along the ramp each level sits.
 *
 * The bottom sits a touch above the palette's light-precipitation tone rather
 * than on it. That colour was chosen to fill an area and, drawn as a two-point
 * line, is thin enough to lose - but only just, so the correction needed is
 * small. An earlier attempt lifted it a third of the way toward the loud colour,
 * which was over-corrected: light rain came out a confident mid-blue and the
 * ramp had nowhere left to go by the time it reached moderate.
 */
private const val LIGHT_MIX = 0.06f
private const val MODERATE_MIX = 0.5f
private const val HEAVY_MIX = 1f

/**
 * Faint marks saying what the height means.
 *
 * The line's height carries the whole reading and, without this, says nothing on
 * its own - the only way to find out whether a rise was drizzle or a downpour
 * was to press and hold it, which nobody does at a glance. A rule and a word at
 * each threshold turn the track back into a scale.
 *
 * Drawn first, so everything here sits behind the curve, and drawn faintly. It
 * is a nudge, not a grid: the shape of the line is still the thing being read
 * and a chart ruled into slabs would fight it.
 *
 * The whole scale is drawn, every time. An earlier version showed only the
 * bands the weather reached, to keep a dry afternoon clean, and that was the
 * wrong trade: a chart whose gridlines move with the data is a different chart
 * each time you open it, and the reader has to re-learn where they are before
 * they can read anything. Fixed rules cost a little ink on a quiet day and buy
 * a scale that means the same thing every morning.
 */
private fun DrawScope.drawIntensityGuides(
    guides: List<Pair<Float, String>>,
    measurer: TextMeasurer,
    style: TextStyle,
    rule: Color,
) {
    guides.forEach { (fraction, label) ->
        val y = size.height * (1f - fraction)

        // The lowest band's boundary is the floor of the chart, which is already
        // drawn by the axis beneath it. A second rule on top of it would only
        // thicken the frame.
        if (fraction > 0f) {
            drawLine(
                color = rule,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = GUIDE_RULE_WIDTH.toPx(),
            )
        }
        val measured = measurer.measure(
            label,
            style,
        )
        // Held to the right edge. On the left they sat over the leftmost minutes
        // of the chart, which are *now* - the one moment on the track somebody
        // is certain to be looking at. The far right is six hours out, which is
        // the part it costs least to write under.
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                size.width - measured.size.width - GUIDE_LABEL_INSET.toPx(),
                y - measured.size.height - GUIDE_LABEL_GAP.toPx(),
            ),
        )
    }
}

/**
 * The hour marks under the chart.
 *
 * Positioned from the clock, not from the samples. The obvious version walks the
 * points and marks the ones whose minute is zero, and it only works when a
 * sample happens to land exactly on the hour - which the fused timeline never
 * reliably does, because it is anchored at whatever moment it was built and
 * re-anchored every minute. The result was an axis whose labels blinked in and
 * out depending on what minute it currently was, looking for all the world like
 * something slow to load.
 *
 * Walking the boundaries themselves and placing each one by where it falls in
 * the window is correct however the samples are spaced, and cannot drift out of
 * alignment with them either: the points are evenly spaced in time, so position
 * along the track means the same thing to both.
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
    // The first boundary at or after the window opens, found from the hour
    // containing it rather than by rounding the window's own ragged start.
    var mark = start.atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()
    while (mark.isBefore(start)) mark = mark.plus(step)

    while (!mark.isAfter(end)) {
        val x = Duration.between(start, mark).toMillis() / span * size.width
        val onTheHour = mark.atZone(zone).minute == 0

        drawLine(
            color = tick,
            start = Offset(x, 0f),
            end = Offset(x, (if (onTheHour) TICK_HOUR else TICK_HALF).toPx()),
            strokeWidth = if (onTheHour) 2f else 1f,
        )

        if (onTheHour) {
            val measured = measurer.measure(clockOf(mark, zone), style)
            // Nudged inside the bounds so the first and last labels are not
            // clipped by the edge of the tile.
            val left = (x - measured.size.width / 2f)
                .coerceIn(0f, size.width - measured.size.width)
            drawText(measured, topLeft = Offset(left, LABEL_TOP.toPx()))
        }
        mark = mark.plus(step)
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
 * The band geometry, which lives in `:domain` because the widget draws it too.
 *
 * These two are thin wrappers rather than direct calls at every use site, so the
 * chart reads the same as it did when it owned the arithmetic - and so there is
 * exactly one place to look when the app and the widget disagree about a peak.
 */
private fun curveLevel(millimetresPerHour: Double): PrecipitationIntensity =
    RainCurveBands.levelOf(millimetresPerHour)

/** Where a rate sits on the track, as a fraction of its height. */
private fun heightFraction(millimetresPerHour: Float): Float =
    RainCurveBands.heightFraction(millimetresPerHour)

/** How finely the curve is walked between the forecast's own samples. */
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

/**
 * Tall enough for five equal bands to each hold a word.
 *
 * At the old height a band was under twenty points and the lowest labels had
 * nowhere to sit, so the scale could only be shown in part. This is the height
 * at which the whole of it fits.
 */
private val TRACK_HEIGHT = 148.dp
private val READOUT_HEIGHT = 24.dp
private val AXIS_HEIGHT = 26.dp
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
