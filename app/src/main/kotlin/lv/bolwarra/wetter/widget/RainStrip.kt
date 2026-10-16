package lv.bolwarra.wetter.widget

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.ui.theme.WetterColors

/**
 * The rain chart the rain widget is made of, and the overview's strip: the band
 * lines, the curve coloured by the band it is in, and the hour marks with their
 * labels, drawn into a box on a widget's picture.
 */
internal object RainStrip {

    /**
     * The chart and its clock, in a box on a canvas that is not necessarily
     * this widget's - the overview carries the same strip, drawn by the same
     * code, so the two can never show the same rain two ways.
     *
     * [box] is in pixels; its height in dp decides the insets, as it always has.
     */
    fun drawStrip(
        canvas: Canvas,
        box: RectF,
        scale: Float,
        colors: WetterColors,
        rates: List<Double>,
        hourOffset: Float,
        hourLabels: List<String>,
    ) {
        val heightDp = box.height() / scale
        // Proportional, not fixed. At one cell tall the whole widget is about
        // 56 dp, and a 10 dp head plus a 24 dp foot would leave the chart less
        // room than its own margins.
        val chart = RectF(
            box.left + CHART_INSET_DP * scale,
            box.top + topInset(heightDp) * scale,
            box.right - CHART_INSET_DP * scale,
            box.bottom - labelInset(heightDp) * scale,
        )
        if (chart.width() > 0f && chart.height() > 0f) {
            drawBands(canvas, chart, scale, colors)
            drawCurve(canvas, chart, scale, colors, rates)
            drawHours(canvas, chart, scale, colors, hourOffset, hourLabels)
        }
    }

