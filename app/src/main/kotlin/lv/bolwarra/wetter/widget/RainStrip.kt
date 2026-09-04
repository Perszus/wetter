package lv.bolwarra.wetter.widget

import android.graphics.Bitmap
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
        hourOffset: Float,
        hourLabels: List<String>,
    ): Bitmap {
        val scale = renderScale(widthDp, heightDp, density)
        val width = max(1, (widthDp * scale).toInt())
        val height = max(1, (heightDp * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val plate = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = cornerRadiusDp * scale

        drawPlate(canvas, plate, radius, colors)
        drawRim(canvas, plate, radius, scale, colors, windSpeed, windFrom)

        // Proportional, not fixed. At one cell tall the whole widget is about
        // 56 dp, and a 10 dp head plus a 24 dp foot would leave the chart less
        // room than its own margins.
        val chart = RectF(
            CHART_INSET_DP * scale,
            topInset(heightDp) * scale,
            width - CHART_INSET_DP * scale,
            height - labelInset(heightDp) * scale,
        )
        if (chart.width() > 0f && chart.height() > 0f) {
            drawBands(canvas, chart, scale, colors)
            drawHours(canvas, chart, scale, colors, hourOffset, hourLabels)
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
        // The raised tone rather than the page tone. On a screen the plate sits
        // on a known ground; on a home screen it sits on a wallpaper, and the
        // first build used `surface` - which on the dark palette is very nearly
        // black, against a wallpaper that was also very nearly black. It read as
        // a hole rather than as a card.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.surfaceRaised.toArgb()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(plate, radius, radius, paint)

        // A sheen down the top third. Glass is not a flat fill: it catches the
        // light it is under, brightest where it faces the source and falling
        // away below. Two percent of a highlight tone is enough - any more and
        // it stops being light on a surface and becomes a painted stripe.
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                plate.top,
                0f,
                plate.top + plate.height() * SHEEN_DEPTH,
                withAlpha(colors.surfaceHighlight.toArgb(), SHEEN_ALPHA),
                withAlpha(colors.surfaceHighlight.toArgb(), 0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(plate, radius, radius, sheen)
    }

    /**
     * The edge, as a rim of light - and the wind, as where it is brightest.
     *
     * The first version was a soft blurred band, which read as a smudge round
     * the outside rather than as an edge: it made the widget look grubby, like a
     * drop shadow drawn on the wrong side. Glass does not have a halo. It has a
     * thin, bright, hard rim where the light catches the bevel, and that rim
     * goes all the way round whether or not anything is happening.
     *
     * So the rim is the material and the wind is a modulation of it. It is
     * always drawn, at a low base brightness, which is what makes the widget
     * read as an object sitting on the wallpaper rather than a hole cut in it.
     * Where the wind blows from, the rim brightens.
     *
     * ### Why it does not move
     *
     * The idea this serves was a gradient travelling around the border, which a
     * widget cannot do: [android.widget.RemoteViews] is inflated in the
     * launcher's process, so there is no frame loop of ours and no animation
     * primitive for arbitrary graphics. The nearest thing - a ViewFlipper
     * cycling pre-rendered frames - costs a full bitmap per frame out of a
     * one-megabyte budget, advances on a coarse timer, and is paused by
     * launchers at will. It would read as a stutter, not as a breeze.
     *
     * Standing still says more anyway. Motion could only have carried speed; a
     * fixed bright side carries direction too, which is the half somebody acts
     * on when deciding which way to walk home.
     */
    private fun drawRim(
        canvas: Canvas,
        plate: RectF,
        radius: Float,
        scale: Float,
        colors: WetterColors,
        windSpeed: Double?,
        windFrom: Int?,
    ) {
        val width = RIM_DP * scale
        val band = RectF(plate).apply { inset(width / 2f, width / 2f) }
        if (band.width() <= 0f || band.height() <= 0f) return

        val rim = colors.surfaceHighlight.toArgb() and 0x00FFFFFF
        val base = withAlpha(rim, RIM_BASE_ALPHA)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            color = base
        }

        // No reading is not a calm reading, so with nothing measured the rim
        // stays even and says nothing about the wind either way.
        if (windSpeed != null && windFrom != null) {
            // Square-rooted, so a real breeze is visible rather than sitting at
            // a fifteenth of the range. Gale force is the top of the scale and
            // almost nothing reaches it, so a linear ramp spends nearly all its
            // range on wind nobody will ever stand in.
            val strength = sqrt((windSpeed / GALE_MS).coerceIn(0.0, 1.0)).toFloat()
            val lit = withAlpha(rim, RIM_BASE_ALPHA + (RIM_PEAK_ALPHA - RIM_BASE_ALPHA) * strength)

            // Sweep angles start at three o'clock and run clockwise; compass
            // bearings start at twelve and also run clockwise, so the two differ
            // by a quarter turn. Rotating by that puts the bright part of the
            // rim exactly on the bearing.
            paint.shader = SweepGradient(
                band.centerX(),
                band.centerY(),
                intArrayOf(lit, base, base, lit),
                floatArrayOf(0f, TAIL_START, TAIL_END, 1f),
            ).apply {
                setLocalMatrix(
                    Matrix().apply {
                        postRotate(windFrom - QUARTER_TURN, band.centerX(), band.centerY())
                    },
                )
            }
        }
        canvas.drawRoundRect(band, radius - width / 2f, radius - width / 2f, paint)
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
     * The clock, as rules down the chart.
     *
     * The hour labels used to sit at fixed quarters of the width, which put them
     * nowhere in particular: the window starts at *now*, so a quarter of the way
     * along is 23:47, not midnight. They read as shifted because they were.
     *
     * These are the real hour boundaries, and the label for each one starts just
     * to the right of its rule - so the rule is the moment and the text belongs
     * to it, rather than floating between two of them. Because the window is
     * exactly four hours, the boundaries stay evenly spaced a quarter apart and
     * only the offset to the first one changes, which is what lets the labels
     * stay four equal cells with one shared indent.
     *
     * Half hours get a short tick off the floor. They carry no text and are not
     * meant to be read individually; they are there so the eye can halve the gap
     * between two hours without measuring it, which is most of what anybody does
     * with "when does this start".
     */
    private fun drawHours(
        canvas: Canvas,
        chart: RectF,
        scale: Float,
        colors: WetterColors,
        hourOffset: Float,
        hourLabels: List<String>,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.gridline.toArgb()
            strokeWidth = HAIRLINE_DP * scale
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.textTertiary.toArgb()
            textSize = LABEL_DP * scale
        }
        val tickHeight = HALF_TICK_DP * scale
        val baseline = chart.bottom + LABEL_BASELINE_DP * scale

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
                // The hour is a rule up the whole chart; the half hour hangs
                // below the floor, where the fill cannot paint over it.
                canvas.drawLine(
                    x,
                    if (onTheHour) chart.top else chart.bottom,
                    x,
                    if (onTheHour) chart.bottom else chart.bottom + tickHeight,
                    paint,
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
                    withAlpha(full, FILL_TOP_ALPHA),
                    withAlpha(full, FILL_BOTTOM_ALPHA),
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
     */
    private fun lifted(fraction: Float): Float {
        val ceiling = RainCurveBands.moderateEdge
        if (fraction <= 0f || fraction >= ceiling) return fraction
        return ceiling * sqrt(fraction / ceiling)
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
     * About 440 KB as ARGB_8888, comfortably inside the roughly one megabyte a
     * process gets per Binder transaction, with the rest of the update to carry.
     *
     * It was a quarter of that when the widget was two cells tall and the hour
     * labels were real views. One cell is a much smaller picture, so this budget
     * lets it render at the screen's own density instead of being upscaled -
     * which is what makes the labels legible now that they are drawn in here.
     */
    private const val MAX_PIXELS = 110_000f

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
    private const val MIN_LABEL_DP = 15f
    private const val MAX_LABEL_DP = 24f

    private const val STROKE_DP = 2.5f

    /** One hour, as a fraction of the four-hour window, and half of one. */
    private const val HOUR = 0.25f
    private const val HALF_HOUR = 0.125f

    /** Half hours are a mark off the floor, not a rule up the chart. */
    private const val HALF_TICK_DP = 5f

    private const val LABEL_DP = 10f
    private const val LABEL_GAP_DP = 3f
    private const val LABEL_BASELINE_DP = 13f
    private const val HAIRLINE_DP = 1f

    /** Thin and hard, the way a bevel catches light. */
    private const val RIM_DP = 1.5f
    private const val RIM_BASE_ALPHA = 0.22f
    private const val RIM_PEAK_ALPHA = 0.85f

    private const val SHEEN_ALPHA = 0.055f
    private const val SHEEN_DEPTH = 0.38f

    /** Where the glow has fallen away entirely, and where it starts returning. */
    private const val TAIL_START = 0.3f
    private const val TAIL_END = 0.7f

    private const val QUARTER_TURN = 90f

    /** Gale force, the same number the hazard rules use. Full glow sits here. */
    private const val GALE_MS = 17.2

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
