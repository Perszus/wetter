package lv.bolwarra.wetter.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.MoonPhase
import lv.bolwarra.wetter.domain.SolarTime
import lv.bolwarra.wetter.domain.at
import lv.bolwarra.wetter.domain.conditionsAt
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.sky.Stargazing
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatWindSpeed
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The current reading, as a dial.
 *
 * A thermostat face: a porcelain disc with the temperature at its centre and a
 * glass edge around it, along which a light travels at the speed of the wind.
 *
 * Two marks sit in the gutter outside the glass, on the lower diagonals. They
 * are outside on purpose — the face is a reading, and these are advice about
 * what to do with it.
 *
 * ### The wind is said twice, deliberately
 *
 * The travelling light gives a *feel* for it: a drift in still air, a rush in a
 * gale. The three lines at the lower right give a *reading* of it, taken at a
 * glance. One is continuous and needs watching; the other is discrete and
 * immediate. Neither is redundant, because they answer at different speeds.
 *
 * Wind *direction* is on neither. The angle around this circle already means
 * time of day, and a compass bearing on the same degrees would be two coordinate
 * systems sharing one set of numbers. Direction belongs in the Air tile, in
 * words.
 *
 * ### The light is one gradient, not several arcs
 *
 * A sweep gradient rotated as a whole, so the tail is a genuine continuous
 * falloff. A few arcs of stepped opacity is the easy approximation and it looks
 * like what it is: banding. The seam where the sweep wraps sits at full
 * transparency, so it never shows.
 */
@Composable
fun WeatherPlate(
    forecast: WeatherForecast,
    now: Instant,
    /**
     * The fused precipitation timeline, so the umbrella and the chart below
     * it are reading the same rain. Empty simply means the model is on its
     * own, which is what the umbrella used to be given always.
     */
    timeline: List<FusedPrecipitation> = emptyList(),
    modifier: Modifier = Modifier,
    /**
     * Which mark is currently explaining itself, held by the caller.
     *
     * Hoisted because dismissing it is a screen-wide gesture: tapping anywhere
     * that is not the card should put it away, and the dial cannot see taps that
     * land on the chart below it.
     */
    explaining: PlateMark? = null,
    onExplain: (PlateMark?) -> Unit = {},
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone

    // Resolved against the clock, not taken from the fetch. Everything else on
    // this face moves - the mark, the light - and a temperature that did not
    // would end up contradicting the mark beside it.
    val current = forecast.conditionsAt(now)
    val windSpeed = current.windSpeed ?: 0.0
    // Falling back to the mean means a provider that does not publish gusts
    // simply reports one figure rather than a nonsensical one.
    val windGust = (current.windGust ?: windSpeed).coerceAtLeast(windSpeed)
    val beamAngle = rememberBeamAngle(windSpeed)
    val showUmbrella = forecast.umbrellaWeatherToday(now, timeline)

    // The cloud decks are what make this answerable at all: the total cannot
    // tell a veil of cirrus from a lid of stratus, and for this question that
    // is the only distinction that matters.
    val hour = forecast.hourly.at(now)
    val sky = Stargazing.assess(
        cloudLow = hour?.cloudLow,
        cloudMedium = hour?.cloudMedium,
        cloudHigh = hour?.cloudHigh,
        sunElevationDegrees = SolarTime.elevationDegrees(
            now,
            forecast.location.latitude,
            forecast.location.longitude,
        ),
        moonIllumination = MoonPhase.illuminationAt(now),
        precipitationMmPerHour = current.precipitation,
    )

    fun toggle(mark: PlateMark) {
        onExplain(if (explaining == mark) null else mark)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(PLATE_MAX + MARK_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            // Picture a line drawn across the bottom of the dial: the marks stand on
            // it, and move out along it to either side. Kept on the diagonal they
            // crowded the glass and looked like they were squeezing it; out here the
            // dial keeps its full width and they read as things standing beside it.
            //
            // The dial gives up whatever room the marks need, so it is measured from
            // what is left rather than assumed.
            val lane = MARK_SIZE + MARK_GAP
            val dial = minOf(maxWidth - lane * 2, PLATE_MAX, maxHeight)
            val radius = dial / 2
            val outwards = radius + MARK_GAP + MARK_SIZE / 2
            val downwards = radius * MARK_BASE_OF_CIRCLE

            Box(Modifier.size(dial), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawPorcelain(colors.surfaceRaised, colors.surfaceSunken)
                    drawGlassEdge(colors.hairline, colors.surfaceRaised)
                    drawHourTicks(colors.hairline)
                    // The pointer is ink, where the scale it aims at is hairline.
                    // One step of tone was not enough separation to tell the mark
                    // that means "now" from the twelve that mean nothing.
                    drawTimeMark(now, zone, colors.textPrimary)
                    if (windSpeed >= STILL_AIR_MS) {
                        drawWindLight(beamAngle, colors.textPrimary)
                    }
                }
            }

            if (sky.isWorthIt) {
                StarsMark(
                    modifier = Modifier
                        .offset(x = -outwards, y = -downwards)
                        .size(MARK_SIZE)
                        .quietlyClickable { toggle(PlateMark.STARS) },
                )
            }

            if (showUmbrella) {
                Icon(
                    painter = painterResource(R.drawable.ic_umbrella),
                    contentDescription = stringResource(R.string.plate_umbrella),
                    // The one coloured thing here, and the only reason that is
                    // allowed: it means rain, rain owns that hue, and this is the
                    // single mark whose job is to catch the eye on the way out.
                    tint = colors.precipitation,
                    modifier = Modifier
                        .offset(x = -outwards, y = downwards)
                        .size(MARK_SIZE)
                        .quietlyClickable { toggle(PlateMark.UMBRELLA) },
                )
            }

            WindLevels(
                speedMs = windSpeed,
                modifier = Modifier
                    .offset(x = outwards, y = downwards)
                    .size(MARK_SIZE)
                    .quietlyClickable { toggle(PlateMark.WIND) },
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = spacing.xxl),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = formatTemperature(current.temperature),
                        style = WetterTheme.type.reading,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.unit_celsius),
                        style = WetterTheme.type.readingUnit,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 3.dp, top = 16.dp),
                    )
                }
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(current.condition.labelRes()),
                    style = WetterTheme.type.title,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        MarkExplanation(
            mark = explaining,
            sky = sky,
            windSpeedMs = windSpeed,
            windGustMs = windGust,
            onDismiss = { onExplain(null) },
        )
    }
}