    /**
     * Where light becomes moderate, and moderate becomes heavy.
     *
     * Two hairlines and no words. In the app the level is named beside the curve
     * and the colour merely reinforces it; here there is no room for a word that
     * would survive being read at arm's length, so the roles swap and the colour
     * becomes the message. The lines are what make that legible - they mark the
     * exact heights the curve changes colour at, so a peak that crosses one is
     * visibly a peak that crossed something.
     */
    private fun drawBands(canvas: Canvas, chart: RectF, scale: Float, colors: WetterColors) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.gridline.toArgb()
            strokeWidth = HAIRLINE_DP * scale
        }
        listOf(RainCurveBands.moderateEdge, RainCurveBands.heavyEdge).forEach { fraction ->
            val y = chart.bottom - chart.height() * fraction
            canvas.drawLine(chart.left, y, chart.right, y, paint)
        }
    }

    /**
     * The clock, along the foot of the chart.
     *
     * The hour labels used to sit at fixed quarters of the width, which put them
     * nowhere in particular: the window starts at *now*, so a quarter of the way
     * along is 23:47, not midnight. They read as shifted because they were.
     *
     * These are the real hour boundaries, marked on the axis line and not as
     * rules up the chart. Rules were the first attempt and they turned the thing
     * into a grid: four verticals crossing three horizontals, competing with the
     * one line that is supposed to be read. The chart has exactly one subject
     * and the clock is not it.
     *
     * So the marks sit on the foot, where an axis belongs, and the label for
     * each hour starts just to the right of its tick - the tick is the moment
     * and the text belongs to it, rather than floating between two of them.
     * Because the window is exactly four hours the boundaries stay a quarter
     * apart, and only the offset to the first one changes.
     *
     * The hour's tick runs all the way down to the text baseline, so it stands
     * immediately to the left of its own label and the two read as one mark. At
     * six and four device-independent pixels the two kinds of tick were close
     * enough in length to be told apart only by looking for it, which is not
     * something anybody does to a widget; against a tick four times longer,
     * with a time written beside it, there is nothing to tell apart.
     *
     * Half hours keep the short tick and no text. They are not meant to be read
     * individually; they are there so the eye can halve the gap between two
     * hours without measuring it, which is most of what anybody does with "when
     * does this start".
     */
    private fun drawHours(
        canvas: Canvas,
        chart: RectF,
        scale: Float,
        colors: WetterColors,
        hourOffset: Float,
        hourLabels: List<String>,
    ) {
        // The marks are read, so they take a text tone rather than the gridline
        // one. Drawn in the tone the band rules use they were technically
        // present and practically invisible - a scale nobody can see is a scale
        // that is not there.
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textTertiary.toArgb()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.hairline.toArgb()
            strokeWidth = HAIRLINE_DP * scale
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textTertiary.toArgb()
            textSize = LABEL_DP * scale
        }
        val baseline = chart.bottom + LABEL_BASELINE_DP * scale

        // The line the marks hang from. Drawn after the curve so the fill,
        // whose own bottom edge sits here, cannot soften it.
        canvas.drawLine(chart.left, chart.bottom, chart.right, chart.bottom, paint)

        // Counted in half-hour steps from the first hour boundary, so whether a
        // mark is on the hour is the parity of the step rather than a modulus of
        // a float that has been added to itself a dozen times.
        var step = 0
        while (hourOffset + step * HALF_HOUR > 0f) step--
        while (true) {
            val fraction = hourOffset + step * HALF_HOUR
            if (fraction >= 1f) break
            if (fraction > 0f) {
                val x = chart.left + chart.width() * fraction
                val onTheHour = step % 2 == 0
                tick.strokeWidth = (if (onTheHour) HOUR_STROKE_DP else HALF_STROKE_DP) * scale
                canvas.drawLine(
                    x,
                    chart.bottom,
                    x,
                    chart.bottom + (if (onTheHour) HOUR_TICK_DP else HALF_TICK_DP) * scale,
                    tick,
                )

                // The label belongs to the rule beside it, so it starts just to
                // its right rather than centred on it. Anything that would run
                // off the end is dropped: a clipped hour is worse than a missing
                // one, because a clipped one still looks like a reading.
                if (onTheHour) {
                    val hour = hourLabels.getOrNull(step / 2)
                    if (hour != null) {
                        val left = x + LABEL_GAP_DP * scale
                        if (left + text.measureText(hour) <= chart.right) {
                            canvas.drawText(hour, left, baseline, text)
                        }
                    }
                }
            }
            step++
        }
    }

    /**
     * The curve, coloured by the band each part of it is in.
     *
     * A vertical gradient with hard steps at the band edges rather than a path
     * cut into coloured segments. Height *is* intensity on this axis, so a
     * colour that varies with height varies with intensity by construction - and
     * a stroke takes its colour from where its pixels are, so a line climbing
     * through a boundary changes colour exactly where it crosses. The part in
     * moderate is the moderate tone and the part below it is not, with no
     * off-by-one at the boundary and no seam where the line grazes a level
     * without settling in it.
     *
     * This is the same construction as the app's chart, for the same reasons,
     * written twice because Compose and android.graphics do not share a brush.
     * What they do share is [RainCurveBands], which is the part that must not
     * differ.
     */
    private fun drawCurve(
        canvas: Canvas,
        chart: RectF,
        scale: Float,
        colors: WetterColors,
        rates: List<Double>,
    ) {
        if (rates.isEmpty()) return

        val points = rates.mapIndexed { index, rate ->
            val x = chart.left + chart.width() * index / max(1, rates.size - 1)
            val y = chart.bottom -
                chart.height() * lifted(RainCurveBands.heightFraction(rate.toFloat()))
            x to y
        }

        val line = smoothPath(points)
        val muted = colors.textTertiary.toArgb()
        val full = colors.textPrimary.toArgb()

        val fill = Path(line).apply {
            lineTo(chart.right, chart.bottom)
            lineTo(chart.left, chart.bottom)
            close()
        }
        canvas.drawPath(
            fill,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    chart.top,
                    0f,
                    chart.bottom,
                    WidgetPlate.withAlpha(full, FILL_TOP_ALPHA),
                    WidgetPlate.withAlpha(full, FILL_BOTTOM_ALPHA),
                    Shader.TileMode.CLAMP,
                )
            },
        )

        // Gradient space runs down from the top, so a band's ceiling in
        // intensity is its lower bound here.
        val heavy = 1f - RainCurveBands.heavyEdge
        val moderate = 1f - RainCurveBands.moderateEdge
        canvas.drawPath(
            line,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = STROKE_DP * scale
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                shader = LinearGradient(
                    0f,
                    chart.top,
                    0f,
                    chart.bottom,
                    intArrayOf(
                        WidgetPlate.mix(muted, full, HEAVY_MIX),
                        WidgetPlate.mix(muted, full, HEAVY_MIX),
                        WidgetPlate.mix(muted, full, MODERATE_MIX),
                        WidgetPlate.mix(muted, full, MODERATE_MIX),
                        WidgetPlate.mix(muted, full, LIGHT_MIX),
                        WidgetPlate.mix(muted, full, LIGHT_MIX),
                    ),
                    // A hair apart, because stops have to keep increasing and
                    // two at the same offset are not guaranteed to draw as an
                    // edge.
                    floatArrayOf(0f, heavy, heavy + SEAM, moderate, moderate + SEAM, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    /** Breathing room over the tallest the curve can go. */
    private fun topInset(heightDp: Float): Float =
        (heightDp * TOP_SHARE).coerceIn(MIN_TOP_DP, MAX_TOP_DP)

    /**
     * The strip the hour labels sit in, which is text and therefore does not
     * shrink in proportion to anything. Hence a floor: below this the labels
     * would start clipping whatever the chart wanted.
     */
    fun labelInset(heightDp: Float): Float =
        (heightDp * LABEL_SHARE).coerceIn(MIN_LABEL_DP, MAX_LABEL_DP)

    /**
     * Low rain, lifted so that it reads at this size.
     *
     * The shared axis puts the least rain there is at eight percent of the
     * track. That is enough in the app, where the band is captioned "Light"
     * right beside the curve; here there are no captions and a third of the
     * height, so a genuine forecast of rain until four in the morning drew as a
     * line along the floor and read as an empty widget.
     *
     * ### It stays inside the light band
     *
     * The lift is a square root applied only below the light band's ceiling, and
     * that ceiling is a fixed point of it - so the boundaries stay exactly on
     * the thirds and **the three levels remain the same height**. Only where a
     * rate lands *within* light changes.
     *
     * The first attempt lifted everything, band lines included, which pushed
     * light to 40.7% of the height against 29.7% for the other two. That is the
     * same unequal-levels fault it was meant to avoid, reintroduced one surface
     * over - the bands are a scale of the three words anybody acts on, and a
     * scale whose steps are different sizes asks the reader to remember which
     * step is which.
     *
     * The attempt before that raised the shared floor in [RainCurveBands], which
     * fixed the widget and quietly redrew the main chart at the same time. A
     * legibility problem on one surface gets fixed on that surface.
     *
     * Dry is exempt - zero maps to zero - because the whole point is to open a
     * gap between no rain and some rain.
     *
     * The arithmetic itself now lives beside the axis it bends, because the
     * week's hour strip is a short track with the same problem, and two
     * hand-copied square roots is how two surfaces start disagreeing about a
     * drizzle. Which surfaces apply it is still each surface's own decision.
     */
    private fun lifted(fraction: Float): Float = RainCurveBands.spreadWithinLight(fraction)

    /**
     * A path through the samples, rounded at the joins.
     *
     * Quadratic segments anchored at the midpoints between samples: each pair
     * contributes one curve whose control point is the sample itself, so the
     * line passes smoothly near every reading without the overshoot a spline
     * would add. Overshoot matters here - a spline between a dry step and a wet
     * one dips below zero on the way, drawing rain that stops harder than
     * stopped.
     */
    private fun smoothPath(points: List<Pair<Float, Float>>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].first, points[0].second)
        if (points.size == 1) return path

        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            val midX = (x0 + x1) / 2f
            val midY = (y0 + y1) / 2f
            if (i == 0) path.lineTo(midX, midY) else path.quadTo(x0, y0, midX, midY)
        }
        val last = points.last()
        path.lineTo(last.first, last.second)
        return path
    }

    private const val CHART_INSET_DP = 10f

    /**
     * Nothing above the chart.
     *
     * Any inset here is empty space sitting directly on top of the heavy lane
     * with no rule between them, so it reads as part of that lane and makes
     * heavy look taller than the other two when all three are equal thirds. The
     * ceiling of the scale is the top of the plate.
     */
    private const val TOP_SHARE = 0f
    private const val MIN_TOP_DP = 0f
    private const val MAX_TOP_DP = 0f

    /** Room under the chart for the hour labels, which are real text on top. */
    private const val LABEL_SHARE = 0.30f
    private const val MIN_LABEL_DP = 17f
    private const val MAX_LABEL_DP = 24f

    private const val STROKE_DP = 2.5f

    /** One hour, as a fraction of the four-hour window, and half of one. */
    private const val HOUR = 0.25f
    private const val HALF_HOUR = 0.125f

    /**
     * The hour reaches the baseline of the text it belongs to; the half hour is
     * a short mark on the line. Tied to [LABEL_BASELINE_DP] rather than given
     * its own number, so the tick cannot drift away from the type beside it.
     */
    private val HOUR_TICK_DP get() = LABEL_BASELINE_DP
    private const val HALF_TICK_DP = 4f
    private const val HOUR_STROKE_DP = 1.8f
    private const val HALF_STROKE_DP = 1.3f

    private const val LABEL_DP = 10f
    private const val LABEL_GAP_DP = 3f
    private const val LABEL_BASELINE_DP = 15f
    private const val HAIRLINE_DP = 1f

    /**
     * The fill does not fade to nothing.
     *
     * Light rain sits at about a twelfth of the track, so a fill that vanishes
     * on the way down leaves a hairline hugging the floor - which is what the
     * first build showed for a genuine "rain until four", and it read as an
     * empty widget. Keeping a floor under the fill means any rain at all is a
     * band of colour rather than a line.
     */
    private const val FILL_TOP_ALPHA = 0.5f
    private const val FILL_BOTTOM_ALPHA = 0.16f

    private const val SEAM = 0.001f
    private const val LIGHT_MIX = 0.06f
    private const val MODERATE_MIX = 0.5f
    private const val HEAVY_MIX = 1f
}
