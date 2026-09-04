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
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.at
import lv.bolwarra.wetter.domain.conditionsAt
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.PrecipitationSpell
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.nextPrecipitation
import lv.bolwarra.wetter.domain.verification.LearnedBias
import lv.bolwarra.wetter.domain.window
import lv.bolwarra.wetter.ui.components.ExpandableTile
import lv.bolwarra.wetter.ui.components.Metric
import lv.bolwarra.wetter.ui.components.MetricGrid
import lv.bolwarra.wetter.ui.components.MetricGroup
import lv.bolwarra.wetter.ui.components.RainCurve
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.format.DayDistance
import lv.bolwarra.wetter.ui.format.NO_READING
import lv.bolwarra.wetter.ui.format.dayDistance
import lv.bolwarra.wetter.ui.format.formatConcentration
import lv.bolwarra.wetter.ui.format.formatDayAndMonth
import lv.bolwarra.wetter.ui.format.formatDuration
import lv.bolwarra.wetter.ui.format.formatMillimetres
import lv.bolwarra.wetter.ui.format.formatPercent
import lv.bolwarra.wetter.ui.format.formatPressure
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatTemperatureDelta
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.format.formatUvIndex
import lv.bolwarra.wetter.ui.format.formatWeekday
import lv.bolwarra.wetter.ui.format.formatWindSpeed
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.format.uvBandLabel
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
    air: AirQuality? = null,
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
                // The rate right now, not the six-hour total. The total read as a
                // current measurement sitting beside a live chart - it would say
                // "2.1 mm" through a dry window because of rain due at five - and
                // the number next to a reading should be the reading.
                trailing = stringResource(
                    R.string.curve_rate,
                    formatMillimetres(rateNow(timeline, ahead, now)),
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
                NextRainBar(forecast, now, timeline)
            }
        }

        AdvancedTile(forecast, now, bias, air)
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
private fun AdvancedTile(
    forecast: WeatherForecast,
    now: Instant,
    bias: LearnedBias?,
    air: AirQuality?,
) {
    val zone = forecast.location.zone
    val current = forecast.conditionsAt(now)
    val today = now.atZone(zone).toLocalDate()
    val day = forecast.daily.firstOrNull { it.date == today }
    val sunrise = day?.sunrise
    val sunset = day?.sunset

    val hour = forecast.hourly.at(now)

    ExpandableTile(
        label = stringResource(R.string.tile_advanced),
        summary = stringResource(R.string.advanced_summary),
    ) {
        // Grouped, because a dozen readings in one grid is a wall somebody has to
        // read all of to find the one they came for. The headings are the
        // questions being asked - what the air is doing, what the wind is doing,
        // what is overhead - so an eye can skip three quarters of it.
        MetricGroup(stringResource(R.string.group_air)) {
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
                    Metric(
                        stringResource(R.string.metric_humidity),
                        formatPercent(current.humidity),
                    ),
                    Metric(
                        stringResource(R.string.metric_pressure),
                        formatPressure(current.pressure),
                    ),
                ),
            )
        }

        MetricGroup(stringResource(R.string.group_wind)) {
            MetricGrid(
                listOf(
                    Metric(
                        stringResource(R.string.metric_wind),
                        formatWindSpeed(current.windSpeed),
                    ),
                    Metric(
                        stringResource(R.string.metric_gust),
                        formatWindSpeed(current.windGust),
                    ),
                    Metric(
                        stringResource(R.string.metric_wind_direction),
                        current.windDirection
                            ?.let { stringResource(CompassPoint.of(it).labelRes()) }
                            ?: NO_READING,
                    ),
                ),
            )
        }

        MetricGroup(stringResource(R.string.group_sky)) {
            MetricGrid(
                listOf(
                    Metric(
                        stringResource(R.string.metric_cloud),
                        formatPercent(hour?.cloudCover),
                    ),
                    // Broken out because the total cannot tell a bright day from
                    // a grey one - 99% of thin cirrus is a hazy sun, 99% of low
                    // stratus is a lid. They do not sum to the total and are not
                    // meant to: a deck seen through a gap in the one below it is
                    // counted twice.
                    Metric(
                        stringResource(R.string.metric_cloud_low),
                        formatPercent(hour?.cloudLow),
                    ),
                    Metric(
                        stringResource(R.string.metric_cloud_medium),
                        formatPercent(hour?.cloudMedium),
                    ),
                    Metric(
                        stringResource(R.string.metric_cloud_high),
                        formatPercent(hour?.cloudHigh),
                    ),
                    Metric(
                        stringResource(R.string.metric_chance_of_rain),
                        formatPercent(hour?.precipitationProbability),
                    ),
                    Metric(
                        stringResource(R.string.metric_uv),
                        hour?.uvIndex?.let {
                            stringResource(
                                R.string.uv_reading,
                                formatUvIndex(it),
                                stringResource(uvBandLabel(it)),
                            )
                        } ?: NO_READING,
                    ),
                ),
            )
        }

        // Absent where the service has nothing for this place, because an
        // empty air quality section reads as an all-clear.
        if (air != null) {
            MetricGroup(stringResource(R.string.group_air_quality)) {
                MetricGrid(
                    listOf(
                        Metric(
                            stringResource(R.string.metric_air_quality),
                            air.band?.let { stringResource(it.labelRes()) } ?: NO_READING,
                        ),
                        Metric(
                            stringResource(R.string.metric_pm25),
                            formatConcentration(air.pm25),
                        ),
                        Metric(
                            stringResource(R.string.metric_pm10),
                            formatConcentration(air.pm10),
                        ),
                        Metric(
                            stringResource(R.string.metric_ozone),
                            formatConcentration(air.ozone),
                        ),
                        Metric(
                            stringResource(R.string.metric_no2),
                            formatConcentration(air.nitrogenDioxide),
                        ),
                    ),
                )
            }
        }

        MetricGroup(stringResource(R.string.group_sun)) {
            MetricGrid(
                listOf(
                    Metric(stringResource(R.string.metric_sunrise), formatTime(sunrise, zone)),
                    Metric(stringResource(R.string.metric_sunset), formatTime(sunset, zone)),
                    Metric(
                        stringResource(R.string.metric_day_length),
                        if (sunrise != null && sunset != null) {
                            formatDuration(Duration.between(sunrise, sunset))
                        } else {
                            // Above the arctic circle there is no sunrise to
                            // report, and a zero would be the wrong kind of wrong.
                            NO_READING
                        },
                    ),
                ),
            )
        }

        MetricGroup(stringResource(R.string.group_moon), last = bias == null) {
            MetricGrid(
                listOf(
                    Metric(
                        stringResource(R.string.metric_moon_phase),
                        stringResource(MoonPhase.nameAt(now).labelRes()),
                    ),
                    Metric(
                        stringResource(R.string.metric_moon_illumination),
                        formatPercent((MoonPhase.illuminationAt(now) * PERCENT).roundToInt()),
                    ),
                ),
            )
        }

        // Absent entirely until this place has enough checked predictions to show
        // a pattern, so the group appears when the correction does rather than
        // sitting there as a dash.
        if (bias != null) {
            MetricGroup(stringResource(R.string.group_here), last = true) {
                MetricGrid(
                    listOf(
                        Metric(
                            stringResource(R.string.metric_local_correction),
                            formatTemperatureDelta(-bias.effectiveOffset),
                        ),
                    ),
                )
                Spacer(Modifier.height(WetterTheme.spacing.l))
                // Said in words as well as shown as a number, because a
                // temperature that has been quietly adjusted is not an
                // improvement on one that has not. Anybody comparing this screen
                // against another app deserves to know why they differ.
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
}

/**
 * What is falling at this moment, in millimetres an hour.
 *
 * Prefers the fused timeline, because in the first hour that is radar and the
 * model's hourly row is a smooth average of sixty minutes that may contain one
 * five-minute burst. Falls back to the row covering now when there is no
 * projection - offline, or somewhere no radar reaches.
 */
private fun rateNow(
    timeline: List<FusedPrecipitation>,
    ahead: List<HourlyWeather>,
    now: Instant,
): Double {
    val nearest = timeline.minByOrNull { Duration.between(it.at, now).abs() }
    // Only if it is actually about now. A projection kept from a sweep half an
    // hour ago describes half an hour ago.
    if (nearest != null && Duration.between(nearest.at, now).abs() <= NOW_TOLERANCE) {
        return nearest.millimetresPerHour
    }
    return ahead.at(now)?.precipitation ?: 0.0
}

/** How far from "now" a projected sample can sit and still count as now. */
private val NOW_TOLERANCE: Duration = Duration.ofMinutes(10)

/**
 * What the radar is seeing, named.
 *
 * Radar returns echoes from hydrometeors and cannot say whether they are frozen,
 * so the word comes from elsewhere: the model's own next spell where it has one,
 * and the air temperature otherwise. Near freezing that is genuinely uncertain,
 * which is why the answer there is "wintry mix" rather than a confident "rain".
 */
private fun radarKind(
    spell: PrecipitationSpell?,
    forecast: WeatherForecast,
    now: Instant,
): PrecipitationKind {
    spell?.kind?.takeIf { it != PrecipitationKind.NONE }?.let { return it }
    val temperature = forecast.conditionsAt(now).temperature ?: return PrecipitationKind.RAIN
    return when {
        temperature <= SNOW_CEILING_C -> PrecipitationKind.SNOW
        temperature <= MIXED_CEILING_C -> PrecipitationKind.MIXED
        else -> PrecipitationKind.RAIN
    }
}

/** At or below this, falling water reaches the ground frozen. */
private const val SNOW_CEILING_C = 0.5

/** Between the two, either is possible and neither is worth asserting. */
private const val MIXED_CEILING_C = 2.5

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
private fun NextRainBar(
    forecast: WeatherForecast,
    now: Instant,
    timeline: List<FusedPrecipitation>,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val spell = forecast.hourly.nextPrecipitation(now)

    // The model's hourly row is an average over sixty minutes, so a shower that
    // the radar can see arriving can sit under the trace threshold in it. When
    // that happens this bar used to announce rain starting in an hour while the
    // curve above it, and the rate beside the label, both showed rain falling.
    // Whatever is actually overhead wins.
    val rate = rateNow(timeline, forecast.hourly, now)
    val fallingNow = rate >= PrecipitationIntensity.TRACE_MM_PER_HOUR
    val modelAgrees = spell != null && !spell.start.isAfter(now)
    val wet = fallingNow || modelAgrees

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
                .background(
                    if (wet ||
                        spell != null
                    ) {
                        colors.precipitation
                    } else {
                        colors.textTertiary
                    },
                ),
        )
        Spacer(Modifier.width(spacing.m))
        Text(
            text = if (fallingNow && !modelAgrees) {
                stringResource(
                    R.string.next_rain_falling,
                    stringResource(radarKind(spell, forecast, now).labelRes()),
                )
            } else {
                spell.describe(now, forecast)
            },
            style = WetterTheme.type.body,
            color = if (wet || spell != null) colors.textPrimary else colors.textTertiary,
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
