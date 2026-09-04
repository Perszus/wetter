package lv.bolwarra.wetter.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.ui.theme.Emphasis
import lv.bolwarra.wetter.ui.theme.WetterColors

/**
 * The widget, drawn as one picture.
 *
 * A home-screen widget is a [android.widget.RemoteViews] tree - a description of
 * views, serialized over Binder and inflated inside the launcher's process. We
 * have no code running there, so there is no canvas of ours to draw on and no
 * frame loop to animate. The only way to put an arbitrary shape on a widget is
 * to render it here, in our process, and hand the launcher a finished bitmap.
 *
 * So this draws everything except the text: the plate, the wind, the bands and
 * the curve. Drawing the plate here rather than as an XML background is what
 * keeps the widget the same colour as the app - the palette is solved in CIE
 * LCh at runtime (ui/theme/Color.kt), and a hex value copied into a drawable
 * would be a second source of truth that silently stops matching.
 *
 * ### The size of it
 *
 * Binder gives a process about one megabyte of transaction buffer, and the
 * bitmap has to fit inside that along with everything else in the update. A
 * 240x130 dp widget at a modern phone's 2.75x density is 660x357 px, which as
 * ARGB_8888 is 942 KB - the entire budget, for one frame.
 *
 * Hence [MAX_PIXELS]. The bitmap is rendered at whatever scale keeps it under
 * that and the launcher scales it up, which costs a little sharpness on the
 * thinnest strokes and nothing anywhere else. It is also why the text is not in
 * here: upscaled type looks broken in a way an upscaled curve does not.
 */
internal object RainStrip {

