// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.format.rateNow
import lv.bolwarra.wetter.ui.theme.Emphasis

/**
 * The coming hours, as the app's hour strip: a column per hour with the time,
 * the sky, the temperature and the rain as a bar. Wet hours are washed and
 * their time set in full ink. Wider shows more hours; taller adds rows.
 */
internal class HoursWidget : PictureWidget() {

    override val defaultWidthDp = 320f
    override val defaultHeightDp = 90f

    override fun draw(sheet: Sheet, snapshot: WidgetSnapshot) {
        val inner = sheet.inner(PAD_X_DP, PAD_Y_DP)
        HourColumns.draw(sheet, inner, snapshot, rowsFor(inner.height() / sheet.scale))
    }

    override fun describe(context: Context, snapshot: WidgetSnapshot): String {
        val cells = HourColumns.cells(context, snapshot, DESCRIBED)
        return cells.joinToString(", ") { cell ->
            "${cell.label} ${formatTemperature(
                cell.temperature,
                snapshot.preferences.temperature,
            )} " +
                context.getString(cell.condition.labelRes())
        }
    }

    private fun rowsFor(heightDp: Float): Int =
        floor(heightDp / HourColumns.ROW_DP).toInt().coerceIn(1, MAX_ROWS)

    private companion object {
        const val PAD_X_DP = 6f
        const val PAD_Y_DP = 4f
        const val MAX_ROWS = 3

        /** Hours read out by a screen reader. */
        const val DESCRIBED = 6
    }
}

/** Hours as columns in any rectangle: the hours widget and a band of the overview. */
internal object HourColumns {

    /** One column. */
    class Cell(
        val label: String,
        val condition: WeatherCondition,
        val isDay: Boolean,
        val temperature: Double?,
        val rate: Double?,
        /** Rain worth naming, by the same bar as the app's next-rain sentence. */
        val wet: Boolean,
    )

    /**
     * The first [count] columns: now (the dial's own reading), then each whole
     * hour after it.
     */
    fun cells(context: Context, snapshot: WidgetSnapshot, count: Int): List<Cell> {
        val face = snapshot.face
        val now = snapshot.now
        val zone = snapshot.forecast.location.zone
        val nowRate = rateNow(snapshot.timeline, snapshot.forecast.hourly, now)
        val first = Cell(
            label = context.getString(R.string.radar_now),
            condition = face.seen,
            isDay = face.current.isDay,
            temperature = face.current.temperature,
            rate = nowRate,
            wet = PrecipitationIntensity.ofRate(nowRate).isWorthNaming,
        )
        val later = snapshot.forecast.hourly
            .filter { it.timestamp.isAfter(now) }
            .take(max(0, count - 1))
            .map { hour ->
                Cell(
                    label = formatTime(hour.timestamp, zone),
                    condition = hour.appearance,
                    isDay = hour.isDay,
                    temperature = hour.temperature,
                    rate = hour.precipitationRate,
                    wet = hour.intensity.isWorthNaming,
                )
            }
        return listOf(first) + later
    }

    /**
     * Draws [rows] rows of hours into [area], as many columns as fit, in the
     * richest arrangement the row height allows (see [Metrics.fit]).
     */
    fun draw(sheet: Sheet, area: RectF, snapshot: WidgetSnapshot, rows: Int = 1) {
        val ink = sheet.ink
        val rowGap = sheet.px(ROW_GAP_DP)
        val rowHeight = (area.height() - rowGap * (rows - 1)) / rows
        // Columns are measured against the figures that will actually be shown,
        // so a mild day is not given room for "-00°".
        val figures = cells(sheet.context, snapshot, MAX_COLUMNS * rows)
            .map { formatTemperature(it.temperature, snapshot.preferences.temperature) }
        val metrics =
            narrowed(ink, area.width(), figures, Metrics.fit(ink, rowHeight / sheet.scale))
        val columns = columnsFor(ink, area.width(), figures, metrics)
        val cells = cells(sheet.context, snapshot, columns * rows)
        if (cells.isEmpty()) return

        cells.chunked(columns).forEachIndexed { row, chunk ->
            val top = area.top + row * (rowHeight + rowGap)
            val band = RectF(area.left, top, area.right, top + rowHeight)
            drawRow(sheet, band, chunk, columns, metrics, snapshot)
        }
    }

