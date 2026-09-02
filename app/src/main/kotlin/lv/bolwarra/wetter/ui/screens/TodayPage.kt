package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.PrecipitationSpell
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.nextPrecipitation
import lv.bolwarra.wetter.domain.totalPrecipitation
import lv.bolwarra.wetter.domain.window
import lv.bolwarra.wetter.ui.components.Metric
import lv.bolwarra.wetter.ui.components.MetricGrid
import lv.bolwarra.wetter.ui.components.RainCurve
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.NO_READING
import lv.bolwarra.wetter.ui.format.formatDuration
import lv.bolwarra.wetter.ui.format.formatMillimetresWithUnit
import lv.bolwarra.wetter.ui.format.formatPercent
import lv.bolwarra.wetter.ui.format.formatPressure
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.format.formatWeekday
import lv.bolwarra.wetter.ui.format.formatWindSpeed
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Today: what the next few hours do.
 *
 * The rain curve is first and largest because it is the reason the app exists.
 * Everything under it is context for reading it.
 */
@Composable
fun TodayPage(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val zone = forecast.location.zone
    val spacing = WetterTheme.spacing
    val ahead = forecast.hourly.window(now, TIMELINE_HOURS)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (ahead.isNotEmpty()) {
            Tile(
                label = stringResource(R.string.tile_rain),
                trailing = formatMillimetresWithUnit(ahead.totalPrecipitation() ?: 0.0),
            ) {
                RainCurve(hours = ahead, zone = zone)
                Spacer(Modifier.height(spacing.m))
                NextRainBar(forecast, now)
            }
        }

        DaylightTile(forecast, now)
        AirTile(forecast)
    }
}

/**
 * When it next rains, in one line.
 *
 * It sits under the curve because it answers the question the curve cannot: the
 * chart covers the next six hours, and "nothing in the next six hours" is only
 * half an answer. The other half is usually the one somebody wanted, and it is
 * short enough to be a bar rather than a card of its own.
 */
@Composable
private fun NextRainBar(forecast: WeatherForecast, now: Instant) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone
    val spell = forecast.hourly.nextPrecipitation(now)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.surfaceSunken)
            .padding(horizontal = spacing.l, vertical = spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(DOT)
                .clip(CircleShape)
                .background(if (spell == null) colors.textTertiary else colors.precipitation),
        )
        Spacer(Modifier.width(spacing.m))
        Text(
            text = spell.describe(now, forecast),
            style = WetterTheme.type.body,
            color = if (spell == null) colors.textTertiary else colors.textPrimary,
        )
    }
}

/** One line, in the fewest words that are still true. */
@Composable
private fun PrecipitationSpell?.describe(now: Instant, forecast: WeatherForecast): String {
    val zone = forecast.location.zone
    if (this == null) return stringResource(R.string.next_rain_none)

    val kind = stringResource(kind.labelRes())
    if (!start.isAfter(now)) {
        // Already falling. When it stops is the only thing left to say, and if
        // the forecast runs out first, nobody can honestly say.
        return if (isOpenEnded) {
            stringResource(R.string.next_rain_falling, kind)
        } else {
            stringResource(R.string.next_rain_until, kind, formatTime(end, zone))
        }
    }

    val startsToday = start.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    return if (startsToday) {
        stringResource(R.string.next_rain_starts, kind, formatTime(start, zone))
    } else {
        stringResource(
            R.string.next_rain_starts_on,
            kind,
            formatWeekday(start.atZone(zone).toLocalDate()),
            formatTime(start, zone),
        )
    }
}

/** Sunrise, sunset, and how much day there is between them. */
@Composable
private fun DaylightTile(forecast: WeatherForecast, now: Instant) {
    val zone = forecast.location.zone
    val today = now.atZone(zone).toLocalDate()
    val day = forecast.daily.firstOrNull { it.date == today } ?: return
    val sunrise = day.sunrise
    val sunset = day.sunset

    Tile(label = stringResource(R.string.tile_daylight)) {
        MetricGrid(
            listOf(
                Metric(stringResource(R.string.metric_sunrise), formatTime(sunrise, zone)),
                Metric(stringResource(R.string.metric_sunset), formatTime(sunset, zone)),
                Metric(
                    stringResource(R.string.metric_day_length),
                    if (sunrise != null && sunset != null) {
                        formatDuration(Duration.between(sunrise, sunset))
                    } else {
                        // Above the arctic circle there is no sunrise to report,
                        // and a zero would be the wrong kind of wrong.
                        NO_READING
                    },
                ),
                Metric(
                    stringResource(R.string.metric_feels_like),
                    lv.bolwarra.wetter.ui.format.formatTemperature(
                        forecast.current.apparentTemperature,
                    ),
                ),
            ),
        )
    }
}

/** The secondary readings — true, occasionally useful, never the headline. */
@Composable
private fun AirTile(forecast: WeatherForecast) {
    val current = forecast.current
    Tile(label = stringResource(R.string.tile_air)) {
        MetricGrid(
            listOf(
                Metric(stringResource(R.string.metric_wind), formatWindSpeed(current.windSpeed)),
                Metric(stringResource(R.string.metric_humidity), formatPercent(current.humidity)),
                Metric(stringResource(R.string.metric_pressure), formatPressure(current.pressure)),
                Metric(
                    stringResource(R.string.metric_cloud),
                    formatPercent(forecast.hourly.firstOrNull()?.cloudCover),
                ),
            ),
        )
    }
}

private val DOT = 7.dp

/**
 * The next six hours, rolling from the current hour.
 *
 * Short on purpose. This is the window in which a forecast is worth acting on
 * and in which it is most likely to be right; a day of it flattened the part
 * anybody was going to use into an eighth of the width. What happens later is
 * the Week page's job, and when it next rains at all is the bar underneath.
 */
private const val TIMELINE_HOURS = 6L