    /**
     * @param rates millimetres per hour, evenly spaced across the window, the
     *   first sample being now.
     * @param windSpeed metres per second, or null when nothing is known - in
     *   which case no ring is drawn at all rather than a calm one.
     * @param windFrom degrees clockwise from north, the direction the wind
     *   blows from.
     */
    fun render(
        widthDp: Float,
        heightDp: Float,
        density: Float,
        cornerRadiusDp: Float,
        colors: WetterColors,
        rates: List<Double>,
        windSpeed: Double?,
        windFrom: Int?,
    ): Bitmap {
        val scale = renderScale(widthDp, heightDp, density)
        val width = max(1, (widthDp * scale).toInt())
        val height = max(1, (heightDp * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val plate = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = cornerRadiusDp * scale

        drawPlate(canvas, plate, radius, colors)
        drawWind(canvas, plate, radius, scale, colors, windSpeed, windFrom)

        val chart = RectF(
            CHART_INSET_DP * scale,
            CHART_TOP_DP * scale,
            width - CHART_INSET_DP * scale,
            height - CHART_BOTTOM_DP * scale,
        )
        if (chart.width() > 0f && chart.height() > 0f) {
            drawBands(canvas, chart, scale, colors)
            drawCurve(canvas, chart, scale, colors, rates)
        }
        return bitmap
    }

    /**
     * The ground the widget sits on.
     *
     * Opaque on purpose. A translucent widget takes its legibility from whatever
     * wallpaper happens to be behind it, and the whole palette is built by
     * solving contrast against a known ground - against an unknown one none of
     * those ratios mean anything.
     */
    private fun drawPlate(canvas: Canvas, plate: RectF, radius: Float, colors: WetterColors) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.surface.toArgb()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(plate, radius, radius, paint)
    }

    /**
     * Wind, as a glow on the edge it is coming from.
     *
     * The idea this replaces was a gradient travelling around the border, which
     * a widget cannot do: there is no animation primitive for arbitrary
     * graphics, and the nearest thing - a ViewFlipper cycling pre-rendered
     * frames - costs a full bitmap per frame out of the same one-megabyte
     * budget, advances on a coarse timer, and is paused by launchers at will. It
     * would read as a stutter rather than as a breeze.
     *
     * Standing still turns out to say more anyway. Motion could only have
     * carried speed; a fixed glow carries direction as well, by sitting on the
     * side the wind blows from - which is the half somebody actually acts on
     * when deciding which way to walk home.
     *
     * It is deliberately soft and wide rather than a hairline. A 2 dp stroke
     * upscaled from a half-resolution bitmap looks like a rendering fault; a
     * diffuse band looks like light, and reads as atmosphere rather than as a
     * drawn line - which is also the honest register for a quantity nobody wants
     * to the nearest metre per second.
     */
    private fun drawWind(
        canvas: Canvas,
        plate: RectF,
        radius: Float,
        scale: Float,
        colors: WetterColors,
        windSpeed: Double?,
        windFrom: Int?,
    ) {
        // No reading is not a calm reading. With nothing known the edge stays
        // bare, rather than showing a still day that was never measured.
        if (windSpeed == null || windFrom == null) return

        val strength = (windSpeed / GALE_MS).coerceIn(0.0, 1.0).toFloat()
        if (strength <= 0f) return

        val width = RING_WIDTH_DP * scale
        val band = RectF(plate).apply { inset(width / 2f, width / 2f) }
        if (band.width() <= 0f || band.height() <= 0f) return

        val alpha = (RING_ALPHA * strength * 255).toInt().coerceIn(0, 255)
        val head = (colors.textPrimary.toArgb() and 0x00FFFFFF) or (alpha shl 24)
        val tail = head and 0x00FFFFFF

        // Sweep angles start at three o'clock and run clockwise; compass
        // bearings start at twelve and also run clockwise, so the two differ by
        // a quarter turn. Rotating by that puts the head of the gradient exactly
        // on the bearing.
        val shader = SweepGradient(
            band.centerX(),
            band.centerY(),
            intArrayOf(head, tail, tail, head),
            floatArrayOf(0f, TAIL_START, TAIL_END, 1f),
        )
        shader.setLocalMatrix(
            Matrix().apply {
                postRotate(windFrom - QUARTER_TURN, band.centerX(), band.centerY())
            },
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.shader = shader
            maskFilter = BlurMaskFilter(width * BLUR_SHARE, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(band, radius, radius, paint)
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
            color = colors.precipitationTrack.toArgb()
            strokeWidth = HAIRLINE_DP * scale
        }
        listOf(RainCurveBands.moderateEdge, RainCurveBands.heavyEdge).forEach { fraction ->
            val y = chart.bottom - chart.height() * fraction
            canvas.drawLine(chart.left, y, chart.right, y, paint)
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
            val y = chart.bottom - chart.height() * RainCurveBands.heightFraction(rate.toFloat())
            x to y
        }

        val line = smoothPath(points)
        val muted = colors.precipitationMuted.toArgb()
        val full = colors.precipitation.toArgb()

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
                    withAlpha(full, Emphasis.MUTED),
                    withAlpha(full, 0f),
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
                        mix(muted, full, HEAVY_MIX),
                        mix(muted, full, HEAVY_MIX),
                        mix(muted, full, MODERATE_MIX),
                        mix(muted, full, MODERATE_MIX),
                        mix(muted, full, LIGHT_MIX),
                        mix(muted, full, LIGHT_MIX),
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

    /**
     * How many device pixels to draw one dp at, given [MAX_PIXELS].
     *
     * Never above the real density - upscaling past it would spend budget on
     * detail the screen cannot show.
     */
    private fun renderScale(widthDp: Float, heightDp: Float, density: Float): Float {
        val area = max(1f, widthDp * heightDp)
        return min(density, sqrt(MAX_PIXELS / area))
    }

    private fun mix(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * amount).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    /**
     * About a quarter of a megabyte as ARGB_8888, which leaves the rest of the
     * Binder transaction for the views and the text.
     */
    private const val MAX_PIXELS = 64_000f

    private const val CHART_INSET_DP = 12f
    private const val CHART_TOP_DP = 14f

    /** Room under the chart for the hour labels, which are real text on top. */
    private const val CHART_BOTTOM_DP = 22f

    private const val STROKE_DP = 2f
    private const val HAIRLINE_DP = 1f

    private const val RING_WIDTH_DP = 6f
    private const val RING_ALPHA = 0.55f
    private const val BLUR_SHARE = 0.7f

    /** Where the glow has fallen away entirely, and where it starts returning. */
    private const val TAIL_START = 0.3f
    private const val TAIL_END = 0.7f

    private const val QUARTER_TURN = 90f

    /** Gale force, the same number the hazard rules use. Full glow sits here. */
    private const val GALE_MS = 17.2

    private const val SEAM = 0.001f
    private const val LIGHT_MIX = 0.06f
    private const val MODERATE_MIX = 0.5f
    private const val HEAVY_MIX = 1f
}
