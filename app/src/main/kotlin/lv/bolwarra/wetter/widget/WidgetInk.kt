package lv.bolwarra.wetter.widget

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.ui.components.drawCondition
import lv.bolwarra.wetter.ui.theme.WetterColors

/**
 * Draws a widget's contents: text fitted to its box, the app's condition
 * marks, and a record of where everything went.
 *
 * Text is shrunk to fit its box, down to a floor, and is left out rather than
 * clipped if it still does not fit. Callers give each element its own
 * non-overlapping box; [placed] lets the tests verify that.
 */
internal class WidgetInk(
    val canvas: Canvas,
    /** Pixels per dp in this picture, which is not necessarily the screen's. */
    val scale: Float,
    val colors: WetterColors,
    private val placed: MutableList<Placed>? = null,
) {

    /** Where one thing went, for the layout tests. */
    data class Placed(val what: String, val rect: RectF)

    fun px(dp: Float): Float = dp * scale

    /**
     * A fitted, positioned line not yet drawn, so layers beneath it can be told
     * where it will be. [rect] spans ascent to descent.
     */
    class Line internal constructor(
        val text: String,
        val rect: RectF,
        val baseline: Float,
        internal val paint: Paint,
        internal val what: String,
    )

    /**
     * Fits [text] into [box], or returns null if it cannot fit at [minDp].
     * Vertically centred unless a [baseline] is given.
     */
    fun set(
        text: String,
        sizeDp: Float,
        colour: Int,
        box: RectF,
        weight: Weight = Weight.NORMAL,
        align: Align = Align.CENTRE,
        minDp: Float = sizeDp * MIN_SHARE,
        tracking: Float = 0f,
        baseline: Float? = null,
        what: String = "text",
    ): Line? {
        val paint = paint(weight, sizeDp, colour, tracking) ?: return null
        fit(paint, text, box, sizeDp, minDp) ?: return null
        val width = paint.measureText(text)
        val metrics = paint.fontMetrics
        val left = when (align) {
            Align.START -> box.left
            Align.CENTRE -> box.centerX() - width / 2f
            Align.END -> box.right - width
        }
        val line = baseline ?: (box.centerY() - (metrics.ascent + metrics.descent) / 2f)
        val rect = RectF(left, line + metrics.ascent, left + width, line + metrics.descent)
        if (baseline != null &&
            (rect.top < box.top - EPSILON || rect.bottom > box.bottom + EPSILON)
        ) {
            return null
        }
        return Line(text, rect, line, paint, what)
    }

    /** Draws a line that was [set]. */
    fun draw(line: Line) {
        canvas.drawText(line.text, line.rect.left, line.baseline, line.paint)
        record(line.what, line.rect)
    }

    /** [set] and [draw] at once, for text nothing has to be told about first. */
    fun text(
        text: String,
        sizeDp: Float,
        colour: Int,
        box: RectF,
        weight: Weight = Weight.NORMAL,
        align: Align = Align.CENTRE,
        minDp: Float = sizeDp * MIN_SHARE,
        tracking: Float = 0f,
        what: String = "text",
    ): RectF? = set(text, sizeDp, colour, box, weight, align, minDp, tracking, what = what)
        ?.also { draw(it) }
        ?.rect

    /**
     * Word-wraps [text] onto at most [maxLines] lines from the top of [box],
     * shrinking towards [minDp] if needed; draws nothing if it still does not fit.
     */
    fun paragraph(
        text: String,
        sizeDp: Float,
        colour: Int,
        box: RectF,
        maxLines: Int,
        weight: Weight = Weight.NORMAL,
        minDp: Float = sizeDp * MIN_SHARE,
        what: String = "text",
    ): List<RectF> {
        var size = sizeDp
        while (size >= minDp) {
            val lines = wrap(text, size, weight, box.width())
            val lineHeight = lineHeight(size, weight)
            if (lines != null &&
                lines.size <= maxLines &&
                lines.size * lineHeight <= box.height()
            ) {
                return lines.mapIndexedNotNull { index, line ->
                    val top = box.top + index * lineHeight
                    text(
                        line,
                        size,
                        colour,
                        RectF(box.left, top, box.right, top + lineHeight),
                        weight,
                        Align.START,
                        minDp = size,
                        what = what,
                    )
                }
            }
            size -= PARAGRAPH_STEP_DP
        }
        return emptyList()
    }

    /** Greedy word wrap at [sizeDp]; null if a single word is wider than the line. */
    private fun wrap(text: String, sizeDp: Float, weight: Weight, width: Float): List<String>? {
        val lines = mutableListOf<String>()
        var current = ""
        text.split(' ').filter { it.isNotEmpty() }.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate, sizeDp, weight) <= width) {
                current = candidate
            } else {
                if (current.isEmpty()) return null
                lines += current
                current = word
                if (measure(current, sizeDp, weight) > width) return null
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    /** The width of [text] at [sizeDp], in pixels. */
    fun measure(
        text: String,
        sizeDp: Float,
        weight: Weight = Weight.NORMAL,
        tracking: Float = 0f,
    ): Float = paint(weight, sizeDp, 0, tracking)?.measureText(text) ?: 0f

    /** How tall a capital stands at [sizeDp], in pixels: baseline to cap line. */
    fun capHeight(sizeDp: Float, weight: Weight = Weight.NORMAL): Float {
        val paint = paint(weight, sizeDp, 0, 0f) ?: return 0f
        val bounds = android.graphics.Rect()
        paint.getTextBounds(CAP, 0, CAP.length, bounds)
        return -bounds.top.toFloat()
    }

    /** The height of a line at [sizeDp], ascent to descent, in pixels. */
    fun lineHeight(sizeDp: Float, weight: Weight = Weight.NORMAL): Float {
        val metrics = paint(weight, sizeDp, 0, 0f)?.fontMetrics ?: return 0f
        return metrics.descent - metrics.ascent
    }

    /** One of the app's condition marks, drawn by the app's own code. */
    fun glyph(
        condition: WeatherCondition,
        isDay: Boolean,
        box: RectF,
        ink: Int = colors.textSecondary.toArgb(),
        wet: Int = colors.precipitation.toArgb(),
    ) {
        if (box.width() <= 0f || box.height() <= 0f) return
        compose(box) { drawCondition(condition, Color(ink), Color(wet), isDay) }
        record("glyph", box)
    }

    /** Runs Compose drawing code into [box], so widgets reuse the app's drawing. */
    fun compose(box: RectF, density: Float = scale, draw: DrawScope.() -> Unit) {
        canvas.save()
        canvas.translate(box.left, box.top)
        CanvasDrawScope().draw(
            Density(density),
            LayoutDirection.Ltr,
            ComposeCanvas(canvas),
            Size(box.width(), box.height()),
            draw,
        )
        canvas.restore()
    }

    fun record(what: String, rect: RectF) {
        placed?.add(Placed(what, RectF(rect)))
    }

    /** The size [text] can be set at inside [box], or null when nothing will do. */
    private fun fit(paint: Paint, text: String, box: RectF, sizeDp: Float, minDp: Float): Float? {
        if (text.isEmpty() || box.width() <= 0f || box.height() <= 0f) return null
        val metrics = paint.fontMetrics
        val width = paint.measureText(text)
        val height = metrics.descent - metrics.ascent
        val share = min(1f, min(box.width() / width, box.height() / height))
        val size = sizeDp * share
        if (size < minDp) return null
        if (share < 1f) paint.textSize = px(size) * SHRINK_MARGIN
        return size
    }

    private fun paint(weight: Weight, sizeDp: Float, colour: Int, tracking: Float): Paint? {
        if (sizeDp <= 0f) return null
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = weight.typeface
            textSize = px(sizeDp)
            color = colour
            letterSpacing = tracking
            fontFeatureSettings = TABULAR
        }
    }

    enum class Align { START, CENTRE, END }

    /** The app's three type weights; by number from Android 9, by family name before. */
    enum class Weight(private val value: Int, private val family: String) {
        LIGHT(300, "sans-serif-light"),
        NORMAL(400, "sans-serif"),
        MEDIUM(500, "sans-serif-medium"),
        ;

        val typeface: Typeface by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.SANS_SERIF, value, false)
            } else {
                Typeface.create(family, Typeface.NORMAL)
            }
        }
    }

    private companion object {
        const val TABULAR = "tnum"
        const val CAP = "H"
        const val EPSILON = 0.5f
        const val PARAGRAPH_STEP_DP = 0.5f

        /** How far a line may shrink before it is left out instead. */
        const val MIN_SHARE = 0.78f

        /** Measured and rendered widths can differ by a pixel at fractional sizes. */
        const val SHRINK_MARGIN = 0.98f
    }
}
