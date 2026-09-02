package lv.bolwarra.wetter.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
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
 * ### The light is the wind
 *
 * Its speed is the wind speed — a slow drift in still air, a visible rush in a
 * gale. That is the whole reason it is allowed to move at all: "4 m/s" is a
 * number most people cannot picture, and a speed they can watch is the part that
 * lands. Dead calm holds it still, which is itself the reading and also stops
 * the animation rather than spinning it at nobody.
 *
 * Wind *direction* is deliberately not on the ring. The angle around this circle
 * already means time of day — that is what the ticks and the time mark are — and
 * a compass bearing on the same degrees would be two coordinate systems sharing
 * one set of numbers. Direction belongs in the Air tile, in words.
 *
 * ### The light is one gradient, not several arcs
 *
 * It is a sweep gradient rotated as a whole, so the tail is a genuine continuous
 * falloff. Drawing a few arcs of stepped opacity is the easy approximation and
 * it looks like what it is: banding. The seam where the sweep wraps sits at full
 * transparency, so it never shows.
 */
@Composable
fun WeatherPlate(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone

    val windSpeed = forecast.current.windSpeed ?: 0.0
    val beamAngle = rememberBeamAngle(windSpeed)

    Box(
        modifier = modifier
            // The cap has to come before fillMaxWidth: fillMaxWidth pins the
            // minimum equal to the maximum, and widthIn cannot pull a maximum
            // below a minimum that is already fixed.
            .widthIn(max = PLATE_MAX)
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawPorcelain(colors.surfaceRaised, colors.surfaceSunken)
            drawGlassEdge(colors.hairline, colors.surfaceRaised)
            drawHourTicks(colors.hairline)
            drawTimeMark(now, zone, colors.textSecondary)
            if (windSpeed >= STILL_AIR_MS) {
                drawWindLight(beamAngle, colors.textPrimary)
            }
        }

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
 * the whole difference between porcelain and a moulded plastic button — real
 * objects are lit from somewhere.
 */
private fun DrawScope.drawPorcelain(face: Color, sunken: Color) {
    val radius = size.minDimension / 2f
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
 * Two strokes: the edge itself, and a slightly inset highlight that catches a
 * little more light at the top. Without the second one the ring is a drawn
 * circle rather than something with thickness.
 */
private fun DrawScope.drawGlassEdge(edge: Color, face: Color) {
    val radius = size.minDimension / 2f - EDGE_INSET.toPx()
    drawCircle(color = edge, radius = radius, style = Stroke(EDGE_WIDTH.toPx()))
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(
                lerp(face, Color.White, GLASS_CATCH),
                Color.Transparent,
            ),
            startY = center.y - radius,
            endY = center.y,
        ),
        radius = radius,
        style = Stroke(EDGE_WIDTH.toPx() * 0.5f),
    )
}

/** One tick an hour, inside the edge. Midnight at the top, clockwise. */
private fun DrawScope.drawHourTicks(colour: Color) {
    val outer = size.minDimension / 2f - EDGE_INSET.toPx() - EDGE_WIDTH.toPx() - TICK_GAP.toPx()
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
 * Where we are in the day: a short radial line, sitting inside the edge rather
 * than on it, so the rim stays a clean unbroken circle.
 */
private fun DrawScope.drawTimeMark(now: Instant, zone: ZoneId, colour: Color) {
    val outer = size.minDimension / 2f - EDGE_INSET.toPx() - EDGE_WIDTH.toPx() - TICK_GAP.toPx()
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
 * The stops run from nothing, up to the head, and back to nothing at the wrap,
 * so the seam where the sweep closes is invisible. Rotating the gradient rather
 * than redrawing segments is what makes the tail a continuous falloff instead of
 * a set of bands.
 */
private fun DrawScope.drawWindLight(angle: Float, colour: Color) {
    val radius = size.minDimension / 2f - EDGE_INSET.toPx()
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
 * Where an instant sits on the face, in `drawArc` degrees — which measure from
 * three o'clock, so midnight at the top is minus ninety.
 */
private fun angleOf(instant: Instant, zone: ZoneId): Float {
    val local = instant.atZone(zone)
    val minutes = local.hour * 60 + local.minute
    return -QUARTER_TURN + minutes / MINUTES_IN_DAY * FULL_TURN
}

private val PLATE_MAX = 240.dp
private val EDGE_INSET = 8.dp
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

/** How far above centre the glaze highlight sits, as a fraction of the radius. */
private const val LIGHT_FROM_ABOVE = 0.35f
private const val GLAZE_SPREAD = 1.25f
private const val GLAZE_HIGHLIGHT = 0.55f
private const val GLASS_CATCH = 0.7f
private const val RIM_SHADE = 0.7f

private const val BEAM_ALPHA = 0.5f

/** Below this the air is still, the light holds, and the animation stops. */
private const val STILL_AIR_MS = 0.5

/** Roughly a strong gale. Past it the light is already as fast as it usefully gets. */
private const val GALE_MS = 20.0

private const val FASTEST_LAP_S = 2.5
private const val SLOWEST_LAP_S = 40.0