    private fun drawRow(
        sheet: Sheet,
        band: RectF,
        cells: List<Cell>,
        columns: Int,
        m: Metrics,
        snapshot: WidgetSnapshot,
    ) {
        val ink = sheet.ink
        val colors = sheet.colors
        val width = band.width() / columns
        val content = m.contentHeight(ink)
        val top = band.centerY() - content / 2f
        val floorY = top + content - sheet.px(m.padDp)

        cells.forEachIndexed { index, cell ->
            val column =
                RectF(
                    band.left + index * width,
                    band.top,
                    band.left + (index + 1) * width,
                    band.bottom,
                )
            val slot = RectF(column).apply { inset(sheet.px(COLUMN_INSET_DP), 0f) }

            if (cell.wet) {
                val wash = RectF(slot.left, top, slot.right, top + content)
                val corner = sheet.px(WASH_RADIUS_DP * m.k)
                ink.canvas.drawRoundRect(
                    wash,
                    corner,
                    corner,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = colors.textPrimary.copy(alpha = Emphasis.GHOST).toArgb()
                    },
                )
                ink.record("frame:wash", wash)
            }

            var y = top + sheet.px(m.padDp)
            val labelHeight = ink.lineHeight(m.labelDp)
            ink.text(
                text = cell.label,
                sizeDp = m.labelDp,
                // Wet hours in full ink, night hours dimmer.
                colour = when {
                    cell.wet -> colors.textPrimary
                    cell.isDay -> colors.textSecondary
                    else -> colors.textTertiary
                }.toArgb(),
                box = RectF(slot.left, y, slot.right, y + labelHeight),
                tracking = AXIS_TRACKING,
                what = "hour",
            )
            y += labelHeight + sheet.px(m.gapDp)

            val temperature = formatTemperature(cell.temperature, snapshot.preferences.temperature)
            val figureHeight = ink.lineHeight(m.figureDp)
            if (m.layout == Layout.INLINE) {
                // The mark beside the figure, centred together as one line.
                val size = sheet.px(m.glyphDp)
                val gap = sheet.px(INLINE_GAP_DP * m.k)
                val figureWidth = ink.measure(temperature, m.figureDp)
                val lineHeight = max(size, figureHeight)
                val left = slot.centerX() - (size + gap + figureWidth) / 2f
                val middle = y + lineHeight / 2f
                ink.glyph(
                    cell.condition,
                    cell.isDay,
                    RectF(left, middle - size / 2f, left + size, middle + size / 2f),
                )
                ink.text(
                    text = temperature,
                    sizeDp = m.figureDp,
                    colour = colors.textPrimary.toArgb(),
                    box = RectF(
                        left + size + gap,
                        middle - figureHeight / 2f,
                        left + size + gap + figureWidth,
                        middle + figureHeight / 2f,
                    ),
                    align = WidgetInk.Align.START,
                    what = "temperature",
                )
                return@forEachIndexed
            }

            if (m.glyphDp > 0f) {
                val size = sheet.px(m.glyphDp)
                ink.glyph(
                    cell.condition,
                    cell.isDay,
                    RectF(slot.centerX() - size / 2f, y, slot.centerX() + size / 2f, y + size),
                )
                y += size + sheet.px(m.gapDp)
            }

            ink.text(
                text = temperature,
                sizeDp = m.figureDp,
                colour = colors.textPrimary.toArgb(),
                box = RectF(slot.left, y, slot.right, y + figureHeight),
                what = "temperature",
            )
            y += figureHeight

            if (m.layout == Layout.BARS) {
                y += sheet.px(m.gapDp)
                drawBar(sheet, slot.centerX(), y, floorY, m, cell.rate)
            }
        }

