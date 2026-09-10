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
import java.time.temporal.ChronoUnit
import lv.bolwarra.wetter.R
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
 * The week: what each day is, and how warm.
 *
 * Four things per row and nothing else: the day, the sky as a mark, and the two
 * ends of its temperature.
 *
 * ### What the millimetres were doing here, and why they went
 *
 * The rain column used to give each day a total in millimetres with its peak
 * probability underneath. Both were true and neither was read. A day is not
 * decided from `4.2 mm` — that number has no feel to it at all, which is the
 * same finding that fixed the rain chart's vertical axis — and a week is skimmed
 * rather than studied. What somebody wants off this page is *which day is the
 * wet one*, and a mark answers that down a column faster than seven numbers can.
 *
 * Anyone who wants the rate goes to Today, where it is drawn against a scale
 * instead of stated.
 *
 * ### And the range bars
 *
 * Seven bars on one shared scale were a real picture of the week's shape, and
 * they cost the row its whole middle to say something the two numbers at the end
 * already said. Once the sky became a mark the row could be read across in one
 * movement, and the bars were the thing standing in the way.
 *
 * ### Nothing under the rows
 *
 * There was a summary tile below them — wet days, the wettest, warmest, coldest.
 * Every figure in it was already on the page it sat under, one scroll up, said
 * more precisely: "four of seven wet" is the four rows with a mark on them, and
 * "warmest 19°" is the largest number in a column of seven. A tile that counts
 * what is already drawn is asking the reader to trust an arithmetic they can do
 * by looking.
 *
 * ### The detail is behind the row, not on it
 *
 * A day opens into its own hours. That is where the rain rate, the sky at four
 * in the afternoon and the shape of the night live now — asked for, rather than
 * spread across seven rows on the chance that somebody wanted one of them.
 *
 * One at a time, deliberately. Seven open drawers is a page nobody can hold in
 * their head, and opening a second one is a clearer way of saying "I am done
 * with the first" than closing it by hand.
 */
@Composable
fun WeekPage(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val units = WetterTheme.units
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone
    val today = now.atZone(zone).toLocalDate()
    val days = forecast.daily.filter { !it.date.isBefore(today) }.take(DAYS_SHOWN)

    if (days.isEmpty()) return

    val thisHour = now.truncatedTo(ChronoUnit.HOURS)

    // The open day, held as its own date rather than as a row number: the list
    // re-forms at midnight and an index would then point at a different day than
    // the one that was opened.
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
                    // Today's drawer opens at the current hour, not at midnight.
                    // The rest of the row is a summary of the whole day and
                    // rightly includes the morning that has been; an hour-by-hour
                    // forecast of hours that have already happened is just a
                    // stretch of strip to scroll past before reaching anything
                    // anybody can act on.
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
                // The tap target is the row, so the padding has to be inside the
                // clickable rather than around it - otherwise the gap between two
                // days belongs to neither and a near miss does nothing.
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayLabel,
                style = WetterTheme.type.title,
                // Today is the only row set at full ink. It is where you are.
                color = if (isToday) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.width(DAY_COLUMN),
            )

            ConditionGlyph(
                // The sky named for what the temperature says would actually
                // reach the ground, not the provider's word for it - the same
                // rule the dial follows, so a freezing day cannot say rain in
                // one place and snow in another.
                condition = day.appearance,
                // A drawn mark says nothing to a screen reader, so it carries
                // the word the mark was made from.
                contentDescription = stringResource(day.appearance.labelRes()),
            )

            // The row is read across, not down, so the gap belongs in the middle
            // where it separates the two halves of the sentence: what the day is,
            // and how warm it is.
            Spacer(Modifier.weight(1f))

            // A range, not two readings.
            //
            // Set apart by a gap alone they read as two columns of a table, and
            // the eye has to be told which is which. A dash makes them one span
            // of temperature, which is the thing a day actually has.
            //
            // One piece of text rather than three, because three could not be
            // spaced properly: each number right-aligned in its own column put a
            // single space before the dash and a wide one after it, which is a
            // range with a limp. Set as one string the dash gets the same air on
            // both sides, and the two ends still take their own colour — the
            // high is what the day is remembered by and the low is context for
            // it.
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

            // Kept faint and small. Seven of these down the right edge is the
            // most repeated mark on the page, and the only job it has is to say
            // the row can be opened.
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
 * An en dash with a hair of air either side, which is how a range is set.
 *
 * The spaces are part of the mark rather than layout: they have to travel with
 * the dash inside one string, which is the whole point of setting it as one.
 */
private const val RANGE = " – "

private const val HALF_TURN = 180f

private val DAY_COLUMN = 52.dp

/** Wide enough for "-15 – -5", which is a real week here in February. */
private val RANGE_COLUMN = 96.dp
private val CHEVRON = 18.dp
