// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.content.Context
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import kotlin.math.min
import lv.bolwarra.wetter.ui.format.labelRes

/**
 * The weather right now. At two cells square or more it is the dial with the
 * weather on its glass; smaller, the reading alone: mark, temperature and word.
 */
internal class NowWidget : PictureWidget() {

    override val defaultWidthDp = 160f
    override val defaultHeightDp = 160f

    override fun draw(sheet: Sheet, snapshot: WidgetSnapshot) {
        // The dial takes the plate with a slim margin; the reading alone takes a
        // wider one, shrinking on a one-cell plate, which has little room to give.
        val dialArea = sheet.inner(DIAL_PAD_DP)
        val side = min(dialArea.width(), dialArea.height())
        if (side / sheet.scale >= DIAL_FROM_DP) {
            val box = RectF(
                dialArea.centerX() - side / 2f,
                dialArea.centerY() - side / 2f,
                dialArea.centerX() + side / 2f,
                dialArea.centerY() + side / 2f,
            )
            WidgetDial.draw(sheet, box, snapshot)
        } else {
            val shortSide = min(sheet.widthDp, sheet.heightDp)
            compact(sheet, sheet.inner(min(PAD_DP, shortSide * SMALL_PAD_SHARE)), snapshot)
        }
    }

    override fun describe(context: Context, snapshot: WidgetSnapshot): String {
        val reading = WidgetReading(snapshot)
        return "${reading.figure}${reading.suffix}, ${context.getString(
            snapshot.face.seen.labelRes(),
        )}"
    }

    /**
     * The reading without the dial: in a line on a wide plate, stacked otherwise.
     * The figure is sized first; if space runs out the word goes, then the mark.
     */
    private fun compact(sheet: Sheet, inner: RectF, snapshot: WidgetSnapshot) {
        val ink = sheet.ink
        val reading = WidgetReading(snapshot)
        val word = sheet.context.getString(snapshot.face.seen.labelRes())
        val current = snapshot.face.current

        val lines: List<WidgetInk.Line>
        val glyph: RectF?
        if (inner.width() >= inner.height() * WIDE) {
            // The mark matches the figure's cap height so the line reads as one.
            val figureDp = min(
                min(reading.sizeForHeight(ink, inner.height() * LINE_SHARE), FIGURE_MAX_DP),
                reading.sizeForWidth(ink, inner.width() * FIGURE_WIDTH_SHARE),
            )
            val figureWidth = reading.width(ink, figureDp)
            val figureHeight = reading.height(ink, figureDp)
            val markSize = ink.capHeight(figureDp, WidgetInk.Weight.LIGHT) * MARK_TO_CAP
            val gap = sheet.px(GAP_DP)
            val wordDp = (figureDp * WORD_SHARE).coerceIn(WORD_MIN_DP, WORD_MAX_DP)
            val wordWidth = ink.measure(word, wordDp, WidgetInk.Weight.MEDIUM)

            val withWord = markSize + gap + figureWidth + gap * 2 + wordWidth
            val besides = withWord <= inner.width()
            val rowWidth = if (besides) withWord else markSize + gap + figureWidth
            val wordBelow = !besides &&
                figureHeight + ink.lineHeight(wordDp, WidgetInk.Weight.MEDIUM) <= inner.height()
            val blockHeight = figureHeight +
                if (wordBelow) ink.lineHeight(wordDp, WidgetInk.Weight.MEDIUM) else 0f

            val left = inner.centerX() - rowWidth / 2f
            val top = inner.centerY() - blockHeight / 2f
            val row = reading.set(ink, figureDp, left + markSize + gap, top)
            val figure = row.firstOrNull()
            glyph = figure?.let {
                val capLine = it.baseline - ink.capHeight(figureDp, WidgetInk.Weight.LIGHT)
                val middle = (capLine + it.baseline) / 2f
                RectF(left, middle - markSize / 2f, left + markSize, middle + markSize / 2f)
            }
            val wordLine = when {
                figure == null -> null
                besides -> {
                    val wordLeft = row.last().rect.right + gap * 2
                    ink.set(
                        word,
                        wordDp,
                        sheet.colors.textSecondary.toArgb(),
                        RectF(wordLeft, top, inner.right, top + figureHeight),
                        WidgetInk.Weight.MEDIUM,
                        WidgetInk.Align.START,
                        what = "word",
                    )
                }
                wordBelow -> ink.set(
                    word,
                    wordDp,
                    sheet.colors.textSecondary.toArgb(),
                    RectF(
                        inner.left,
                        top + figureHeight,
                        inner.right,
                        inner.bottom.coerceAtMost(
                            top + blockHeight,
                        ),
                    ),
                    WidgetInk.Weight.MEDIUM,
                    what = "word",
                )
                else -> null
            }
            lines = row + listOfNotNull(wordLine)
        } else {
            // On the smallest plates the mark and the word go, and the figure takes their room.
            val tiny = min(sheet.widthDp, sheet.heightDp) < MARK_FROM_DP
            val figureDp = min(
                min(reading.sizeForWidth(ink, inner.width() * FIGURE_WIDTH_SHARE), FIGURE_MAX_DP),
                reading.sizeForHeight(
                    ink,
                    inner.height() * if (tiny) TINY_FIGURE_SHARE else STACK_FIGURE_SHARE,
                ),
            )
            val figureHeight = reading.height(ink, figureDp)
            val markSize = (ink.capHeight(figureDp, WidgetInk.Weight.LIGHT) * MARK_TO_CAP)
                .coerceAtMost(sheet.px(MARK_MAX_DP))
            val wordDp = (figureDp * WORD_SHARE).coerceIn(WORD_MIN_DP, WORD_MAX_DP)
            val wordHeight = ink.lineHeight(wordDp, WidgetInk.Weight.MEDIUM)
            val gap = sheet.px(GAP_DP)

            var withMark = !tiny
            var withWord = !tiny
            fun height() = figureHeight +
                (if (withMark) markSize + gap else 0f) +
                (if (withWord) wordHeight else 0f)
            if (height() > inner.height()) withWord = false
            if (height() > inner.height()) withMark = false

            var top = inner.centerY() - height() / 2f
            glyph = if (withMark) {
                RectF(
                    inner.centerX() - markSize / 2f,
                    top,
                    inner.centerX() + markSize / 2f,
                    top + markSize,
                )
                    .also { top += markSize + gap }
            } else {
                null
            }
            val row = reading.set(
                ink,
                figureDp,
                inner.centerX() - reading.width(ink, figureDp) / 2f,
                top,
            )
            top += figureHeight
            val wordLine = if (withWord && row.isNotEmpty()) {
                ink.set(
                    word,
                    wordDp,
                    sheet.colors.textSecondary.toArgb(),
                    RectF(inner.left, top, inner.right, top + wordHeight),
                    WidgetInk.Weight.MEDIUM,
                    what = "word",
                )
            } else {
                null
            }
            lines = row + listOfNotNull(wordLine)
        }

        // The glass is light, so it shows only on a dark plate; a light one goes without.
        if (!sheet.colors.isLight) {
            WidgetGlass.draw(
                canvas = ink.canvas,
                context = sheet.context,
                name = snapshot.face.glass,
                area = sheet.bounds,
                scale = sheet.scale,
                round = false,
                opacity = PLATE_GLASS_OPACITY,
                cornerRadius = sheet.cornerRadius(),
                text = lines,
                marks = listOfNotNull(glyph),
            )
        }
        glyph?.let { ink.glyph(snapshot.face.seen, current.isDay, it) }
        lines.forEach { ink.draw(it) }
    }

    private companion object {
        /** The rain strip's inset. */
        const val PAD_DP = 10f
        const val SMALL_PAD_SHARE = 0.1f

        /** The dial's margin to the plate edge. */
        const val DIAL_PAD_DP = 5f

        /** Below this the dial's figure is too small to read. */
        const val DIAL_FROM_DP = 96f

        /** Aspect ratio from which the reading runs in a line. */
        const val WIDE = 1.45f

        /** The figure's share of the height, in a line and in a stack. */
        const val LINE_SHARE = 0.62f
        const val STACK_FIGURE_SHARE = 0.46f
        const val TINY_FIGURE_SHARE = 0.62f

        /** Below this the plate holds the figure alone. */
        const val MARK_FROM_DP = 52f

        /** The figure's widest share of the plate. */
        const val FIGURE_WIDTH_SHARE = 0.82f
        const val FIGURE_MAX_DP = 46f

        /** The mark's size against the figure's cap height. */
        const val MARK_TO_CAP = 1.15f
        const val MARK_MAX_DP = 28f

        /**
         * Fainter across a whole plate than inside the dial's well, where the
         * porcelain and the falloff already hold it back.
         */
        const val PLATE_GLASS_OPACITY = 0.12f

        const val WORD_SHARE = 0.34f
        const val WORD_MIN_DP = 10f
        const val WORD_MAX_DP = 14f
        const val GAP_DP = 4f
    }
}
