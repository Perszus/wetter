// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.hourCovering
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.onDay
import lv.bolwarra.wetter.ui.components.ConditionGlyph
import lv.bolwarra.wetter.ui.components.HairlineRule
import lv.bolwarra.wetter.ui.components.HourStrip
import lv.bolwarra.wetter.ui.components.Reveal
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.formatMillimetresWithUnit
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatWeekdayShort
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The week: what each day is, and how warm. Four things per row: the day, the sky
 * as a mark, and the two ends of its temperature, so the wet day is found down a
 * column at a glance.
 *
 * A day opens into its own hours, where the rain rate and the shape of the night
 * live. One at a time: opening a second closes the first.
 */
@Composable
fun WeekPage(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val units = WetterTheme.units
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone
    val today = now.atZone(zone).toLocalDate()
    val days = forecast.daily.filter { !it.date.isBefore(today) }.take(DAYS_SHOWN)

    if (days.isEmpty()) return

    // The row covering now, not the clock's hour, which differs in half-hour zones
    // (see hourCovering).
    val thisHour = forecast.hourly.hourCovering(now)

    // The open day, held as its date: the list re-forms at midnight and an index
    // would point at a different day.
    var openDay by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth()) {
        Tile(
            label = stringResource(R.string.tile_week_rain),
            trailing = formatMillimetresWithUnit(
                days.sumOf {
                    it.precipitationTotal ?: 0.0
                },
                units.precipitation,
            ),
        ) {
            days.forEachIndexed { index, day ->
                if (index > 0) {
                    Spacer(Modifier.height(spacing.s))
                    HairlineRule()
                    Spacer(Modifier.height(spacing.s))
                }
                DayRow(
                    day = day,
                    isToday = day.date == today,
                    // Today's drawer opens at the current hour: hours already past are not worth
                    // scrolling through.
                    hours = forecast.hourly
                        .onDay(day.date, zone)
                        .filter { !it.timestamp.isBefore(thisHour) },
                    zone = zone,
                    open = openDay == day.date.toString(),
                    onToggle = {
                        openDay = day.date.toString().takeIf { it != openDay }
                    },
                )
            }
        }
    }
}

@Composable
private fun DayRow(
    day: DailyWeather,
    isToday: Boolean,
    hours: List<HourlyWeather>,
    zone: ZoneId,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    val dayLabel = when {
        isToday -> stringResource(R.string.week_today)
        else -> formatWeekdayShort(day.date)
    }
    val turn by animateFloatAsState(
        targetValue = if (open) HALF_TURN else 0f,
        animationSpec = Reveal.chevron,
        label = "day chevron",
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                // The padding is inside the clickable, so the gap between two days belongs to one
                // of them.
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayLabel,
                style = WetterTheme.type.title,
                // Today is the only row at full ink.
                color = if (isToday) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.width(DAY_COLUMN),
            )

            ConditionGlyph(
                // The sky named for what the temperature says reaches the ground, as on the
                // dial.
                condition = day.appearance,
                // A drawn mark says nothing to a screen reader, so it carries the word it was
                // made from.
                contentDescription = stringResource(day.appearance.labelRes()),
            )

            // The gap separates what the day is from how warm it is.
            Spacer(Modifier.weight(1f))

            // A range, not two readings: one string, so the dash gets equal air on both sides,
            // with the high and low in their own colours.
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.textTertiary)) {
                        append(formatTemperature(day.temperatureMin, units.temperature))
                    }
                    withStyle(SpanStyle(color = colors.textDisabled)) {
                        append(RANGE)
                    }
                    withStyle(SpanStyle(color = colors.textPrimary)) {
                        append(formatTemperature(day.temperatureMax, units.temperature))
                    }
                },
                style = WetterTheme.type.figure,
                textAlign = TextAlign.End,
                modifier = Modifier.width(RANGE_COLUMN),
            )

            // Faint and small: the most repeated mark on the page, only saying the row can be
            // opened.
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier
                    .padding(start = spacing.xs)
                    .size(CHEVRON)
                    .rotate(turn),
            )
        }

        AnimatedVisibility(visible = open, enter = Reveal.enter, exit = Reveal.exit) {
            Column {
                Spacer(Modifier.height(spacing.s))
                HourStrip(hours = hours, zone = zone)
                Spacer(Modifier.height(spacing.xs))
            }
        }
    }
}

private const val DAYS_SHOWN = 7

/**
 * An en dash with a hair of air either side, travelling with the dash inside one
 * string.
 */
private const val RANGE = " – "

private const val HALF_TURN = 180f

private val DAY_COLUMN = 52.dp

/** Wide enough for "-15 – -5". */
private val RANGE_COLUMN = 96.dp
private val CHEVRON = 18.dp
