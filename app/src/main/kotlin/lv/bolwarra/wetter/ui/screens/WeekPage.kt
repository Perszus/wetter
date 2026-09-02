package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.components.HairlineRule
import lv.bolwarra.wetter.ui.components.Metric
import lv.bolwarra.wetter.ui.components.MetricGrid
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.formatMillimetresWithUnit
import lv.bolwarra.wetter.ui.format.formatPercent
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatWeekday
import lv.bolwarra.wetter.ui.format.formatWeekdayShort
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The week: which days are wet, and how warm.
 *
 * A row per day, with the temperature ranges drawn against one shared scale so
 * the column can be read down as a shape rather than as seven pairs of numbers.
 * That shared scale is the whole point — ranges scaled individually would make
 * every day look the same length.
 */
@Composable
fun WeekPage(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone
    val today = now.atZone(zone).toLocalDate()
    val days = forecast.daily.filter { !it.date.isBefore(today) }.take(DAYS_SHOWN)

    if (days.isEmpty()) return

    val coldest = days.minOf { it.temperatureMin }
    val warmest = days.maxOf { it.temperatureMax }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        Tile(
            label = stringResource(R.string.tile_week_rain),
            trailing = formatMillimetresWithUnit(days.sumOf { it.precipitationTotal ?: 0.0 }),
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
                    coldest = coldest,
                    warmest = warmest,
                )
            }
        }

        WeekSummaryTile(days)
    }
}

@Composable
private fun DayRow(day: DailyWeather, isToday: Boolean, coldest: Double, warmest: Double) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val wet = PrecipitationIntensity.ofRate(day.precipitationTotal).isWet

    val dayLabel = when {
        isToday -> stringResource(R.string.week_today)
        else -> formatWeekdayShort(day.date)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dayLabel,
            style = WetterTheme.type.title,
            // Today is the only row set at full ink. It is where you are.
            color = if (isToday) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.width(DAY_COLUMN),
        )

        Column(Modifier.width(RAIN_COLUMN)) {
            Text(
                text = if (wet) formatMillimetresWithUnit(day.precipitationTotal) else DRY,
                style = WetterTheme.type.figure,
                color = if (wet) colors.precipitation else colors.textTertiary,
            )
            if (wet && day.precipitationProbabilityMax != null) {
                Text(
                    text = formatPercent(day.precipitationProbabilityMax),
                    style = WetterTheme.type.meta,
                    color = colors.textTertiary,
                )
            }
        }

        Text(
            text = formatTemperature(day.temperatureMin),
            style = WetterTheme.type.figure,
            color = colors.textTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(TEMP_COLUMN),
        )
        Spacer(Modifier.width(spacing.s))
        TemperatureRangeBar(
            day = day,
            coldest = coldest,
            warmest = warmest,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(spacing.s))
        Text(
            text = formatTemperature(day.temperatureMax),
            style = WetterTheme.type.figure,
            color = colors.textPrimary,
            modifier = Modifier.width(TEMP_COLUMN),
        )
    }
}

/**
 * One day's temperature range, positioned on the week's scale.
 *
 * The gradient runs cool to warm across the bar so a cold night and a mild
 * afternoon are visibly different ends of the same day, rather than a flat block
 * whose colour would have to mean the average of the two.
 */
@Composable
private fun TemperatureRangeBar(
    day: DailyWeather,
    coldest: Double,
    warmest: Double,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val span = (warmest - coldest).takeIf { it > 0.1 } ?: 1.0

    val leadingFraction = ((day.temperatureMin - coldest) / span).toFloat().coerceIn(0f, 1f)
    val barFraction = ((day.temperatureMax - day.temperatureMin) / span).toFloat().coerceIn(0f, 1f)
    val trailingFraction = (1f - leadingFraction - barFraction).coerceAtLeast(0f)

    Box(
        modifier
            .height(BAR_HEIGHT)
            .clip(RoundedCornerShape(BAR_HEIGHT / 2))
            .background(colors.surfaceSunken),
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            // Weights must be positive, so a day sitting exactly at an end of the
            // week's range gets a hairline of space rather than a crash.
            Spacer(Modifier.weight(leadingFraction.coerceAtLeast(EPSILON)))
            Box(
                Modifier
                    .weight(barFraction.coerceAtLeast(MIN_BAR))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(BAR_HEIGHT / 2))
                    .background(
                        Brush.horizontalGradient(
                            listOf(colors.temperatureCool, colors.temperatureWarm),
                        ),
                    ),
            )
            Spacer(Modifier.weight(trailingFraction.coerceAtLeast(EPSILON)))
        }
    }
}

/** The two facts worth taking from a week at a glance. */
@Composable
private fun WeekSummaryTile(days: List<DailyWeather>) {
    val wetDays = days.count { PrecipitationIntensity.ofRate(it.precipitationTotal).isWet }
    val wettest = days.maxByOrNull { it.precipitationTotal ?: 0.0 }
    val wettestIsWet = wettest != null &&
        PrecipitationIntensity.ofRate(wettest.precipitationTotal).isWet

    Tile(label = stringResource(R.string.tile_week_summary)) {
        MetricGrid(
            listOf(
                Metric(
                    stringResource(R.string.metric_wet_days),
                    stringResource(R.string.week_days_of, wetDays, days.size),
                ),
                Metric(
                    stringResource(R.string.metric_wettest_day),
                    if (wettestIsWet) formatWeekday(wettest.date) else DRY,
                ),
                Metric(
                    stringResource(R.string.metric_warmest),
                    formatTemperature(days.maxOf { it.temperatureMax }),
                ),
                Metric(
                    stringResource(R.string.metric_coldest),
                    formatTemperature(days.minOf { it.temperatureMin }),
                ),
            ),
        )
    }
}

private const val DAYS_SHOWN = 7
private const val DRY = "—"
private const val EPSILON = 0.0001f

/** A range of nearly nothing still needs to be visible as a mark. */
private const val MIN_BAR = 0.04f

private val DAY_COLUMN = 52.dp
private val RAIN_COLUMN = 68.dp
private val TEMP_COLUMN = 34.dp
private val BAR_HEIGHT = 6.dp