        if (m.layout == Layout.BARS) {
            // One floor under the whole row for the bars to stand on.
            ink.canvas.drawRect(
                band.left,
                floorY,
                band.right,
                floorY + max(1f, sheet.px(HAIRLINE_DP)),
                Paint().apply { color = colors.hairline.toArgb() },
            )
        }
    }

    /**
     * One hour's rain as height on the app's shared rain axis. The track keeps
     * its full height so bars compare across hours; a dry hour draws nothing.
     */
    private fun drawBar(
        sheet: Sheet,
        centreX: Float,
        top: Float,
        floor: Float,
        m: Metrics,
        rate: Double?,
    ) {
        val fraction = RainCurveBands
            .spreadWithinLight(RainCurveBands.heightFraction((rate ?: 0.0).toFloat()))
            .coerceIn(0f, 1f)
        val half = sheet.px(BAR_WIDTH_DP * m.k) / 2f
        val track = RectF(centreX - half, top, centreX + half, floor)
        sheet.ink.record("bar", track)
        if (fraction <= 0f) return
        val barTop = floor - track.height() * fraction
        val path = Path().apply {
            addRoundRect(
                RectF(track.left, barTop, track.right, floor),
                floatArrayOf(half, half, half, half, 0f, 0f, 0f, 0f),
                Path.Direction.CW,
            )
        }
        sheet.ink.canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sheet.colors.textPrimary.toArgb() },
        )
    }

    /**
     * As many columns as fit, and at least three. A column is never narrower
     * than its widest time label, nor, in the inline layout, than a mark beside
     * the widest temperature.
     */
    private fun columnsFor(ink: WidgetInk, width: Float, figures: List<String>, m: Metrics): Int =
        floor(width / columnWidth(ink, figures, m)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    private fun columnWidth(ink: WidgetInk, figures: List<String>, m: Metrics): Float {
        val air = ink.px(LABEL_AIR_DP + COLUMN_INSET_DP * 2)
        val label = ink.measure(WIDEST_LABEL, m.labelDp, tracking = AXIS_TRACKING)
        val figure = figures.maxOfOrNull { ink.measure(it, m.figureDp) } ?: 0f
        val line = if (m.layout ==
            Layout.INLINE
        ) {
            ink.px(m.glyphDp + INLINE_GAP_DP * m.k) + figure
        } else {
            figure
        }
        return max(ink.px(COLUMN_MIN_DP * m.k), max(label, line) + air)
    }

    /**
     * [m], made small enough that the minimum number of columns fits across
     * [width]: smaller type first, and the marks given up only below the floor.
     */
    private fun narrowed(ink: WidgetInk, width: Float, figures: List<String>, m: Metrics): Metrics {
        fun fits(candidate: Metrics) = columnWidth(ink, figures, candidate) * MIN_COLUMNS <= width
        if (fits(m)) return m
        var k = m.k
        while (k >= LAST_K) {
            Metrics(m.layout, k).takeIf(::fits)?.let { return it }
            k -= K_STEP
        }
        return Metrics(Layout.BARE, LAST_K)
    }

    private enum class Layout {
        /** Time, mark, temperature and the rain bar, stacked. */
        BARS,

        /** Time, mark and temperature, stacked. */
        STACKED,

        /** Time above; mark and temperature side by side. Made for a one-row widget. */
        INLINE,

        /** Time and temperature only, when nothing else fits. */
        BARE,
    }

    /** Every size in a row, from the layout and a scale [k] on the app's sizes. */
    private class Metrics(val layout: Layout, val k: Float) {
        val labelDp = LABEL_DP * k
        val glyphDp = k * when (layout) {
            Layout.INLINE -> INLINE_GLYPH_DP
            Layout.BARE -> 0f
            else -> GLYPH_DP
        }
        val figureDp = FIGURE_DP * k
        val gapDp = GAP_DP * k
        val padDp = PAD_DP * k

        fun contentHeight(ink: WidgetInk): Float {
            val label = ink.lineHeight(labelDp) + ink.px(gapDp)
            val figure = ink.lineHeight(figureDp)
            val body = when (layout) {
                Layout.BARS -> ink.px(glyphDp + gapDp) + figure + ink.px(gapDp + BAR_DP * k)
                Layout.STACKED -> ink.px(glyphDp + gapDp) + figure
                Layout.INLINE -> max(ink.px(glyphDp), figure)
                Layout.BARE -> figure
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
                    // A one-row layout spends spare height on more hours, not larger type.
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
                return scaled(Layout.BARS, MIN_K)
                    ?: scaled(Layout.STACKED, MIN_K)
                    ?: scaled(Layout.INLINE, MIN_K)
                    ?: scaled(Layout.BARE, LAST_K)
                    ?: Metrics(Layout.BARE, LAST_K)
            }
        }
    }

    /** A stacked row at the app's sizes: the height at which another row of hours is added. */
    const val ROW_DP = 80f

    // The app's hour strip sizes (HourStrip.kt).
    private const val LABEL_DP = 11f
    private const val GLYPH_DP = 18f
    private const val FIGURE_DP = 15f
    private const val BAR_DP = 22f
    private const val GAP_DP = 4f
    private const val PAD_DP = 4f
    private const val BAR_WIDTH_DP = 10f
    private const val WASH_RADIUS_DP = 8f
    private const val AXIS_TRACKING = 0.01f
    private const val HAIRLINE_DP = 1f

    /** Beside the figure the mark matches its height rather than standing over it. */
    private const val INLINE_GLYPH_DP = 16f
    private const val INLINE_GAP_DP = 3f

    /** How far a row may shrink and grow from those sizes; the bare layout may go a little further. */
    private const val MIN_K = 0.8f
    private const val LAST_K = 0.7f
    private const val MAX_K = 1.25f
    private const val K_STEP = 0.02f

    private const val COLUMN_MIN_DP = 44f
    private const val COLUMN_INSET_DP = 2f
    private const val LABEL_AIR_DP = 4f
    private const val WIDEST_LABEL = "00:00"
    private const val MIN_COLUMNS = 3
    private const val MAX_COLUMNS = 25

    private const val ROW_GAP_DP = 6f
}
