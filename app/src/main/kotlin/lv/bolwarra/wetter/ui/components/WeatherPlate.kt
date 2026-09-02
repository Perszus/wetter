package lv.bolwarra.wetter.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sin
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.format.formatTemperature
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
fun WeatherPlate(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone

    val windSpeed = forecast.current.windSpeed ?: 0.0
    val beamAngle = rememberBeamAngle(windSpeed)

    val rainExpectedToday = forecast.rainExpectedToday(now)
    // Forced on while the placement is being judged. Delete the flag and this
    // becomes `rainExpectedToday`, which is already correct.
    val showUmbrella = ALWAYS_SHOW_UMBRELLA || rainExpectedToday

    BoxWithConstraints(
        modifier = modifier
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
                drawTimeMark(now, zone, colors.textSecondary)
                if (windSpeed >= STILL_AIR_MS) {
                    drawWindLight(beamAngle, colors.textPrimary)
                }
            }
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
                    .size(MARK_SIZE),
            )
        }

        WindLevels(
            speedMs = windSpeed,
            modifier = Modifier
                .offset(x = outwards, y = downwards)
                .size(MARK_SIZE),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = spacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = formatTemperature(forecast.current.temperature),
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
                text = stringResource(forecast.current.condition.labelRes()),
                style = WetterTheme.type.title,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
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
 * Whether anything is expected to fall today, from now on.
 *
 * From now rather than across the whole calendar day: a shower that finished
 * this morning is not a reason to carry an umbrella this afternoon.
 */
private fun WeatherForecast.rainExpectedToday(now: Instant): Boolean {
    val zone = location.zone
    val today = now.atZone(zone).toLocalDate()
    return hourly.any { hour ->
        !hour.timestamp.isBefore(now) &&
            hour.timestamp.atZone(zone).toLocalDate() == today &&
            hour.intensity.isWet
    }
}

/** The light's position, turning at the speed of the wind. */
@Composable
private fun rememberBeamAngle(windSpeedMs: Double): Float {
    if (windSpeedMs < STILL_AIR_MS) return 0f

    val fraction = (windSpeedMs / GALE_MS).coerceIn(0.0, 1.0)
    val seconds = FASTEST_LAP_S + (SLOWEST_LAP_S - FASTEST_LAP_S) * (1.0 - fraction)

    val transition = rememberInfiniteTransition(label = "wind")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween((seconds * 1000).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "beam",
    )
    return angle
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

/** One tick an hour, inside the edge. Midnight at the top, clockwise. */
private fun DrawScope.drawHourTicks(colour: Color) {
    val outer = ringRadius() - EDGE_WIDTH.toPx() / 2f - TICK_GAP.toPx()
    repeat(HOURS_IN_DAY) { hour ->
        val quarter = hour % 6 == 0
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
 * Where we are in the day: a short radial line inside the edge rather than on
 * it, so the rim stays a clean unbroken circle.
 */
private fun DrawScope.drawTimeMark(now: Instant, zone: ZoneId, colour: Color) {
    val outer = ringRadius() - EDGE_WIDTH.toPx() / 2f - TICK_GAP.toPx()
    rotate(degrees = angleOf(now, zone) + QUARTER_TURN, pivot = center) {
        drawLine(
            color = colour,
            start = Offset(center.x, center.y - outer),
            end = Offset(center.x, center.y - outer + MARK_LENGTH.toPx()),
            strokeWidth = MARK_WIDTH.toPx(),
            cap = StrokeCap.Round,
        )
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
 * Where an instant sits on the face, in `drawArc` degrees — which measure from
 * three o'clock, so midnight at the top is minus ninety.
 */
private fun angleOf(instant: Instant, zone: ZoneId): Float {
    val local = instant.atZone(zone)
    val minutes = local.hour * 60 + local.minute
    return -QUARTER_TURN + minutes / MINUTES_IN_DAY * FULL_TURN
}

/**
 * Temporary. Shows the umbrella regardless of the forecast so its placement and
 * weight can be judged against a dry day. Remove with the flag in the composable.
 */
private const val ALWAYS_SHOW_UMBRELLA = true

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
private val MARK_LENGTH = 12.dp
private val MARK_WIDTH = 2.5.dp

private const val HOURS_IN_DAY = 24
private const val DEGREES_PER_HOUR = 360f / HOURS_IN_DAY
private const val MINUTES_IN_DAY = 24f * 60f
private const val QUARTER_TURN = 90f
private const val FULL_TURN = 360f
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

/** Beaufort 4 and 6, roughly: branches moving, then leaning into it. */
private const val MODERATE_WIND_MS = 3.5
private const val STRONG_WIND_MS = 8.0

/** Below this the air is still, the light holds, and the animation stops. */
private const val STILL_AIR_MS = 0.5

/** Roughly a strong gale. Past it the light is already as fast as it usefully gets. */
private const val GALE_MS = 20.0

private const val FASTEST_LAP_S = 2.5
private const val SLOWEST_LAP_S = 40.0
