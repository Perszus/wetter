// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatWeekdayShort
import lv.bolwarra.wetter.ui.format.labelRes

/**
 * The week ahead: a column per day with the day, its sky, and the high over the
 * low. Wider shows more days, up to the app's seven. With enough height each
 * day's range is drawn as a bar on a scale shared by the week.
 */
internal class WeekWidget : PictureWidget() {

    override val defaultWidthDp = 320f
    override val defaultHeightDp = 90f

    override fun draw(sheet: Sheet, snapshot: WidgetSnapshot) {
        val inner = sheet.inner(PAD_X_DP, PAD_Y_DP)
        DayColumns.draw(sheet, inner, snapshot)
    }

    override fun describe(context: Context, snapshot: WidgetSnapshot): String {
        val unit = snapshot.preferences.temperature
        return DayColumns.days(snapshot).joinToString(", ") { day ->
            "${formatWeekdayShort(day.date)} ${context.getString(day.appearance.labelRes())} " +
                "${formatTemperature(
                    day.temperatureMax,
                    unit,
                )} / ${formatTemperature(day.temperatureMin, unit)}"
        }
    }

    private companion object {
        const val PAD_X_DP = 6f
        const val PAD_Y_DP = 4f
    }
}

/** Days as columns in any rectangle: the week widget and the overview's last band. */
internal object DayColumns {

    /** Today onward, up to the app's week. */
    fun days(snapshot: WidgetSnapshot): List<DailyWeather> {
        val today = snapshot.now.atZone(snapshot.forecast.location.zone).toLocalDate()
        return snapshot.forecast.daily.filter { !it.date.isBefore(today) }.take(MAX_DAYS)
    }

    fun draw(sheet: Sheet, area: RectF, snapshot: WidgetSnapshot) {
        val ink = sheet.ink
        val unit = snapshot.preferences.temperature
        val all = days(snapshot)
        // Measured against the highs that will actually be shown.
        val figures = all.map { formatTemperature(it.temperatureMax, unit) }
        val m = narrowed(ink, area.width(), figures, Metrics.fit(ink, area.height() / sheet.scale))
        val count = min(all.size, columnsFor(ink, area.width(), figures, m))
        if (count == 0) return
        val days = all.take(count)
        val today = snapshot.now.atZone(snapshot.forecast.location.zone).toLocalDate()

        // One scale for the whole row, so the bars compare.
        val coldest = days.minOf { it.temperatureMin }
        val warmest = days.maxOf { it.temperatureMax }

        val width = area.width() / count
        val content = if (m.layout == Layout.RANGES) area.height() else m.contentHeight(ink)
        val top = area.centerY() - content / 2f

        days.forEachIndexed { index, day ->
            val slot = RectF(
                area.left + index * width,
                top,
                area.left + (index + 1) * width,
                top + content,
            )
                .apply { inset(sheet.px(COLUMN_INSET_DP), 0f) }
            var y = slot.top + sheet.px(m.padDp)

            val labelHeight = ink.lineHeight(m.labelDp, WidgetInk.Weight.MEDIUM)
            dayLabel(
                sheet,
                day.date,
                day.date == today,
                m,
                RectF(
                    slot.left,
                    y,
                    slot.right,
                    y + labelHeight,
                ),
            )
            y += labelHeight + sheet.px(m.gapDp)

            val high = formatTemperature(day.temperatureMax, unit)
            val low = formatTemperature(day.temperatureMin, unit)
            val highHeight = ink.lineHeight(m.figureDp)
            val lowHeight = ink.lineHeight(m.lowDp)

            if (m.layout == Layout.INLINE) {
                // The mark beside the high, centred together as one line.
                val size = sheet.px(m.glyphDp)
                val gap = sheet.px(INLINE_GAP_DP * m.k)
                val highWidth = ink.measure(high, m.figureDp)
                val left = slot.centerX() - (size + gap + highWidth) / 2f
                val middle = y + max(size, highHeight) / 2f
                ink.glyph(
                    day.appearance,
                    isDay = true,
                    box = RectF(
                        left,
                        middle - size / 2f,
                        left + size,
                        middle + size / 2f,
                    ),
                )
                figure(
                    sheet,
                    high,
                    m.figureDp,
                    true,
                    RectF(
                        left + size + gap,
                        middle - highHeight / 2f,
                        left + size + gap + highWidth,
                        middle + highHeight / 2f,
                    ),
                )
                return@forEachIndexed
            }

            if (m.glyphDp > 0f) {
                val size = sheet.px(m.glyphDp)
                ink.glyph(
                    day.appearance,
                    isDay = true,
                    box = RectF(
                        slot.centerX() - size / 2f,
                        y,
                        slot.centerX() + size / 2f,
                        y + size,
                    ),
                )
                y += size + sheet.px(m.gapDp)
            }

            val bottom = slot.bottom - sheet.px(m.padDp)
            when (m.layout) {
                Layout.RANGES -> range(
                    sheet,
                    RectF(slot.left, y, slot.right, bottom),
                    day,
                    coldest,
                    warmest,
                    high,
                    low,
                    m,
                )
                Layout.STACKED -> {
                    figure(
                        sheet,
                        high,
                        m.figureDp,
                        true,
                        RectF(
                            slot.left,
                            y,
                            slot.right,
                            y + highHeight,
                        ),
                    )
                    y += highHeight
                    figure(
                        sheet,
                        low,
                        m.lowDp,
                        false,
                        RectF(slot.left, y, slot.right, y + lowHeight),
                    )
                }
                else -> pair(sheet, high, low, m, RectF(slot.left, y, slot.right, y + highHeight))
            }
        }
    }

