// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.graphics.RectF
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import kotlin.math.min
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.forecast.UmbrellaDay
import lv.bolwarra.wetter.ui.components.drawFiredEdge
import lv.bolwarra.wetter.ui.components.drawHazardTriangle
import lv.bolwarra.wetter.ui.components.drawHourTicks
import lv.bolwarra.wetter.ui.components.drawPorcelain
import lv.bolwarra.wetter.ui.components.hazardInk
import lv.bolwarra.wetter.ui.format.labelRes

/**
 * The app's dial as a scale model, drawn by the app's own porcelain, edge and
 * tick code at a density scaled by the ratio of the two faces.
 *
 * The time pointer is omitted because a picture redrawn every ten minutes
 * cannot keep it accurate, and the travelling wind light because widgets cannot
 * animate; the plate's rim shows the wind instead.
 */
internal object WidgetDial {

    /**
     * Draws the dial into the square [box], with the reading at its centre, a
     * warning above it and the umbrella below when they apply.
     */
    fun draw(sheet: Sheet, box: RectF, snapshot: WidgetSnapshot) {
        val ink = sheet.ink
        val colors = sheet.colors
        val model = box.width() / sheet.scale / APP_DIAL_DP

        // Laid out first so the glass can be cleared beneath it.
        val reading = reading(sheet, box, snapshot, model)
        val marks = marks(sheet, box, model, reading, snapshot)

        ink.compose(box, density = sheet.scale * model) {
            drawPorcelain(
                face = colors.surface,
                lip = lerp(colors.surface, colors.surfaceShade, colors.relief),
                floorCatch = lerp(colors.surface, colors.surfaceRaised, colors.relief),
                occlusion = colors.hairline,
            )
        }
        WidgetGlass.draw(
            canvas = ink.canvas,
            context = sheet.context,
            name = snapshot.face.glass,
            area = box,
            scale = sheet.scale,
            round = true,
            text = reading,
            marks = marks.map { it.second },
        )
        ink.compose(box, density = sheet.scale * model) {
            drawFiredEdge(colors.hairline)
            drawHourTicks(colors.hairline)
        }
        reading.forEach { ink.draw(it) }
        marks.forEach { (mark, at) -> mark(at) }
        ink.record("frame:dial", box)
    }

    /** The figure, its unit and the word, at the app's proportions scaled to the face. */
    private fun reading(
        sheet: Sheet,
        box: RectF,
        snapshot: WidgetSnapshot,
        model: Float,
    ): List<WidgetInk.Line> {
        val ink = sheet.ink
        val reading = WidgetReading(snapshot)
        val word = sheet.context.getString(snapshot.face.seen.labelRes())

        val column = box.width() * TEXT_SHARE
        val figureDp = min(READING_DP * model, reading.sizeForWidth(ink, column))
        val wordDp = (WORD_DP * model).coerceIn(WORD_MIN_DP, WORD_DP)

        val figureHeight = reading.height(ink, figureDp)
        val wordHeight = ink.lineHeight(wordDp, WidgetInk.Weight.MEDIUM)
        val gap = sheet.px(WORD_GAP_DP * model)
        val top = box.centerY() - (figureHeight + gap + wordHeight) / 2f
        val left = box.centerX() - reading.width(ink, figureDp) / 2f
        val row = reading.set(ink, figureDp, left, top)
        if (row.isEmpty()) return emptyList()

        val wordTop = top + figureHeight + gap
        val wordLine = ink.set(
            text = word,
            sizeDp = wordDp,
            colour = sheet.colors.textSecondary.toArgb(),
            box = RectF(
                box.centerX() - column / 2f,
                wordTop,
                box.centerX() + column / 2f,
                wordTop + wordHeight,
            ),
            weight = WidgetInk.Weight.MEDIUM,
            minDp = WORD_MIN_DP * WORD_FLOOR_SHARE,
            what = "word",
        )
        return row + listOfNotNull(wordLine)
    }

    /**
     * The warning and the umbrella, centred in the clear bands between the
     * reading and the hour ticks. A widget has no gutter beside the dial, so
     * they go inside it.
     */
    private fun marks(
        sheet: Sheet,
        box: RectF,
        model: Float,
        reading: List<WidgetInk.Line>,
        snapshot: WidgetSnapshot,
    ): List<Pair<(RectF) -> Unit, RectF>> {
        if (reading.isEmpty()) return emptyList()
        val colors = sheet.colors
        val size = sheet.px((MARK_DP * model).coerceAtLeast(MARK_MIN_DP))
        val scaleTop = box.top + sheet.px(SCALE_DEPTH_DP * model)
        val scaleBottom = box.bottom - sheet.px(SCALE_DEPTH_DP * model)
        val readingTop = reading.minOf { it.rect.top }
        val readingBottom = reading.maxOf { it.rect.bottom }

        fun band(from: Float, to: Float): RectF? {
            if (to - from < size) return null
            val middle = (from + to) / 2f
            return RectF(
                box.centerX() - size / 2f,
                middle - size / 2f,
                box.centerX() + size / 2f,
                middle + size / 2f,
            )
        }

        val placed = mutableListOf<Pair<(RectF) -> Unit, RectF>>()

        snapshot.hazards.firstOrNull()?.let { worst ->
            band(scaleTop, readingTop)?.let { at ->
                placed += { r: RectF ->
                    sheet.ink.compose(r) {
                        drawHazardTriangle(hazardInk(worst.severity, colors.warning))
                    }
                    sheet.ink.record("hazard", r)
                } to at
            }
        }

        if (UmbrellaDay.isUmbrellaDay(snapshot.forecast, snapshot.timeline, snapshot.now)) {
            band(readingBottom, scaleBottom)?.let { at ->
                placed += { r: RectF ->
                    ContextCompat.getDrawable(sheet.context, R.drawable.ic_umbrella)?.apply {
                        setTint(colors.precipitation.toArgb())
                        setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                        draw(sheet.ink.canvas)
                    }
                    sheet.ink.record("umbrella", r)
                } to at
            }
        }
        return placed
    }

    /** The app's dial, which this one is a scale model of. */
    private const val APP_DIAL_DP = 240f

    // The app's own type sizes on that dial (WetterType.reading and title),
    // scaled with it.
    private const val READING_DP = 64f
    private const val WORD_DP = 16f
    private const val WORD_GAP_DP = 4f

    /** The word's floor, so it stays readable on a small face. */
    private const val WORD_MIN_DP = 11f

    /** How much further a long word may shrink. */
    private const val WORD_FLOOR_SHARE = 0.85f

    /** The share of the face's width the reading may use. */
    private const val TEXT_SHARE = 0.72f

    /** The app's marks are 26 dp beside a 240 dp face. */
    private const val MARK_DP = 26f
    private const val MARK_MIN_DP = 13f

    /** The fired edge, gap and long tick, plus air: the marks stay inside this. */
    private const val SCALE_DEPTH_DP = 3f + 6f + 7f + 4f
}