/**
 * Clickable without the ripple.
 *
 * These marks are drawn objects on a porcelain face, not buttons on a sheet, and
 * a Material ripple washing across the dial behind one of them reads as damage
 * rather than as feedback. The answer to the tap is the card appearing, which is
 * a far clearer acknowledgement than a grey flash.
 *
 * The click semantics are untouched, so this stays reachable and announced.
 */
private fun Modifier.quietlyClickable(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/** The two marks standing beside the dial, each of which can explain itself. */
enum class PlateMark { UMBRELLA, WIND, STARS }

/**
 * What the mark you just tapped actually means.
 *
 * These symbols carry real information and no label, which is the right trade
 * for something read at a glance every day but leaves nowhere to say what they
 * mean the first time. A tap is the least intrusive place to put that: absent
 * until asked for, and gone again on the next tap.
 *
 * It appears beneath the dial rather than over it. Floating it on top would
 * cover the temperature - the one thing on this screen most likely to be the
 * reason the app was opened.
 */
@Composable
private fun MarkExplanation(
    mark: PlateMark?,
    sky: Stargazing.Sky,
    windSpeedMs: Double,
    windGustMs: Double,
    onDismiss: () -> Unit,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    // The card outlives the selection by the length of the collapse animation, so
    // it needs its own copy of what to say. Reading `mark` directly would blank
    // the text the instant it cleared and animate an empty box shut.
    var shown by remember { mutableStateOf(PlateMark.WIND) }
    LaunchedEffect(mark) { if (mark != null) shown = mark }

    AnimatedVisibility(
        visible = mark != null,
        enter = Reveal.enter,
        exit = Reveal.exit,
    ) {
        val title = when (shown) {
            PlateMark.UMBRELLA -> stringResource(R.string.explain_umbrella_title)
            PlateMark.WIND -> stringResource(windLevelLabel(windLevel(windSpeedMs)))
            PlateMark.STARS -> stringResource(starsTitle(sky))
        }
        val body = when (shown) {
            PlateMark.UMBRELLA -> stringResource(R.string.explain_umbrella_body)
            PlateMark.WIND -> stringResource(R.string.explain_wind_body)
            PlateMark.STARS -> if (sky.moonWashed) {
                stringResource(R.string.explain_stars_body_moon)
            } else {
                stringResource(R.string.explain_stars_body)
            }
        }
        // The wind card carries the number the whole indicator is derived from.
        // Without it the levels and the speed of the light are two things you
        // have to take on trust; with it they are two readings of one figure you
        // can see, and either can be checked against it.
        val reading = when (shown) {
            // The gust is a reading, not something the dial acts out. A mean of
            // five gusting to six is a different afternoon from a mean of five
            // gusting to twelve, and that is worth saying in a number even
            // though the light itself holds a steady pace.
            PlateMark.WIND -> if (windGustMs > windSpeedMs + GUST_WORTH_SAYING) {
                stringResource(
                    R.string.explain_wind_reading,
                    formatWindSpeed(windSpeedMs),
                    formatWindSpeed(windGustMs),
                )
            } else {
                formatWindSpeed(windSpeedMs)
            }
            PlateMark.UMBRELLA -> null
            // The figure the mark is derived from, as the wind card does it:
            // "clear" is a judgement, and the share of open sky is the reading
            // it was made from.
            PlateMark.STARS -> stringResource(
                R.string.explain_stars_reading,
                (sky.clarity * PERCENT).roundToInt(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.s)
                .clip(RoundedCornerShape(CARD_CORNER))
                .background(colors.surfaceSunken)
                .quietlyClickable(onClick = onDismiss)
                .padding(horizontal = spacing.l, vertical = spacing.m),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = WetterTheme.type.title,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (reading != null) {
                    Text(
                        text = reading,
                        style = WetterTheme.type.figure,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(spacing.xs))
            if (shown == PlateMark.WIND) {
                // The three bands as a scale, with the one you are in picked out.
                // A reader wants to know what the lines mean and where they
                // currently stand, and both are easier to take from a short list
                // than from a paragraph describing them.
                val level = windLevel(windSpeedMs)
                WindBands(level)
                Spacer(Modifier.height(spacing.m))
            }
            Text(text = body, style = WetterTheme.type.body, color = colors.textSecondary)
        }
    }
}

/**
 * The three wind bands, as a scale.
 *
 * The thresholds are formatted from the same constants the indicator switches
 * on, so the numbers a reader is given cannot drift away from the ones the dial
 * is actually using.
 */
@Composable
private fun WindBands(currentLevel: Int) {
    val colors = WetterTheme.colors
    val moderate = formatWindSpeed(MODERATE_WIND_MS)
    val strong = formatWindSpeed(STRONG_WIND_MS)

    val bands = listOf(
        1 to stringResource(R.string.explain_wind_band_light, moderate),
        2 to stringResource(R.string.explain_wind_band_moderate, moderate, strong),
        3 to stringResource(R.string.explain_wind_band_strong, strong),
    )

    Column(verticalArrangement = Arrangement.spacedBy(WetterTheme.spacing.xs)) {
        bands.forEach { (level, text) ->
            val here = level == currentLevel
            Text(
                text = text,
                style = WetterTheme.type.body,
                color = if (here) colors.textPrimary else colors.textTertiary,
                fontWeight = if (here) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

/**
 * How windy it is, as three stacked lines that fill like a level.
 *
 * All three are always drawn. An indicator that hides its unfilled steps cannot
 * be read as a level at all: two lines showing would leave you unable to tell
 * whether that meant two of three or two of two.
 */
@Composable
private fun WindLevels(speedMs: Double, modifier: Modifier = Modifier) {
    val colour = WetterTheme.colors.textPrimary
    val level = windLevel(speedMs)
    val description = stringResource(windLevelLabel(level))

    Canvas(modifier.semantics { contentDescription = description }) {
        val gap = size.height / (WIND_LINES + 1)
        repeat(WIND_LINES) { index ->
            // Filled from the bottom up, the way a level fills.
            val lit = index >= WIND_LINES - level
            drawWave(
                y = gap * (index + 1),
                gap = gap,
                colour = colour.copy(alpha = if (lit) WAVE_LIT else WAVE_UNLIT),
            )
        }
    }
}

/** One wind line: a shallow wave, because a straight line would be a rule. */
private fun DrawScope.drawWave(y: Float, gap: Float, colour: Color) {
    val amplitude = gap * WAVE_AMPLITUDE
    val path = Path()
    path.moveTo(0f, y)
    var x = 0f
    while (x <= size.width) {
        val phase = (x / size.width) * WAVE_CYCLES * TAU
        // Minus, not plus: screen y grows downward, so a positive sine dips
        // first and the wave arrives back to front - down on the left, up on the
        // right.
        path.lineTo(x, y - amplitude * sin(phase))
        x += WAVE_RESOLUTION
    }
    drawPath(path, colour, style = Stroke(width = WAVE_STROKE.toPx(), cap = StrokeCap.Round))
}

/**
 * Three bands, cut near the Beaufort boundaries people already have words for: a
 * breeze you would not remark on, a wind that moves branches, and one you lean
 * into.
 */
private fun windLevel(speedMs: Double): Int = when {
    speedMs < MODERATE_WIND_MS -> 1
    speedMs < STRONG_WIND_MS -> 2
    else -> 3
}

private fun windLevelLabel(level: Int) = when (level) {
    1 -> R.string.wind_light
    2 -> R.string.wind_moderate
    else -> R.string.wind_strong
}

/**
 * Whether the day still holds rain worth carrying something for.
 *
 * Moderate, not merely wet. The mark used to appear for anything at all, which
 * meant it was up on most days in a maritime climate and stopped being read: a
 * warning that is always on is furniture. Moderate is where rain stops being
 * something you walk through and starts being something you take a coat for, and
 * an umbrella that appears only then is one worth looking at.
 *
 * From now rather than across the whole calendar day: a shower that finished
 * this morning is not a reason to carry an umbrella this afternoon.
 *
 * Radar counts, and has to. The model publishes one figure for a whole hour, so
 * a ten-minute downpour arrives as a mild average and never reaches moderate -
 * which meant the curve could be drawn plainly above the moderate guide with no
 * umbrella beside it, the chart and the mark disagreeing about the same rain.
 */
private fun WeatherForecast.umbrellaWeatherToday(
    now: Instant,
    timeline: List<FusedPrecipitation>,
): Boolean {
    val zone = location.zone
    val today = now.atZone(zone).toLocalDate()

    fun isToday(at: Instant) = !at.isBefore(now) && at.atZone(zone).toLocalDate() == today

    // Two steps in a row, not one. The threshold is a rate, and the model
    // applies it to a whole hour while the projection applies it to ten minutes
    // - so taking a single radar step at face value would raise the mark for a
    // burst the model would have averaged away, and put it back to being up
    // most days. Twenty minutes of moderate rain is a shower either way.
    val projected = timeline
        .filter { isToday(it.at) }
        .windowed(SUSTAINED_STEPS, partialWindows = false)
        .any { window ->
            window.all {
                PrecipitationIntensity.ofRate(it.millimetresPerHour) >=
                    PrecipitationIntensity.MODERATE
            }
        }

    return projected ||
        hourly.any {
            isToday(it.timestamp) && it.intensity >= PrecipitationIntensity.MODERATE
        }
}

/**
 * The light's position, turning at the speed of the wind.
 *
 * The angle is integrated frame by frame rather than handed to a repeating
 * animation, and both halves of that matter.
 *
 * A repeating tween has to be told a duration up front, so a change in wind
 * speed can only take effect by replacing the animation - which restarts it, and
 * the light jumps back to the top of the dial. Worse, the old version worked out
 * the duration *after* an early return for still air, which put a `remember`
 * behind a condition. Compose matches remembered state by position, so on the
 * frame the wind crossed that threshold the slots moved and the animation was
 * rebuilt from nothing. Between them, that is why the level indicator could
 * climb to two while the light carried on at its original speed.
 *
 * Accumulating an angle has neither problem. Each frame advances it by however
 * far the current wind justifies, so a gust speeds the light up smoothly from
 * wherever it happens to be, and there is no duration to invalidate.
 */
@Composable
private fun rememberBeamAngle(windSpeedMs: Double): Float {
    // Read inside the frame callback, so the loop always sees the latest wind
    // without being restarted by it.
    val speed by rememberUpdatedState(windSpeedMs)
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var previousFrame = 0L
        while (true) {
            withFrameNanos { frame ->
                val elapsed = if (previousFrame == 0L) {
                    0f
                } else {
                    (frame - previousFrame) / NANOS_PER_SECOND
                }
                previousFrame = frame
                val lap = lapSecondsFor(speed)
                if (lap != null && elapsed > 0f) {
                    angle = (angle + FULL_TURN * elapsed / lap) % FULL_TURN
                }
            }
        }
    }
    return angle
}

/**
 * How long one lap takes at a given wind speed, or null when the air is still
 * and the light should hold.
 *
 * The light behaves like a wind turbine. A turbine holds a roughly constant
 * tip-speed ratio, so its rotation rate rises in proportion to the wind and its
 * period is inversely proportional to it - which is all this is:
 *
 *     laps per minute = 3 x wind speed in m/s
 *
 * So 2 m/s turns at 6 rpm, 4 m/s at 12, 8 m/s at 24. Doubling the wind doubles
 * the rate, and the number above is worth checking against if this is ever
 * retuned.
 *
 * The two clamps are the turbine's cut-in and rated speeds. Below [STILL_AIR_MS]
 * nothing turns, because a turbine in dead air does not either and a light
 * creeping round in still conditions would be claiming wind that is not there.
 * Above about 13 m/s it holds at its fastest, because past that the eye cannot
 * tell one blur from another anyway. Between them - which is very nearly every
 * wind anyone ever stands in - it is honestly proportional.
 *
 * An earlier version interpolated linearly between a slowest and a fastest lap
 * against a 20 m/s ceiling. That is not proportionality and it did not look like
 * anything: a light breeze and a moderate one came out four seconds apart on a
 * forty-second lap.
 */
private fun lapSecondsFor(windSpeedMs: Double): Float? {
    if (windSpeedMs < STILL_AIR_MS) return null
    val ratio = REFERENCE_MS / windSpeedMs
    return (REFERENCE_LAP_S * Math.pow(ratio, DRAMA))
        .coerceIn(FASTEST_LAP_S, SLOWEST_LAP_S)
        .toFloat()
}

/**
 * The body of the plate: glazed ceramic, lit from above.
 *
 * The gradient's centre sits above the disc's centre rather than on it, which is
 * the difference between glazed ceramic and a moulded plastic button — real
 * objects are lit from somewhere.
 */
private fun DrawScope.drawPorcelain(face: Color, sunken: Color) {
    val radius = ringRadius()
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                lerp(face, Color.White, GLAZE_HIGHLIGHT),
                face,
                lerp(face, sunken, RIM_SHADE),
            ),
            center = Offset(center.x, center.y - radius * LIGHT_FROM_ABOVE),
            radius = radius * GLAZE_SPREAD,
        ),
        radius = radius,
    )
}

/**
 * The glass rim the light runs along.
 *
 * Two strokes: the edge itself, and an inset highlight catching a little more
 * light at the top. Without the second the ring is a drawn circle rather than
 * something with thickness.
 */
private fun DrawScope.drawGlassEdge(edge: Color, face: Color) {
    val radius = ringRadius()
    drawCircle(color = edge, radius = radius, style = Stroke(EDGE_WIDTH.toPx()))
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(lerp(face, Color.White, GLASS_CATCH), Color.Transparent),
            startY = center.y - radius,
            endY = center.y,
        ),
        radius = radius,
        style = Stroke(EDGE_WIDTH.toPx() * 0.5f),
    )
}

/** One tick an hour, inside the edge. Twelve at the top, clockwise. */
private fun DrawScope.drawHourTicks(colour: Color) {
    val outer = ringRadius() - EDGE_WIDTH.toPx() / 2f - TICK_GAP.toPx()
    repeat(HOURS_ON_FACE) { hour ->
        val quarter = hour % QUARTERS == 0
        val length = if (quarter) TICK_LONG else TICK_SHORT
        rotate(degrees = hour * DEGREES_PER_HOUR, pivot = center) {
            drawLine(
                color = colour,
                start = Offset(center.x, center.y - outer),
                end = Offset(center.x, center.y - outer + length.toPx()),
                strokeWidth = if (quarter) 2f else 1f,
            )
        }
    }
}

/**
 * Where we are in the day: a pointer aimed at the hour scale.
 *
 * A *shape*, not a longer line. It used to be a radial stroke starting at the
 * same radius as the hour ticks and only a few points longer than the ones at
 * twelve, three, six and nine - which made the one mark on this face that says
 * something about right now indistinguishable from the twelve that are just a
 * scale. Any amount of making it longer or brighter would still have been a
 * tick among ticks; a triangle is read as pointing at something, and that is
 * the whole job.
 *
 * It sits inside the rim and aims outward at the scale rather than running from
 * the centre like a clock hand, because the middle of this face is occupied by
 * the temperature - which is what people open the app for, and which a hand
 * sweeping across it would cross twice an hour.
 */
private fun DrawScope.drawTimeMark(now: Instant, zone: ZoneId, colour: Color) {
    val tip = ringRadius() - EDGE_WIDTH.toPx() / 2f - TICK_GAP.toPx()
    val base = tip - MARK_LENGTH.toPx()
    val halfBase = MARK_WIDTH.toPx()

    rotate(degrees = angleOf(now, zone) + QUARTER_TURN, pivot = center) {
        val pointer = Path().apply {
            moveTo(center.x, center.y - tip)
            lineTo(center.x - halfBase, center.y - base)
            lineTo(center.x + halfBase, center.y - base)
            close()
        }
        drawPath(pointer, colour)
    }
}

/**
 * The travelling light: one sweep gradient, rotated whole.
 *
 * Deliberately not the accent. Precipitation owns the only saturated hue in this
 * app, and a blue arc travelling the rim above a blue rain chart read as rain
 * rather than wind. Drawn in ink instead it read as a scuff, so it is the face's
 * own tone brought up — the ring brightening as the light passes.
 */
private fun DrawScope.drawWindLight(angle: Float, colour: Color) {
    val radius = ringRadius()
    rotate(degrees = angle, pivot = center) {
        drawCircle(
            brush = Brush.sweepGradient(
                0.00f to Color.Transparent,
                0.42f to Color.Transparent,
                0.62f to colour.copy(alpha = BEAM_ALPHA * 0.06f),
                0.78f to colour.copy(alpha = BEAM_ALPHA * 0.30f),
                0.90f to colour.copy(alpha = BEAM_ALPHA * 0.70f),
                0.97f to colour.copy(alpha = BEAM_ALPHA),
                // Back to nothing before the wrap, so the join never shows.
                1.00f to Color.Transparent,
                center = center,
            ),
            radius = radius,
            style = Stroke(EDGE_WIDTH.toPx()),
        )
    }
}

/**
 * The one circle everything is measured from.
 *
 * The stroke straddles this radius, so half its width falls outside it and the
 * ring's outer face lands flush with the bounds. The porcelain is drawn to the
 * same radius, which makes the ring the plate's edge rather than a second circle
 * inside it.
 */
private fun DrawScope.ringRadius(): Float = size.minDimension / 2f - EDGE_WIDTH.toPx() / 2f

/**
 * Where an instant sits on the face, in `drawArc` degrees - which measure from
 * three o'clock, so twelve at the top is minus ninety.
 *
 * Twelve hours to a turn, not twenty-four. A full day around the face put the
 * mark at a quarter of a degree a minute, which is about half a point on this
 * dial and simply cannot be seen moving; people reasonably read a hand that
 * never visibly moves as a broken one. Half a day doubles that, and matches the
 * clock face everyone already knows how to read.
 */
private fun angleOf(instant: Instant, zone: ZoneId): Float {
    val local = instant.atZone(zone)
    // Seconds included so the pointer sits where the instant actually is rather
    // than snapping to the last whole minute. The clock driving this only ticks
    // once a minute today, so this changes nothing on screen - but it means the
    // function answers the question it claims to, and a finer tick would simply
    // work.
    val minutes = (local.hour % HOURS_ON_FACE) * 60 +
        local.minute +
        local.second / SECONDS_PER_MINUTE
    return -QUARTER_TURN + minutes / MINUTES_ON_FACE * FULL_TURN
}

private val PLATE_MAX = 240.dp

private val MARK_SIZE = 26.dp

/** Clear air between the glass and the marks, so they are beside it, not on it. */
private val MARK_GAP = 10.dp

/**
 * The marks sit on the line tangent to the bottom of the dial, and travel out
 * along it. Hence exactly the radius: any less and they are floating somewhere
 * around the base rather than standing on it.
 */
private val MARK_BASE_OF_CIRCLE = 1f

private val EDGE_WIDTH = 3.dp
private val TICK_GAP = 6.dp
private val TICK_LONG = 7.dp
private val TICK_SHORT = 3.5.dp

/** Long enough to read as a pointer at arm's length, short enough not to crowd. */
private val MARK_LENGTH = 11.dp

/** Half the pointer's base. Slim, so it aims rather than blocks. */
private val MARK_WIDTH = 3.dp

private const val HOURS_ON_FACE = 12
private const val DEGREES_PER_HOUR = 360f / HOURS_ON_FACE
private const val MINUTES_ON_FACE = HOURS_ON_FACE * 60f

/** Long ticks at twelve, three, six and nine, as a clock face has. */
private const val QUARTERS = 3
private const val QUARTER_TURN = 90f
private const val FULL_TURN = 360f
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val SECONDS_PER_MINUTE = 60f

private val CARD_CORNER = 14.dp

/** Below this a gust is not meaningfully different from the mean. */
private const val GUST_WORTH_SAYING = 0.6

private const val TAU = 6.2831855f

/** How far above centre the glaze highlight sits, as a fraction of the radius. */
private const val LIGHT_FROM_ABOVE = 0.35f
private const val GLAZE_SPREAD = 1.25f
private const val GLAZE_HIGHLIGHT = 0.55f
private const val GLASS_CATCH = 0.7f
private const val RIM_SHADE = 0.35f

private const val BEAM_ALPHA = 0.5f

private const val WIND_LINES = 3
private const val WAVE_LIT = 1f
private const val WAVE_UNLIT = 0.16f
private const val WAVE_CYCLES = 1f

/** As a fraction of the gap between lines, so deeper waves cannot collide. */
private const val WAVE_AMPLITUDE = 0.42f
private const val WAVE_RESOLUTION = 1f
private val WAVE_STROKE = 2.dp

/**
 * The Beaufort 4 and 6 boundaries, rounded to whole numbers.
 *
 * The scale in the explanation card is written from these constants and speeds
 * are displayed as whole numbers, so 5.5 and 10.8 were printed as 6 and 11 while
 * the indicator still switched at the unrounded values - which put a reading of
 * 6 m/s under a line saying light wind is "under 6". Moving the thresholds a few
 * tenths costs nothing meteorologically and makes what is shown and what is done
 * the same thing.
 *
 * Below 5.5 is up to a gentle breeze - present, not worth remarking on. From
 * there to 10.8 is moderate and fresh: dust and paper lift, small trees sway.
 * At 10.8 the scale calls it a strong breeze and describes umbrellas becoming
 * difficult to use, which is the same wind that takes a hat off. That is the
 * level worth a third line, and it is reachable on a coast several times a
 * month - not a once-a-decade storm nobody would ever see the indicator fill
 * for.
 *
 * The previous values claimed to be these boundaries and were not; they were 3
 * and 5, which put "strong" at a fresh breeze.
 */
/** Consecutive projected steps that must be moderate before the mark goes up. */
private const val SUSTAINED_STEPS = 2

private const val MODERATE_WIND_MS = 6.0
private const val STRONG_WIND_MS = 11.0

/** Below this the air is still, the light holds, and the animation stops. */
private const val STILL_AIR_MS = 0.5

/**
 * The anchor for the rotation: an ordinary 5 m/s breeze takes four seconds to go
 * round. Everything else falls out of that inversely, so 2 m/s is ten seconds,
 * 8 m/s is two and a half, and a gale is at the floor.
 *
 * The first calibration of this was anchored at twelve seconds and floored at
 * forty-five, which was arithmetically adaptive and useless to look at: a light
 * breeze crawled round in half a minute, which does not read as movement at all,
 * so nothing below a gale looked different from anything else. The speed only
 * carries information if the difference between two ordinary winds is visible,
 * which means the whole usable range has to sit where the eye can tell laps
 * apart - a few seconds, not tens of them.
 */
private const val REFERENCE_MS = 5.0
private const val REFERENCE_LAP_S = 4.0

/**
 * How much the speed exaggerates a difference in wind.
 *
 * A turbine holds its rate proportional to the wind, and that was the first
 * version. It was faithful and it was not worth looking at: real wind over a day
 * here runs 3.4 to 7.0 m/s, so a proportional light varied by barely twice, and
 * two laps that differ by a factor of two look the same unless you can see them
 * side by side - which nobody can, because the hourly figure only moves once an
 * hour.
 *
 * Squaring it turns that same day into a fourfold spread, which is legible at a
 * glance. This is deliberately not physics: the dial is an indicator, and the
 * job of an indicator is to make a difference visible, not to model a rotor.
 */
private const val DRAMA = 2.0

/**
 * One lap a second. Fast enough to read as urgent, and reached at about 10 m/s
 * - which is where the level indicator beside it turns to strong. The two say
 * the same thing at the same moment, one continuously and one in steps.
 */
private const val FASTEST_LAP_S = 1.0

/** A slow crawl. Still air stops the light entirely, so this is a light breeze. */
private const val SLOWEST_LAP_S = 22.0

/**
 * The mark for a night worth looking up at.
 *
 * Drawn rather than dropped in as an image, like the wind levels beside it: one
 * bright four-pointed star and two lesser ones, which reads as sky at 26dp where
 * a five-pointed star reads as a rating and a dot reads as nothing.
 *
 * The points are concave-sided on purpose - straight edges make a compass rose,
 * and the pinched waist is what the eye recognises as a star's flare.
 */
@Composable
private fun StarsMark(modifier: Modifier = Modifier) {
    val colour = WetterTheme.colors.textPrimary
    val description = stringResource(R.string.plate_stars)

    Canvas(modifier.semantics { contentDescription = description }) {
        drawStar(Offset(size.width * 0.36f, size.height * 0.38f), size.minDimension * 0.30f, colour)
        drawStar(
            Offset(size.width * 0.78f, size.height * 0.22f),
            size.minDimension * 0.15f,
            colour.copy(alpha = LESSER_STAR),
        )
        drawStar(
            Offset(size.width * 0.68f, size.height * 0.76f),
            size.minDimension * 0.19f,
            colour.copy(alpha = LESSER_STAR),
        )
    }
}

/** One four-pointed star: long points, pinched waist. */
private fun DrawScope.drawStar(centre: Offset, radius: Float, colour: Color) {
    val path = Path()
    val waist = radius * STAR_WAIST
    repeat(STAR_POINTS * 2) { step ->
        val reach = if (step % 2 == 0) radius else waist
        // Starting a quarter turn round so a point aims straight up.
        val angle = step * (TAU / (STAR_POINTS * 2)) - TAU / 4
        val x = centre.x + reach * cos(angle).toFloat()
        val y = centre.y + reach * sin(angle).toFloat()
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, colour)
}

/** Companions, drawn back so the main star is read first. */
private const val LESSER_STAR = 0.45f

private const val STAR_POINTS = 4

/**
 * How far the star pinches in between its points. Small, because a wide waist
 * makes a diamond and the flare is the whole of the shape.
 */
private const val STAR_WAIST = 0.2f

private const val PERCENT = 100

/** The word for a night, from how good it actually is. */
private fun starsTitle(sky: Stargazing.Sky): Int = when {
    sky.quality >= EXCELLENT_SKY -> R.string.stars_excellent
    sky.quality >= GOOD_SKY -> R.string.stars_good
    else -> R.string.stars_fair
}

private const val EXCELLENT_SKY = 0.85f
private const val GOOD_SKY = 0.6f
