// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
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
import lv.bolwarra.wetter.domain.dominantKind
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.sky.StarWatch
import lv.bolwarra.wetter.domain.sky.Stargazing
import lv.bolwarra.wetter.domain.sky.Tides
import lv.bolwarra.wetter.domain.window
import lv.bolwarra.wetter.ui.components.ExpandableTile
import lv.bolwarra.wetter.ui.components.Metric
import lv.bolwarra.wetter.ui.components.MetricGrid
import lv.bolwarra.wetter.ui.components.MetricGroup
import lv.bolwarra.wetter.ui.components.RainCurve
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.components.TileAction
import lv.bolwarra.wetter.ui.components.drawFoldedMap
import lv.bolwarra.wetter.ui.format.NO_READING
import lv.bolwarra.wetter.ui.format.NextRain
import lv.bolwarra.wetter.ui.format.formatConcentration
import lv.bolwarra.wetter.ui.format.formatDuration
import lv.bolwarra.wetter.ui.format.formatMillimetres
import lv.bolwarra.wetter.ui.format.formatPercent
import lv.bolwarra.wetter.ui.format.formatPressure
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.format.formatUvIndex
import lv.bolwarra.wetter.ui.format.formatWindSpeed
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.format.rateNow
import lv.bolwarra.wetter.ui.format.uvBandLabel
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Today: what the next few hours do. The rain curve is first and largest; the rest
 * is context for reading it.
 */
@Composable
fun TodayPage(
    forecast: WeatherForecast,
    now: Instant,
    timeline: List<FusedPrecipitation> = emptyList(),
    air: AirQuality? = null,
    /** Whether a fetch is outstanding, shown as a turning arc beside the label. */
    isRefreshing: Boolean = false,
    /**
     * Opens the rain map. Offered whenever there is a forecast, not only once radar
     * has landed: the map says for itself that it is still fetching.
     */
    onOpenMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val units = WetterTheme.units
    val zone = forecast.location.zone
    val spacing = WetterTheme.spacing
    val span = Duration.ofHours(TIMELINE_HOURS)
    val window = Duration.ofHours(WINDOW_HOURS)
    // One hour more than is drawn: the window rolls by the minute, the data comes in
    // whole hours.
    val ahead = forecast.hourly.window(now, TIMELINE_HOURS + 1)

    // What the window is made of or, when nothing is coming, what would fall if it
    // did, so the tile has an honest word on it either way.
    val falling = ahead.dominantKind().takeIf { it != PrecipitationKind.NONE }
        ?: PrecipitationKind.likelyAt(forecast.conditionsAt(now).temperature)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (ahead.isNotEmpty()) {
            Tile(
                // Named for what is actually going to fall: the one word on the tile somebody
                // reads without looking at anything else.
                label = stringResource(
                    R.string.tile_precipitation,
                    stringResource(falling.labelRes()),
                ),
                // The rate right now, not the window's total: the number beside a live chart
                // should be a reading.
                trailing = stringResource(
                    R.string.curve_rate,
                    formatMillimetres(rateNow(timeline, ahead, now), units.precipitation),
                    units.precipitation.label,
                ),
                // This tile only: it is the one the radar refresh changes.
                busy = isRefreshing,
                action = onOpenMap?.let {
                    TileAction(stringResource(R.string.radar_open), { ink ->
                        drawFoldedMap(ink)
                    }, it)
                },
            ) {
                RainCurve(
                    hours = ahead,
                    zone = zone,
                    from = now,
                    span = span,
                    window = window,
                    fused = timeline,
                )
                Spacer(Modifier.height(spacing.m))
                NextRainBar(forecast, now, timeline)
            }
        }

        AdvancedTile(forecast, now, air, falling)
    }
}

/**
 * Everything else that is known, behind one tap: sun, air, wind, sky and moon,
 * none of which competes with the rain chart while shut.
 */
