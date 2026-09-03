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
import kotlin.math.roundToInt
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.CompassPoint
import lv.bolwarra.wetter.domain.MoonPhase
import lv.bolwarra.wetter.domain.Psychrometrics
import lv.bolwarra.wetter.domain.at
import lv.bolwarra.wetter.domain.conditionsAt
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.PrecipitationSpell
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.nextPrecipitation
import lv.bolwarra.wetter.domain.totalPrecipitation
import lv.bolwarra.wetter.domain.verification.LearnedBias
import lv.bolwarra.wetter.domain.window
import lv.bolwarra.wetter.ui.components.ExpandableTile
import lv.bolwarra.wetter.ui.components.Metric
import lv.bolwarra.wetter.ui.components.MetricGrid
import lv.bolwarra.wetter.ui.components.RainCurve
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.DayDistance
import lv.bolwarra.wetter.ui.format.NO_READING
import lv.bolwarra.wetter.ui.format.dayDistance
import lv.bolwarra.wetter.ui.format.formatDayAndMonth
import lv.bolwarra.wetter.ui.format.formatDuration
import lv.bolwarra.wetter.ui.format.formatMillimetresWithUnit
import lv.bolwarra.wetter.ui.format.formatPercent
import lv.bolwarra.wetter.ui.format.formatPressure
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatTemperatureDelta
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
fun TodayPage(
    forecast: WeatherForecast,
    now: Instant,
    timeline: List<FusedPrecipitation> = emptyList(),
    bias: LearnedBias? = null,
    modifier: Modifier = Modifier,
) {
    val zone = forecast.location.zone
    val spacing = WetterTheme.spacing
    val span = Duration.ofHours(TIMELINE_HOURS)
    // One hour more than is drawn. The window rolls by the minute but the data
    // arrives in whole hours, so covering six hours from 14:45 needs the row
    // that starts at 20:00.
    val ahead = forecast.hourly.window(now, TIMELINE_HOURS + 1)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (ahead.isNotEmpty()) {
            Tile(
                label = stringResource(R.string.tile_rain),
                trailing = formatMillimetresWithUnit(
                    ahead.totalPrecipitation(now, now.plus(span)) ?: 0.0,
                ),
            ) {
                RainCurve(
                    hours = ahead,
                    zone = zone,
                    from = now,
                    span = span,
                    fused = timeline,
                )
                Spacer(Modifier.height(spacing.m))
                NextRainBar(forecast, now)
            }
        }

        AdvancedTile(forecast, now, bias)
    }
}

/**
 * Everything else that is known, behind one tap.
 *
 * The Sun and Air tiles used to sit open on this page. They were accurate and
 * almost nobody needed them: sunrise is the same to within minutes as yesterday,
 * and pressure means nothing to most readers on any given morning. Two tiles of
 * that under the one chart this app exists for was the wrong ratio, so they are
 * one shut drawer now.
 *
 * Being shut is also what makes it safe to be generous with what goes in. Dew
 * point, the moon, wind bearing - none of them has to earn its place against the
 * rain chart any more, only against the other things already in the drawer.
 */
@Composable
private fun AdvancedTile(forecast: WeatherForecast, now: Instant, bias: LearnedBias?) {
    val zone = forecast.location.zone
    val current = forecast.conditionsAt(now)
    val today = now.atZone(zone).toLocalDate()
    val day = forecast.daily.firstOrNull { it.date == today }
    val sunrise = day?.sunrise
    val sunset = day?.sunset

    ExpandableTile(
        label = stringResource(R.string.tile_advanced),
        summary = stringResource(R.string.advanced_summary),
    ) {
        MetricGrid(
            listOf(
                Metric(
                    stringResource(R.string.metric_feels_like),
                    formatTemperature(current.apparentTemperature),
                ),
                Metric(
                    stringResource(R.string.metric_dew_point),
                    formatTemperature(
                        Psychrometrics.dewPoint(current.temperature, current.humidity),
                    ),
                ),
                Metric(stringResource(R.string.metric_humidity), formatPercent(current.humidity)),
                Metric(stringResource(R.string.metric_pressure), formatPressure(current.pressure)),
                Metric(stringResource(R.string.metric_wind), formatWindSpeed(current.windSpeed)),
                Metric(stringResource(R.string.metric_gust), formatWindSpeed(current.windGust)),
                Metric(
                    stringResource(R.string.metric_wind_direction),
                    current.windDirection
                        ?.let { stringResource(CompassPoint.of(it).labelRes()) }
                        ?: NO_READING,
                ),
                Metric(
                    stringResource(R.string.metric_cloud),
                    formatPercent(forecast.hourly.at(now)?.cloudCover),
                ),
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
                    stringResource(R.string.metric_moon_phase),
                    stringResource(MoonPhase.nameAt(now).labelRes()),
                ),
                Metric(
                    stringResource(R.string.metric_moon_illumination),
                    formatPercent((MoonPhase.illuminationAt(now) * PERCENT).roundToInt()),
                ),
                // Absent entirely until this place has enough checked
                // predictions to show a pattern, so the row appears when the
                // correction does rather than sitting there as a dash.
                Metric(
                    stringResource(R.string.metric_local_correction),
                    bias?.let { formatTemperatureDelta(-it.effectiveOffset) } ?: NO_READING,
                ),
            ),
        )

        // Said in words as well as shown as a number, because a temperature that
        // has been quietly adjusted is not an improvement on one that has not.
        // Anybody comparing this screen against another app deserves to know why
        // they differ.
        if (bias != null) {
            Spacer(Modifier.height(WetterTheme.spacing.l))
            Text(
                text = stringResource(
                    R.string.advanced_correction_note,
                    formatTemperatureDelta(bias.offset),
                    bias.samples,
                ),
                style = WetterTheme.type.meta,
                color = WetterTheme.colors.textTertiary,
            )
        }
    }
}

private const val PERCENT = 100

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

    val date = start.atZone(zone).toLocalDate()
    val day = when (dayDistance(start, now, zone)) {
        DayDistance.TODAY -> null
        DayDistance.TOMORROW -> stringResource(R.string.relative_tomorrow)
        DayDistance.THIS_WEEK -> formatWeekday(date)
        // Seven days out a weekday name is today's name again, so it stops being
        // an answer and becomes a riddle.
        DayDistance.LATER -> formatDayAndMonth(date)
    }

    return if (day == null) {
        stringResource(R.string.next_rain_starts, kind, formatTime(start, zone))
    } else {
        stringResource(R.string.next_rain_starts_on, kind, day, formatTime(start, zone))
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
