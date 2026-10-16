// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
     * The chart and its clock in a box on any canvas, so the rain widget and the
     * overview draw the same rain the same way.
     *
     * [box] is in pixels; its height in dp decides the insets.
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
        // Proportional insets: at one cell tall fixed ones would leave the chart less
        // room than its margins.
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
     * Where light becomes moderate, and moderate becomes heavy: two hairlines and no
     * words. There is no room for a band name here, so the colour carries the level
     * and the lines mark where it changes.
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
     * The clock, along the foot of the chart. Ticks sit on the real hour boundaries
     * (the window starts at now), each hour's label just to the right of its tick.
     * The hour tick runs down to the label's baseline so the two read as one mark;
     * half hours get a short tick and no text.
     */
    private fun drawHours(
        canvas: Canvas,
        chart: RectF,
        scale: Float,
        colors: WetterColors,
        hourOffset: Float,
        hourLabels: List<String>,
    ) {
        // A text tone, not the gridline one: the marks are read.
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

        // Drawn after the curve so the fill cannot soften it.
        canvas.drawLine(chart.left, chart.bottom, chart.right, chart.bottom, paint)

        // Counted in half-hour steps, so whether a mark is on the hour is the step's
        // parity rather than a modulus of an accumulated float.
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

                // A label that would run off the end is dropped: a clipped hour still looks like
                // a reading.
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
     * A vertical gradient with hard steps at the band edges: height is intensity on
     * this axis, so a stroke changes colour where it crosses a level. The
     * same construction as the app's chart, sharing [RainCurveBands] for the edges.
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

        // Gradient space runs down from the top, so a band's ceiling in intensity is its
        // lower bound here.
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
                    // A hair apart: stops must keep increasing, and two at the same offset are not
                    // guaranteed to draw as an edge.
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
     * The strip the hour labels sit in. Text does not shrink with the widget, hence
     * a floor.
     */
    fun labelInset(heightDp: Float): Float =
        (heightDp * LABEL_SHARE).coerceIn(MIN_LABEL_DP, MAX_LABEL_DP)

    /**
     * Low rain, lifted so that it reads at this size.
     *
     * Without captions and at a third of the height, light rain at the shared axis
     * reads as an empty widget. The lift applies only within the light band and
     * leaves its ceiling fixed, so the three levels stay equal thirds. Dry stays at
     * zero, keeping the gap between no rain and some.
     */
    private fun lifted(fraction: Float): Float = RainCurveBands.spreadWithinLight(fraction)

    /**
     * A path through the samples, rounded at the joins: quadratic segments anchored at
     * the midpoints, each controlled by its sample. Unlike a spline it cannot
     * overshoot below zero between a dry step and a wet one.
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
     * Nothing above the chart: an inset there would read as part of the heavy lane
     * and make it look taller than the other two.
     */
    private const val TOP_SHARE = 0f
    private const val MIN_TOP_DP = 0f
    private const val MAX_TOP_DP = 0f

    /** Room under the chart for the hour labels. */
    private const val LABEL_SHARE = 0.30f
    private const val MIN_LABEL_DP = 17f
    private const val MAX_LABEL_DP = 24f

    private const val STROKE_DP = 2.5f

    /** One hour, as a fraction of the four-hour window, and half of one. */
    private const val HOUR = 0.25f
    private const val HALF_HOUR = 0.125f

    /**
     * The hour tick reaches the baseline of its label; the half hour is a short mark
     * on the line.
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
     * The fill does not fade to nothing, so any rain at all is a band of colour
     * rather than a hairline along the floor.
     */
    private const val FILL_TOP_ALPHA = 0.5f
    private const val FILL_BOTTOM_ALPHA = 0.16f

    private const val SEAM = 0.001f
    private const val LIGHT_MIX = 0.06f
    private const val MODERATE_MIX = 0.5f
    private const val HEAVY_MIX = 1f
}
