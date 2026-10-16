// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.theme.WetterColors

/**
 * The temperature as the dial sets it, at any size: the figure in the light
 * weight with the unit small beside it at its cap line, in the app's 64:24
 * proportion.
 */
internal class WidgetReading(snapshot: WidgetSnapshot) {

    private val unit = snapshot.preferences.temperature
    val figure: String = formatTemperature(snapshot.face.current.temperature, unit)
    val suffix: String = unit.suffix

    /** How wide the row is with the figure at [figureDp], in pixels. */
    fun width(ink: WidgetInk, figureDp: Float): Float =
        ink.measure(figure, figureDp, WidgetInk.Weight.LIGHT, TRACKING) +
            ink.px(figureDp * GAP_SHARE) +
            ink.measure(suffix, figureDp * UNIT_SHARE, WidgetInk.Weight.LIGHT)

    /** How tall the row is with the figure at [figureDp], in pixels. */
    fun height(ink: WidgetInk, figureDp: Float): Float =
        ink.lineHeight(figureDp, WidgetInk.Weight.LIGHT)

    /** The figure size at which the row is exactly [widthPx] wide. */
    fun sizeForWidth(ink: WidgetInk, widthPx: Float): Float =
        PROBE_DP * widthPx / width(ink, PROBE_DP)

    /** The figure size at which the row is exactly [heightPx] tall. */
    fun sizeForHeight(ink: WidgetInk, heightPx: Float): Float =
        PROBE_DP * heightPx / height(ink, PROBE_DP)

    /**
     * Places the row at [left], [top] without drawing it; empty if the figure
     * cannot be set.
     */
    fun set(
        ink: WidgetInk,
        figureDp: Float,
        left: Float,
        top: Float,
        colors: WetterColors = ink.colors,
    ): List<WidgetInk.Line> {
        val figureWidth = ink.measure(figure, figureDp, WidgetInk.Weight.LIGHT, TRACKING)
        val height = height(ink, figureDp)
        val figureLine = ink.set(
            text = figure,
            sizeDp = figureDp,
            colour = colors.textPrimary.toArgb(),
            box = RectF(left, top, left + figureWidth, top + height),
            weight = WidgetInk.Weight.LIGHT,
            align = WidgetInk.Align.START,
            tracking = TRACKING,
            what = "reading",
        ) ?: return emptyList()

        val unitDp = figureDp * UNIT_SHARE
        val unitLeft = figureLine.rect.right + ink.px(figureDp * GAP_SHARE)
        val unitWidth = ink.measure(suffix, unitDp, WidgetInk.Weight.LIGHT)
        val capLine = figureLine.baseline - ink.capHeight(figureDp, WidgetInk.Weight.LIGHT)
        val unitLine = ink.set(
            text = suffix,
            sizeDp = unitDp,
            colour = colors.textTertiary.toArgb(),
            box = RectF(unitLeft, top, unitLeft + unitWidth, top + height),
            weight = WidgetInk.Weight.LIGHT,
            align = WidgetInk.Align.START,
            baseline = capLine + ink.capHeight(unitDp, WidgetInk.Weight.LIGHT),
            what = "unit",
        )
        return listOfNotNull(figureLine, unitLine)
    }

    private companion object {
        /** WetterType.readingUnit against WetterType.reading. */
        const val UNIT_SHARE = 24f / 64f

        /**
         * The gap between them as a share of the figure. Smaller than the app's
         * three points: the platform measures the degree sign with more side
         * bearing than Compose does.
         */
        const val GAP_SHARE = 1f / 64f

        /** WetterType.reading's tracking. */
        const val TRACKING = -0.03f

        /** The size the row's proportions are measured at. */
        const val PROBE_DP = 100f
    }
}