@Composable
private fun AdvancedTile(
    forecast: WeatherForecast,
    now: Instant,
    air: AirQuality?,
    /** So the drawer asks about the same thing the chart above it is drawing. */
    falling: PrecipitationKind,
) {
    val units = WetterTheme.units
    val zone = forecast.location.zone
    val current = forecast.conditionsAt(now)
    val today = now.atZone(zone).toLocalDate()
    val day = forecast.daily.firstOrNull { it.date == today }
    val sunrise = day?.sunrise
    val sunset = day?.sunset

    val hour = forecast.hourly.at(now)

    // The coming night rather than now: nobody decides to look at the sky the moment
    // they open a weather app. Remembered, since it walks the next day in ten-minute
    // steps and `now` ticks far more often than the answer changes.
    val night = remember(forecast, now) {
        StarWatch.tonight(
            hours = forecast.hourly,
            now = now,
            latitude = forecast.location.latitude,
            longitude = forecast.location.longitude,
            moonIllumination = MoonPhase.illuminationAt(now),
        )
    }

    ExpandableTile(
        label = stringResource(R.string.tile_advanced),
        summary = stringResource(R.string.advanced_summary),
    ) {
        // Grouped under the questions being asked, so an eye can skip to the one it came
        // for.
        MetricGroup(stringResource(R.string.group_air)) {
            MetricGrid(
                listOf(
                    Metric(
                        stringResource(R.string.metric_feels_like),
                        formatTemperature(current.apparentTemperature, units.temperature),
                    ),
                    Metric(
                        stringResource(R.string.metric_dew_point),
                        formatTemperature(
                            Psychrometrics.dewPoint(current.temperature, current.humidity),
                            units.temperature,
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
                        formatWindSpeed(current.windSpeed, units.wind),
                    ),
                    Metric(
                        stringResource(R.string.metric_gust),
                        formatWindSpeed(current.windGust, units.wind),
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
                    // The three decks, because the total cannot tell thin cirrus from low stratus.
                    // They do not sum to the total: a deck seen through a gap is counted twice.
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
                        stringResource(
                            R.string.metric_chance_of,
                            stringResource(falling.labelRes()).lowercase(),
                        ),
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

        // Absent where the service has nothing for this place: an empty section would
        // read as an all-clear.
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

        if (night.isWorthShowing) {
            MetricGroup(stringResource(R.string.group_stars)) {
                MetricGrid(
                    listOf(
                        // A plain answer first; everything under it is the working.
                        Metric(
                            stringResource(R.string.metric_worth_watching),
                            stringResource(
                                if (night.best != null) {
                                    R.string.answer_yes
                                } else {
                                    R.string.answer_no
                                },
                            ),
                        ),
                        // Absent when the answer is no: there is no "when" for a night that never
                        // clears.
                        Metric(
                            stringResource(R.string.metric_stars_when),
                            night.best
                                ?.let {
                                    stringResource(
                                        R.string.stars_window,
                                        formatTime(it.from, zone),
                                        formatTime(it.to, zone),
                                    )
                                }
                                ?: NO_READING,
                        ),
                        // Named "now", because the answer above it is about tonight and the two
                        // would
                        // otherwise read as contradicting each other.
                        Metric(
                            stringResource(R.string.metric_sky_open),
                            Stargazing
                                .clarityOf(hour?.cloudLow, hour?.cloudMedium, hour?.cloudHigh)
                                ?.let { formatPercent((it * PERCENT).roundToInt()) }
                                ?: NO_READING,
                        ),
                        Metric(
                            stringResource(R.string.metric_dark_from),
                            night.darkFrom?.let { formatTime(it, zone) } ?: NO_READING,
                        ),
                        Metric(
                            stringResource(R.string.metric_dark_until),
                            night.darkUntil?.let { formatTime(it, zone) } ?: NO_READING,
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
                            // Above the Arctic Circle there may be no sunrise to report.
                            NO_READING
                        },
                    ),
                ),
            )
        }

        MetricGroup(stringResource(R.string.group_moon), last = true) {
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
                    // Spring and neap tides: twice a month the moon lines up with the sun and the
                    // tides add; at the quarters they cancel.
                    Metric(
                        stringResource(R.string.metric_tide),
                        stringResource(Tides.stateAt(now).labelRes()),
                    ),
                ),
            )
        }
    }
}

private const val PERCENT = 100

/**
 * When it next rains, in one line. It reads the whole timeline and beyond, so a
 * reader who has seen only the first screen of the chart still gets the answer.
 */
@Composable
private fun NextRainBar(
    forecast: WeatherForecast,
    now: Instant,
    timeline: List<FusedPrecipitation>,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    // Worked out in NextRain, which the widgets read too, so the two cannot disagree.
    val line = NextRain.of(LocalResources.current, forecast, timeline, now)
    val ink = if (line.matters) colors.textPrimary else colors.textTertiary

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
                .background(ink),
        )
        Spacer(Modifier.width(spacing.m))
        Text(
            text = line.text,
            style = WetterTheme.type.body,
            color = ink,
        )
    }
}

private val DOT = 7.dp

/**
 * How much of the day the chart holds, rolling from the current hour. Only
 * [WINDOW_HOURS] of it is on screen at once, at full scale; the rest scrolls.
 */
private const val TIMELINE_HOURS = 24L

/** How much of it is on screen at once: the chart's fixed horizontal scale. */
private const val WINDOW_HOURS = 6L