    /** The high and the low on one line; the high alone where both will not fit. */
    private fun pair(sheet: Sheet, high: String, low: String, m: Metrics, line: RectF) {
        val ink = sheet.ink
        val gap = sheet.px(PAIR_GAP_DP * m.k)
        val highWidth = ink.measure(high, m.figureDp)
        val lowWidth = ink.measure(low, m.lowDp)
        if (highWidth + gap + lowWidth > line.width()) {
            figure(sheet, high, m.figureDp, true, line)
            return
        }
        val left = line.centerX() - (highWidth + gap + lowWidth) / 2f
        figure(sheet, high, m.figureDp, true, RectF(left, line.top, left + highWidth, line.bottom))
        val lowLeft = left + highWidth + gap
        figure(
            sheet,
            low,
            m.lowDp,
            false,
            RectF(lowLeft, line.top, lowLeft + lowWidth, line.bottom),
        )
    }

    /** "Today" in full ink, or the weekday where it does not fit; other days dimmer. */
    private fun dayLabel(sheet: Sheet, date: LocalDate, isToday: Boolean, m: Metrics, box: RectF) {
        val ink = if (isToday) sheet.colors.textPrimary else sheet.colors.textSecondary
        val colour = ink.toArgb()
        val placed = isToday &&
            sheet.ink.text(
                text = sheet.context.getString(R.string.week_today),
                sizeDp = m.labelDp,
                colour = colour,
                box = box,
                weight = WidgetInk.Weight.MEDIUM,
                minDp = m.labelDp * TODAY_SHRINK,
                what = "day",
            ) != null
        if (!placed) {
            sheet.ink.text(
                formatWeekdayShort(date),
                m.labelDp,
                colour,
                box,
                WidgetInk.Weight.MEDIUM,
                what = "day",
            )
        }
    }

    private fun figure(sheet: Sheet, text: String, sizeDp: Float, high: Boolean, box: RectF) {
        val colour = if (high) sheet.colors.textPrimary else sheet.colors.textTertiary
        sheet.ink.text(text, sizeDp, colour.toArgb(), box, what = if (high) "high" else "low")
    }

