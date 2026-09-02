package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.PrecipitationSpell
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.nextPrecipitation
import lv.bolwarra.wetter.domain.onDay
import lv.bolwarra.wetter.domain.totalPrecipitation
import lv.bolwarra.wetter.ui.components.Metric
import lv.bolwarra.wetter.ui.components.MetricGrid
import lv.bolwarra.wetter.ui.components.RainTimeline
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.NO_READING
import lv.bolwarra.wetter.ui.format.formatDuration
import lv.bolwarra.wetter.ui.format.formatMillimetresWithUnit
import lv.bolwarra.wetter.ui.format.formatPercent
import lv.bolwarra.wetter.ui.format.formatPressure
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.format.formatWeekday
import lv.bolwarra.wetter.ui.format.formatWindSpeed
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Today: what the rest of this day does.
 *
 * The rain timeline is first and largest because it is the reason the app
 * exists. Everything under it is context for reading it.
 */
@Composable
fun TodayPage(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val zone = forecast.location.zone
    val spacing = WetterTheme.spacing
    val today = now.atZone(zone).toLocalDate()
    val hoursToday = forecast.hourly.onDay(today, zone)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (hoursToday.isNotEmpty()) {
            Tile(
                label = stringResource(R.string.tile_rain),
                trailing = formatMillimetresWithUnit(hoursToday.totalPrecipitation() ?: 0.0),
            ) {
                RainTimeline(hours = hoursToday, now = now, zone = zone)
            }
        }

        NextRainTile(forecast, now)

        DaylightTile(forecast, now)

        AirTile(forecast)
    }
}

/**
 * When it next rains, and for how long.
 *
 * The timeline shows today; this looks across the whole forecast, because "not
 * today" is only half an answer and the other half is usually the one somebody
 * wanted.
 */
@Composable
private fun NextRainTile(forecast: WeatherForecast, now: Instant) {
    val zone = forecast.location.zone
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val spell = forecast.hourly.nextPrecipitation(now)

    Tile(
        label = stringResource(R.string.tile_next_rain),
        trailing = spell?.let { formatMillimetresWithUnit(it.totalMillimetres) },
    ) {
        if (spell == null) {
            Text(
                text = stringResource(R.string.next_rain_none),
                style = WetterTheme.type.headline,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = stringResource(R.string.next_rain_none_detail),
                style = WetterTheme.type.meta,
                color = colors.textTertiary,
            )
            return@Tile
        }

        val falling = !spell.start.isAfter(now)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(spacing.s)
                    .clip(CircleShape)
                    .background(colors.precipitation),
            )
            Spacer(Modifier.width(spacing.s))
            Text(
                text = if (falling) {
                    stringResource(
                        R.string.next_rain_falling,
                        stringResource(spell.kind.labelRes()),
                    )
                } else {
                    stringResource(
                        R.string.next_rain_at,
                        stringResource(spell.kind.labelRes()),
                        whenLabel(spell, now, forecast),
                        formatTime(spell.start, zone),
                    )
                },
                style = WetterTheme.type.headline,
                color = colors.textPrimary,
            )
        }

        Spacer(Modifier.height(spacing.s))
        Text(
            text = if (spell.isOpenEnded) {
                // The forecast stops here; the shower may not. Saying it ends at
                // the edge of the data would be inventing an ending.
                stringResource(R.string.next_rain_continues, formatTime(spell.end, zone))
            } else {
                stringResource(
                    R.string.next_rain_until,
                    formatTime(spell.end, zone),
                    formatDuration(spell.duration),
                )
            },
            style = WetterTheme.type.body,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun whenLabel(spell: PrecipitationSpell, now: Instant, forecast: WeatherForecast): String {
    val zone = forecast.location.zone
    val startDate = spell.start.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (startDate) {
        today -> stringResource(R.string.relative_today)
        today.plusDays(1) -> stringResource(R.string.relative_tomorrow)
        else -> formatWeekday(startDate)
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
                    formatTemperature(forecast.current.apparentTemperature),
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
