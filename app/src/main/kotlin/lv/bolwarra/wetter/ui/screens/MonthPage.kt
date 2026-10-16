// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.climate.ClimateNormals
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.climate.DayNormal
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.components.ConditionGlyph
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatWeekdayInitial
import lv.bolwarra.wetter.ui.theme.Emphasis
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The month: thirty days as a calendar. A grid, because the questions are about
 * position (which weekend, is that Thursday clear), answered by weekday columns.
 *
 * No service forecasts a month, so past the forecast's reach each square carries
 * a ten-year normal: the median high and how often that date has been wet. Normal
 * squares have no condition mark and a lighter ink; the missing glyph is what
 * tells them apart.
 *
 * Each square asks for a forecast and falls back to a normal only if there is
 * none, so the boundary moves forward by itself and nothing is stored that could
 * go stale. The grid is always thirty days, so it reads the same every morning.
 */
@Composable
fun MonthPage(
    forecast: WeatherForecast,
    now: Instant,
    climatology: Climatology,
    modifier: Modifier = Modifier,
) {
    val zone = forecast.location.zone
    val today = now.atZone(zone).toLocalDate()
    val last = today.plusDays(SPAN_DAYS - 1)

    val days = forecast.daily.associateBy { it.date }

    // Whole weeks, in the reader's own week order, like the calendar on their wall.
    val weekStart = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val gridStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
    val gridEnd = last.with(TemporalAdjusters.nextOrSame(weekStart.minus(1)))

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WetterTheme.spacing.m),
    ) {
        Tile(label = stringResource(R.string.tile_month)) {
            Row(Modifier.fillMaxWidth()) {
                repeat(DAYS_IN_WEEK) { index ->
                    Text(
                        text = formatWeekdayInitial(weekStart.plus(index.toLong())),
                        style = WetterTheme.type.axis,
                        color = WetterTheme.colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(WetterTheme.spacing.s))

            var week = gridStart
            while (!week.isAfter(gridEnd)) {
                Row(Modifier.fillMaxWidth()) {
                    repeat(DAYS_IN_WEEK) { index ->
                        val date = week.plusDays(index.toLong())
                        val withinSpan = !date.isBefore(today) && !date.isAfter(last)
                        val day = days[date]
                        DayCell(
                            date = date,
                            day = day,
                            // Only where there is no forecast, so the boundary rolls forward by
                            // itself.
                            normal = if (withinSpan && day == null) {
                                climatology.at(date)
                            } else {
                                null
                            },
                            isToday = date == today,
                            withinSpan = withinSpan,
                        )
                    }
                }
                Spacer(Modifier.height(WetterTheme.spacing.xs))
                week = week.plusWeeks(1)
            }
        }
    }
}

/**
 * One square: which day, what the sky does, how warm. No low: at this width it
 * would halve both numbers.
 */
@Composable
private fun RowScope.DayCell(
    date: LocalDate,
    day: DailyWeather?,
    normal: DayNormal?,
    isToday: Boolean,
    withinSpan: Boolean,
) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors
    val wet = when {
        // A day's total, judged as a total against the conventional rain-day line, the
        // same one the normals use, so both halves of the grid answer the same question.
        day != null -> (day.precipitationTotal ?: 0.0) >= ClimateNormals.WET_DAY_MM
        // A normal is washed when the date has been wet more often than not.
        normal != null -> normal.wetShare >= USUALLY
        else -> false
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .height(CELL_HEIGHT)
            .padding(horizontal = CELL_GAP)
            .clip(RoundedCornerShape(CELL_RADIUS))
            // A wet day is washed, as a wet hour is in the week's strip, so the wet days read
            // off the month at a glance.
            .background(
                if (wet) colors.textPrimary.copy(alpha = Emphasis.GHOST) else Color.Transparent,
            )
            // Today is outlined rather than filled, so it can be today and wet at once.
            .then(
                if (isToday) {
                    Modifier.border(
                        width = TODAY_RING,
                        color = colors.textTertiary,
                        shape = RoundedCornerShape(CELL_RADIUS),
                    )
                } else {
                    Modifier
                },
            )
            .padding(vertical = CELL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = WetterTheme.type.axis,
            color = when {
                !withinSpan || day == null -> colors.textDisabled
                isToday -> colors.textPrimary
                else -> colors.textTertiary
            },
            textAlign = TextAlign.Center,
        )

        if (!withinSpan) return@Column

        when {
            day != null -> {
                Spacer(Modifier.height(CELL_GAP))
                ConditionGlyph(
                    condition = day.appearance,
                    contentDescription = null,
                    size = CELL_GLYPH,
                )
                Spacer(Modifier.height(CELL_GAP))
                Text(
                    text = formatTemperature(day.temperatureMax, units.temperature),
                    style = WetterTheme.type.meta,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
            }

            normal?.medianHigh != null -> {
                // No condition mark: the visible gap says this is a different kind of statement.
                // Ten Septembers do not agree on a sky.
                Spacer(Modifier.height(CELL_GLYPH + CELL_GAP * 2))
                Text(
                    text = formatTemperature(normal.medianHigh, units.temperature),
                    style = WetterTheme.type.meta,
                    // A rank lighter than a forecast's, so the two never read as equally good
                    // answers.
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }

            // Nothing else, where there is nothing else to say.
            else -> return@Column
        }
    }
}

/** Thirty days, drawn whether or not they can all be filled. */
private const val SPAN_DAYS = 30L

private const val DAYS_IN_WEEK = 7

/** More than half the past years wet, which is what "usually" has to mean. */
private const val USUALLY = 0.5

private val CELL_HEIGHT = 62.dp
private val CELL_GLYPH = 18.dp
private val CELL_RADIUS = 8.dp
private val CELL_GAP = 2.dp
private val CELL_PADDING = 5.dp
private val TODAY_RING = 1.dp
