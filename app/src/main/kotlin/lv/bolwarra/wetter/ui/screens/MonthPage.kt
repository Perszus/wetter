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
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.climate.DayNormal
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.components.ConditionGlyph
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatWeekdayInitial
import lv.bolwarra.wetter.ui.theme.Emphasis
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The month: thirty days as a calendar, and the honest edge of what is known.
 *
 * A grid rather than a list, because the question this page answers is about
 * *position* — which weekend, how far after payday, is that Thursday clear — and
 * a list of thirty rows makes you count. Weekday columns answer it without
 * reading anything.
 *
 * ### Two kinds of square, and the line between them moves every day
 *
 * No service forecasts a month. Open-Meteo stops at sixteen days, MET Norway at
 * nine, and past a fortnight the ensembles that reach further stop saying
 * anything: measured at Rīga, day 33 of a 31-member run had two fifths of its
 * members wet — the base rate for October there, which is what "no information"
 * looks like in a probability — and 16.8 °C between its warmest and coldest
 * member. There is no sky to draw for a day the models cannot agree is 2 °C or
 * 19 °C.
 *
 * What there is, for a day that far out, is what that date has actually done
 * before. So the far squares carry a ten-year normal — the median high, and how
 * often that date has been wet.
 *
 * They are told apart by how they are drawn and by nothing else: no condition
 * mark where every forecast square has one, and a rank lighter in the ink. There
 * was a line under the grid explaining the change, and it is gone — a caption
 * telling a reader what to see in a picture is an admission that the picture is
 * not saying it, and this one does. The gap where a glyph should be is the
 * loudest thing in the square.
 *
 * **The boundary is worked out here, on every draw, and stored nowhere.** Each
 * square asks for a forecast and falls back to a normal only if there is none.
 * So the morning the forecast first reaches the 3rd of October, that square
 * stops being a normal and becomes a forecast by itself, with nothing to
 * invalidate and nothing that can be left stale. Writing a normal into the
 * forecast would need exactly that invalidation, and would be wrong the first
 * time it was missed.
 *
 * ### The grid is thirty days either way
 *
 * Sizing it to the data would make this a different page every morning, and a
 * different page each time a provider changed its reach — the same reason the
 * rain chart's axis is fixed and draws its whole scale on a dry day. A calendar
 * you have to re-measure before you can read it is not a calendar.
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

    // Whole weeks, in the reader's own week order: the calendar has to look like
    // the one on their wall, and where the week starts is not the same question
    // everywhere.
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
                            // Asked for only where there is no forecast, which
                            // is what makes the boundary roll forward on its
                            // own as the forecast reaches another day out.
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
 * One square of the calendar.
 *
 * The three things that fit, in the order they are wanted: which day, what the
 * sky does, how warm. The low is not here — at this width it would halve the
 * size of both numbers to add a figure nobody plans a fortnight around.
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
        day != null -> PrecipitationIntensity.ofRate(day.precipitationTotal).isWet
        // A normal is washed when the date has been wet more often than not. A
        // share of past years is not a forecast and is not drawn as one, but
        // "usually wet" is a fair thing for a square to say at a glance.
        normal != null -> normal.wetShare >= USUALLY
        else -> false
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .height(CELL_HEIGHT)
            .padding(horizontal = CELL_GAP)
            .clip(RoundedCornerShape(CELL_RADIUS))
            // A wet day is washed, exactly as a wet hour is in the week's strip.
            // Down a month it is the only mark that can be read without stopping
            // on anything, and reading the wet days off a month at a glance is
            // the whole reason this page is a grid.
            .background(
                if (wet) colors.textPrimary.copy(alpha = Emphasis.GHOST) else Color.Transparent,
            )
            // Today is outlined rather than filled, so it can be today *and* wet
            // without the two marks having to fight over one background.
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
                // No condition mark, and the gap it leaves is the point: the
                // square is visibly missing the thing every forecast square has,
                // which says "a different kind of statement" without a word or a
                // legend. Ten Septembers do not agree on a sky, and drawing the
                // commonest of them would be a picture of nothing.
                Spacer(Modifier.height(CELL_GLYPH + CELL_GAP * 2))
                Text(
                    text = formatTemperature(normal.medianHigh, units.temperature),
                    style = WetterTheme.type.meta,
                    // A rank lighter than a forecast's, so the two can never
                    // read as equally good answers to the same question.
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }

            // Nothing else, where there is nothing else to say. An empty square
            // is the correct drawing of a day nobody has anything on.
            else -> return@Column
        }
    }
}

/**
 * Thirty days, asked for and drawn whether or not they can all be filled.
 *
 * See the note on [MonthPage]: the grid is a fixed size on purpose.
 */
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