    /**
     * The day's range as a bar on the week's scale, the high just above it and
     * the low just below. The track reserves a label's height at each end so
     * the labels never leave the column.
     */
    private fun range(
        sheet: Sheet,
        slot: RectF,
        day: DailyWeather,
        coldest: Double,
        warmest: Double,
        high: String,
        low: String,
        m: Metrics,
    ) {
        val ink = sheet.ink
        val highHeight = ink.lineHeight(m.figureDp)
        val lowHeight = ink.lineHeight(m.lowDp)
        val trackTop = slot.top + highHeight
        val trackBottom = slot.bottom - lowHeight
        if (trackBottom - trackTop < sheet.px(MIN_TRACK_DP)) return

        val span = max(warmest - coldest, MIN_SPAN)
        fun yOf(t: Double) =
            (trackBottom - (trackBottom - trackTop) * ((t - coldest) / span)).toFloat()
        val barTop = yOf(day.temperatureMax)
        val barBottom = max(
            yOf(day.temperatureMin),
            barTop + sheet.px(BAR_MIN_DP),
        ).coerceAtMost(trackBottom)

        val half = sheet.px(BAR_WIDTH_DP * m.k) / 2f
        val bar = RectF(slot.centerX() - half, barTop, slot.centerX() + half, barBottom)
        ink.canvas.drawRoundRect(
            bar,
            half,
            half,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    trackTop,
                    0f,
                    trackBottom,
                    sheet.colors.textPrimary.toArgb(),
                    sheet.colors.textTertiary.toArgb(),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        ink.record("range", bar)

        figure(
            sheet,
            high,
            m.figureDp,
            true,
            RectF(slot.left, barTop - highHeight, slot.right, barTop),
        )
        figure(
            sheet,
            low,
            m.lowDp,
            false,
            RectF(
                slot.left,
                barBottom,
                slot.right,
                barBottom + lowHeight,
            ),
        )
    }

    /** As many days as fit, up to the week. */
    private fun columnsFor(ink: WidgetInk, width: Float, figures: List<String>, m: Metrics): Int =
        floor(width / columnWidth(ink, figures, m)).toInt().coerceIn(MIN_DAYS, MAX_DAYS)

    private fun columnWidth(ink: WidgetInk, figures: List<String>, m: Metrics): Float {
        val air = ink.px(COLUMN_INSET_DP * 2 + LABEL_AIR_DP)
        val figure = figures.maxOfOrNull { ink.measure(it, m.figureDp) } ?: 0f
        val line = if (m.layout ==
            Layout.INLINE
        ) {
            ink.px(m.glyphDp + INLINE_GAP_DP * m.k) + figure
        } else {
            figure
        }
        return max(ink.px(COLUMN_MIN_DP * m.k), line + air)
    }

    /**
     * [m], made small enough that the minimum number of days fits across
     * [width]: smaller type first, and the marks given up only below the floor.
     */
    private fun narrowed(ink: WidgetInk, width: Float, figures: List<String>, m: Metrics): Metrics {
        fun fits(candidate: Metrics) = columnWidth(ink, figures, candidate) * MIN_DAYS <= width
        if (fits(m)) return m
        var k = m.k
        while (k >= LAST_K) {
            Metrics(m.layout, k).takeIf(::fits)?.let { return it }
            k -= K_STEP
        }
        return Metrics(Layout.BARE, LAST_K)
    }

    private enum class Layout {
        /** Day, mark, and the range as a bar with the high above and the low below. */
        RANGES,

        /** Day, mark, high, low, stacked. */
        STACKED,

        /** Day, mark, then the high and low on one line. */
        SINGLE,

        /** Day above; mark and high side by side. Made for a one-row widget. */
        INLINE,

        /** Day and high and low only, when nothing else fits. */
        BARE,
    }

    private class Metrics(val layout: Layout, val k: Float) {
        val labelDp = LABEL_DP * k
        val glyphDp = k * when (layout) {
            Layout.INLINE -> INLINE_GLYPH_DP
            Layout.BARE -> 0f
            else -> GLYPH_DP
        }
        val figureDp = FIGURE_DP * k
        val lowDp = LOW_DP * k
        val gapDp = GAP_DP * k
        val padDp = PAD_DP * k

        fun contentHeight(ink: WidgetInk): Float {
            val label = ink.lineHeight(labelDp, WidgetInk.Weight.MEDIUM) + ink.px(gapDp)
            val high = ink.lineHeight(figureDp)
            val low = ink.lineHeight(lowDp)
            val mark = ink.px(glyphDp + gapDp)
            val body = when (layout) {
                Layout.RANGES -> mark + high + ink.px(RANGE_TRACK_DP * k) + low
                Layout.STACKED -> mark + high + low
                Layout.SINGLE -> mark + high
                Layout.INLINE -> max(ink.px(glyphDp), high)
                Layout.BARE -> high
            }
            return ink.px(padDp * 2) + label + body
        }

        companion object {
            /**
             * The richest layout that fits [heightDp] at no less than [MIN_K]
             * of the app's sizes, scaled up to [MAX_K] where there is room.
             */
            fun fit(ink: WidgetInk, heightDp: Float): Metrics {
                fun scaled(layout: Layout, floor: Float): Metrics? {
                    val natural = Metrics(layout, 1f).contentHeight(ink) / ink.scale
                    // A one-row layout spends spare height on more days, not larger type.
                    val ceiling = if (layout == Layout.INLINE ||
                        layout == Layout.BARE
                    ) {
                        1f
                    } else {
                        MAX_K
                    }
                    val k = min(ceiling, heightDp / natural)
                    return if (k >= floor) Metrics(layout, k) else null
                }
                return scaled(Layout.RANGES, MIN_K)
                    ?: scaled(Layout.STACKED, MIN_K)
                    ?: scaled(Layout.SINGLE, MIN_K)
                    ?: scaled(Layout.INLINE, MIN_K)
                    ?: scaled(Layout.BARE, LAST_K)
                    ?: Metrics(Layout.BARE, LAST_K)
            }
        }
    }

    // The week page's sizes (WeekPage.kt).
    private const val LABEL_DP = 13f
    private const val GLYPH_DP = 22f
    private const val FIGURE_DP = 15f
    private const val LOW_DP = 13f
    private const val GAP_DP = 4f
    private const val PAD_DP = 4f

    /** Beside the high the mark matches its height rather than standing over it. */
    private const val INLINE_GLYPH_DP = 17f
    private const val INLINE_GAP_DP = 3f

    /** The natural length of a range track. */
    private const val RANGE_TRACK_DP = 44f
    private const val MIN_TRACK_DP = 20f
    private const val BAR_WIDTH_DP = 6f
    private const val BAR_MIN_DP = 6f

    /** The narrowest span the scale covers, so a flat week is not exaggerated. */
    private const val MIN_SPAN = 4.0

    private const val PAIR_GAP_DP = 4f

    /** How far a row may shrink and grow from those sizes; the bare layout may go a little further. */
    private const val MIN_K = 0.8f
    private const val LAST_K = 0.7f
    private const val MAX_K = 1.2f
    private const val K_STEP = 0.02f

    private const val COLUMN_MIN_DP = 46f
    private const val COLUMN_INSET_DP = 2f
    private const val LABEL_AIR_DP = 4f

    /** "Today" may shrink this much before the weekday is used instead. */
    private const val TODAY_SHRINK = 0.85f
    private const val MIN_DAYS = 3
    private const val MAX_DAYS = 7
}
