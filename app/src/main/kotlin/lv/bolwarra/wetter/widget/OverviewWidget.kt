// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.content.Context
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import kotlin.math.min
import lv.bolwarra.wetter.ui.format.NextRain
import lv.bolwarra.wetter.ui.format.labelRes

/**
 * Most of the app on the home screen: the dial beside the next rain (sentence
 * and four-hour strip), then the coming hours, then the week, each drawn by the
 * same code as its own widget. As height shrinks the week goes first, then the
 * hours.
 */
internal class OverviewWidget : PictureWidget() {

    override val defaultWidthDp = 320f
    override val defaultHeightDp = 300f

    override fun draw(sheet: Sheet, snapshot: WidgetSnapshot) {
        val inner = sheet.inner(PAD_DP)
        val gap = sheet.px(BAND_GAP_DP)
        val heightDp = inner.height() / sheet.scale

        val bands = when {
            heightDp >= HEAD_MIN_DP + HOURS_MIN_DP + WEEK_MIN_DP + BAND_GAP_DP * 2 -> 3
            heightDp >= HEAD_MIN_DP + HOURS_MIN_DP + BAND_GAP_DP -> 2
            else -> 1
        }
        // The bands below the dial split what is left evenly.
        val head = when (bands) {
            1 -> inner.height()
            else -> min(inner.height() * HEAD_SHARE[bands]!!, sheet.px(HEAD_MAX_DP))
        }
        val rest = (inner.height() - head - gap * (bands - 1)) / (bands - 1).coerceAtLeast(1)

        val headBand = RectF(inner.left, inner.top, inner.right, inner.top + head)
        drawHead(sheet, headBand, snapshot)
        if (bands >= 2) {
            val top = headBand.bottom + gap
            HourColumns.draw(sheet, RectF(inner.left, top, inner.right, top + rest), snapshot)
        }
        if (bands >= 3) {
            val top = inner.bottom - rest
            DayColumns.draw(sheet, RectF(inner.left, top, inner.right, inner.bottom), snapshot)
        }
    }

    override fun describe(context: Context, snapshot: WidgetSnapshot): String {
        val reading = WidgetReading(snapshot)
        val rain = NextRain.of(
            context.resources,
            snapshot.forecast,
            snapshot.timeline,
            snapshot.now,
        )
        return "${snapshot.forecast.location.name}: ${reading.figure}${reading.suffix}, " +
            "${context.getString(snapshot.face.seen.labelRes())}. ${rain.text}"
    }

    /** The dial on the left; the place, the next rain and the rain strip on the right. */
    private fun drawHead(sheet: Sheet, band: RectF, snapshot: WidgetSnapshot) {
        val ink = sheet.ink
        val colors = sheet.colors
        val side = min(band.height(), band.width() * DIAL_MAX_SHARE)
        val dial = RectF(
            band.left,
            band.centerY() - side / 2f,
            band.left + side,
            band.centerY() + side / 2f,
        )
        WidgetDial.draw(sheet, dial, snapshot)

        val right = RectF(dial.right + sheet.px(COLUMN_GAP_DP), band.top, band.right, band.bottom)
        if (right.width() < sheet.px(RIGHT_MIN_DP)) return

        var y = right.top + sheet.px(TEXT_TOP_DP)
        val placeHeight = ink.lineHeight(PLACE_DP, WidgetInk.Weight.MEDIUM)
        ink.text(
            text = snapshot.forecast.location.name,
            sizeDp = PLACE_DP,
            colour = colors.textTertiary.toArgb(),
            box = RectF(right.left, y, right.right, y + placeHeight),
            weight = WidgetInk.Weight.MEDIUM,
            align = WidgetInk.Align.START,
            tracking = PLACE_TRACKING,
            what = "place",
        )
        y += placeHeight + sheet.px(LINE_GAP_DP)

        val rain = NextRain.of(
            sheet.context.resources,
            snapshot.forecast,
            snapshot.timeline,
            snapshot.now,
        )
        val rainColour = (if (rain.matters) colors.textPrimary else colors.textTertiary).toArgb()
        val lines = ink.paragraph(
            text = rain.text,
            sizeDp = SENTENCE_DP,
            colour = rainColour,
            box = RectF(right.left, y, right.right, right.bottom),
            maxLines = 2,
            what = "sentence",
        )
        y = (lines.maxOfOrNull { it.bottom } ?: y) + sheet.px(STRIP_GAP_DP)

        val strip = RectF(right.left - sheet.px(STRIP_BLEED_DP), y, right.right, right.bottom)
        if (strip.height() >= sheet.px(STRIP_MIN_DP)) {
            val window = RainWindow.of(snapshot)
            RainStrip.drawStrip(
                canvas = ink.canvas,
                box = strip,
                scale = sheet.scale,
                colors = colors,
                rates = window.rates,
                hourOffset = window.hourOffset,
                hourLabels = window.hourLabels,
            )
            ink.record("frame:strip", strip)
        }
    }

    private companion object {
        const val PAD_DP = 10f
        const val BAND_GAP_DP = 6f
        const val COLUMN_GAP_DP = 12f

        /** The least each band needs to be drawn at all. */
        const val HEAD_MIN_DP = 104f
        const val HOURS_MIN_DP = 58f
        const val WEEK_MIN_DP = 62f

        /** The dial band's share of the height, by band count. */
        val HEAD_SHARE = mapOf(2 to 0.6f, 3 to 0.44f)
        const val HEAD_MAX_DP = 176f

        /** The dial's widest share of the width, leaving room for the rain. */
        const val DIAL_MAX_SHARE = 0.46f
        const val RIGHT_MIN_DP = 96f

        const val TEXT_TOP_DP = 4f
        const val PLACE_DP = 12f
        const val PLACE_TRACKING = 0.02f
        const val LINE_GAP_DP = 2f
        const val SENTENCE_DP = 14f
        const val STRIP_GAP_DP = 4f

        /** Cancels the strip's own plate inset next to the dial. */
        const val STRIP_BLEED_DP = 10f
        const val STRIP_MIN_DP = 44f
    }
}
