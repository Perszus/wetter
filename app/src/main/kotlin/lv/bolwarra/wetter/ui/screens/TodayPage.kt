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
    air: AirQuality? = null,
    /** Whether a fetch is outstanding, shown as a turning arc beside the label. */
    isRefreshing: Boolean = false,
    /**
     * Opens the rain map. Offered whenever there is a forecast, not only once
     * radar has landed: nobody waits half a minute for a button to appear, and
     * the map says for itself that it is still fetching.
     */
    onOpenMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val units = WetterTheme.units
    val zone = forecast.location.zone
    val spacing = WetterTheme.spacing
    val span = Duration.ofHours(TIMELINE_HOURS)
    val window = Duration.ofHours(WINDOW_HOURS)
    // One hour more than is drawn. The window rolls by the minute but the data
    // arrives in whole hours, so covering a day from 14:45 needs the row that
    // starts at 14:00 tomorrow.
    val ahead = forecast.hourly.window(now, TIMELINE_HOURS + 1)

    // What the window is made of, or - when nothing is coming - what would fall
    // if it did, so the tile still has an honest word on it in a dry January.
    val falling = ahead.dominantKind().takeIf { it != PrecipitationKind.NONE }
        ?: PrecipitationKind.likelyAt(forecast.conditionsAt(now).temperature)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.m),
    ) {
        if (ahead.isNotEmpty()) {
            Tile(
                // Named for what is actually going to fall. A chart headed
                // "rain" during a January cold snap is wrong in the way that
                // matters most: it is the one word on the tile somebody reads
                // without looking at anything else.
                label = stringResource(
                    R.string.tile_precipitation,
                    stringResource(falling.labelRes()),
                ),
                // The rate right now, not the six-hour total. The total read as a
                // current measurement sitting beside a live chart - it would say
                // "2.1 mm" through a dry window because of rain due at five - and
                // the number next to a reading should be the reading.
                trailing = stringResource(
                    R.string.curve_rate,
                    formatMillimetres(rateNow(timeline, ahead, now), units.precipitation),
                    units.precipitation.label,
                ),
                // This tile and no other. It is the one the reader is watching
                // and the one the radar refresh actually changes; putting the
                // same mark on the moon phase would be the app fidgeting.
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

    // The mark on the dial answers "right now", which is the wrong horizon for
    // this one thing - nobody decides to go and look at the sky at the instant
    // they open a weather app. Here it is the coming night.
    //
    // Hoisted above every conditional in this composable and remembered, because
    // it walks the next 24 hours in ten-minute steps and the clock behind `now`
    // ticks far more often than the answer changes.
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
        // Grouped, because a dozen readings in one grid is a wall somebody has to
        // read all of to find the one they came for. The headings are the
        // questions being asked - what the air is doing, what the wind is doing,
        // what is overhead - so an eye can skip three quarters of it.
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

        if (night.isWorthShowing) {
            MetricGroup(stringResource(R.string.group_stars)) {
                MetricGrid(
                    listOf(
                        // A plain answer first, because that is the whole
                        // question - everything under it is the working.
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
                        // Absent when the answer is no, rather than sitting
                        // there as a dash: there is no "when" for a night that
                        // never comes good.
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
                        // Named "now" because it is, and the answer above it
                        // is about tonight. A clear midday over a forecast
                        // overcast night is an ordinary thing, and without the
                        // word the two rows read as contradicting each other.
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
                            // Above the arctic circle there is no sunrise to
                            // report, and a zero would be the wrong kind of wrong.
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
                    // The one thing the moon does that is not about light. Twice
                    // a month it lines up with the sun and the two tides add;
                    // at the quarters they pull square and cancel.
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
 * When it next rains, in one line.
 *
 * It sits under the curve because it answers the question the curve cannot: the
 * chart covers the next day, and "nothing in the next day" is only half an
 * answer. The other half is usually the one somebody wanted, and it is short
 * enough to be a bar rather than a card of its own.
 *
 * It also answers it without asking anybody to travel. The chart now holds four
 * screens, and a reader who has not scrolled has seen six hours of it; this line
 * has read the whole thing.
 */
@Composable
private fun NextRainBar(
    forecast: WeatherForecast,
    now: Instant,
    timeline: List<FusedPrecipitation>,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    // Worked out in NextRain, which the widgets read too, so the home screen
    // and this line cannot say two different things about the same rain.
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
 * How much of the day the chart holds, rolling from the current hour.
 *
 * This was six hours for a long time, and the reason was sound: a day drawn
 * across a phone flattens the part anybody is going to act on into an eighth of
 * the width. What was wrong was the conclusion drawn from it — that the *chart*
 * had to be six hours, when what had to be six hours was the screen.
 *
 * So the day is all here and only six hours of it are in front of you, at
 * exactly the size those hours were before. Nothing about the near term is any
 * smaller, and "will it have stopped by the time I come home" stopped being a
 * question the chart refuses to take.
 */
private const val TIMELINE_HOURS = 24L

/**
 * How much of it is on screen at once.
 *
 * The scale, in other words: six hours to a screen is what one pixel is worth,
 * and it is fixed the way the height is fixed. A chart that changed its
 * resolution would have to be re-read every time it was opened.
 */
private const val WINDOW_HOURS = 6L
