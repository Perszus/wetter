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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The current reading, as a dial.
 *
 * A thermostat face rather than a number in the corner: one round plate, the
 * temperature at its centre, and the rim carrying the shape of the day. It is
 * the most instrument-like form there is, and it is the only place in the app
 * where a single moment gets this much room.
 *
 * ### What the rim says
 *
 * **The ring is a day.** Midnight at the top, clockwise, one tick an hour. The
 * stretch between sunrise and sunset is drawn lit and the rest dark, with a mark
 * at the current moment — so the plate is a sundial, and it turns over the day
 * whether or not anything is animating.
 *
 * **The beam is the wind.** A short travelling highlight whose speed is the wind
 * speed: it crawls in still air and races in a gale. Dead calm holds it still,
 * which is itself the reading, and also means the animation stops rather than
 * spinning pointlessly on a screen nobody is watching.
 *
 * ### What the rim deliberately does not say
 *
 * Wind *direction* is not on the ring, and cannot be. The angle around this
 * circle already means time of day; putting a compass bearing on the same
 * circle would be two coordinate systems sharing one set of degrees, and every
 * reading of it would be ambiguous. The direction belongs in the Air tile, in
 * words.
 */
@Composable
fun WeatherPlate(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone
    val today = forecast.daily.firstOrNull { it.date == now.atZone(zone).toLocalDate() }

    val windSpeed = forecast.current.windSpeed ?: 0.0
    val beamAngle = rememberBeamAngle(windSpeed)

    Box(
        modifier = modifier
            .widthIn(max = PLATE_MAX)
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawPlate(colors.surfaceRaised, colors.surfaceSunken)
            drawHourTicks(colors.hairline)
            drawDayArc(today, now, zone, colors.temperatureWarm, colors.night)
            drawNowMark(now, zone, colors.textPrimary)
            if (windSpeed >= STILL_AIR_MS) {
                drawWindBeam(beamAngle, colors.temperatureWarm)
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

/**
 * The beam's position, turning at the speed of the wind.
 *
 * Mapped so that a light breeze is a slow drift and a gale is a visible rush,
 * which is the point — "4 m/s" is a number most people cannot picture, and a
 * speed they can watch is the part that lands.
 */
@Composable
private fun rememberBeamAngle(windSpeedMs: Double): Float {
    if (windSpeedMs < STILL_AIR_MS) return 0f

    val seconds = (
        FASTEST_LAP_S +
            (SLOWEST_LAP_S - FASTEST_LAP_S) * (1.0 - windFraction(windSpeedMs))
        )
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

/** 0 at a standstill, 1 at a gale. Above that the beam stops getting faster. */
private fun windFraction(metresPerSecond: Double): Double =
    (metresPerSecond / GALE_MS).coerceIn(0.0, 1.0)

/** The face: a disc a step above the page, sunk very slightly at its rim. */
private fun DrawScope.drawPlate(face: Color, rim: Color) {
    val radius = size.minDimension / 2f
    drawCircle(
        brush = Brush.radialGradient(
            // Barely there: enough to lift the face off the page, not so much
            // that it becomes a moulded plastic button.
            colors = listOf(face, face, lerp(face, rim, RIM_SHADE)),
            center = center,
            radius = radius,
        ),
        radius = radius,
    )
}

private fun DrawScope.drawHourTicks(colour: Color) {
    val radius = size.minDimension / 2f
    repeat(HOURS_IN_DAY) { hour ->
        val long = hour % 6 == 0
        val length = if (long) TICK_LONG else TICK_SHORT
        rotate(degrees = hour * DEGREES_PER_HOUR, pivot = center) {
            drawLine(
                color = colour,
                start = Offset(center.x, center.y - radius + RIM_INSET.toPx()),
                end = Offset(center.x, center.y - radius + RIM_INSET.toPx() + length.toPx()),
                strokeWidth = if (long) 2f else 1f,
            )
        }
    }
}

/**
 * The lit stretch between sunrise and sunset.
 *
 * Above the arctic circle there is no sunrise to draw from, so the whole ring
 * takes the one tone that is true: lit through a polar day, dark through a polar
 * night.
 */
private fun DrawScope.drawDayArc(
    day: DailyWeather?,
    now: Instant,
    zone: ZoneId,
    dayColour: Color,
    nightColour: Color,
) {
    val radius = size.minDimension / 2f - RIM_INSET.toPx() - ARC_INSET.toPx()
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    val stroke =
        Stroke(width = ARC_WIDTH.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)

    val sunrise = day?.sunrise
    val sunset = day?.sunset

    if (sunrise == null || sunset == null) {
        // No sunrise means the sun either never set or never rose. Which of the
        // two is decided by whether it is up right now.
        val polarDay = day?.let { now.isAfter(it.date.atStartOfDay(zone).toInstant()) } ?: false
        drawArc(
            color = if (polarDay) dayColour.copy(alpha = ARC_ALPHA) else nightColour,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        return
    }

    val start = angleOf(sunrise, zone)
    val sweep = ((angleOf(sunset, zone) - start) + 360f) % 360f
    drawArc(
        color = dayColour.copy(alpha = ARC_ALPHA),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = stroke,
    )
}

private fun DrawScope.drawNowMark(now: Instant, zone: ZoneId, colour: Color) {
    // A single mark, sitting on the arc like a thermostat's set point. An extra
    // tick at the rim said the same thing at a different radius, and two marks
    // for one moment read as two readings.
    val radius = size.minDimension / 2f - RIM_INSET.toPx() - ARC_INSET.toPx()
    val radians = Math.toRadians(angleOf(now, zone).toDouble())
    val at = Offset(
        center.x + (radius * cos(radians)).toFloat(),
        center.y + (radius * sin(radians)).toFloat(),
    )
    drawCircle(color = colour, radius = NOW_DOT.toPx(), center = at)
}

/**
 * A short sweep of light, brightest at its leading edge.
 *
 * Deliberately not the accent. Precipitation owns the only saturated hue in this
 * app, and a blue arc travelling the rim of the plate directly above the rain
 * chart read as rain rather than as wind - or, worse, as a rendering fault.
 *
 * It is the day arc's own colour at full strength, so the effect is the ring
 * brightening under the beam rather than a second mark laid over it. Drawn in
 * ink instead it read as a scuff on the dial.
 */
private fun DrawScope.drawWindBeam(angle: Float, colour: Color) {
    val radius = size.minDimension / 2f - RIM_INSET.toPx() - ARC_INSET.toPx()
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2, radius * 2)

    // Drawn as a few arcs of rising opacity rather than one gradient stroke:
    // a sweep gradient cannot be aimed along an arc without a shader per frame,
    // and four segments read as a tail at this size.
    repeat(BEAM_SEGMENTS) { i ->
        val fraction = (i + 1f) / BEAM_SEGMENTS
        drawArc(
            color = colour.copy(alpha = BEAM_ALPHA * fraction * fraction),
            startAngle = angle + i * BEAM_SEGMENT_DEGREES,
            sweepAngle = BEAM_SEGMENT_DEGREES + 0.5f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = ARC_WIDTH.toPx()),
        )
    }
}

/**
 * Where an instant sits on the face, in degrees for `drawArc` — which measures
 * from three o'clock, so midnight at the top is minus ninety.
 */
private fun angleOf(instant: Instant, zone: ZoneId): Float {
    val local = instant.atZone(zone)
    val minutes = local.hour * 60 + local.minute
    return -QUARTER_TURN + minutes / MINUTES_IN_DAY * FULL_TURN
}

private val PLATE_MAX = 240.dp
private val RIM_INSET = 10.dp
private val ARC_INSET = 14.dp
private val ARC_WIDTH = 3.dp
private val TICK_LONG = 8.dp
private val TICK_SHORT = 4.dp
private val NOW_DOT = 4.dp

private const val HOURS_IN_DAY = 24
private const val DEGREES_PER_HOUR = 360f / HOURS_IN_DAY
private const val MINUTES_IN_DAY = 24f * 60f
private const val QUARTER_TURN = 90f
private const val FULL_TURN = 360f

private const val ARC_ALPHA = 0.3f

/** How far the rim is shaded towards the sunken tone. A hint, not a bevel. */
private const val RIM_SHADE = 0.55f
private const val BEAM_ALPHA = 0.9f
private const val BEAM_SEGMENTS = 4
private const val BEAM_SEGMENT_DEGREES = 7f

/** Below this the air is still, the beam holds, and the animation stops. */
private const val STILL_AIR_MS = 0.5

/** Roughly a strong gale. Past it the beam is already as fast as it usefully gets. */
private const val GALE_MS = 20.0

private const val FASTEST_LAP_S = 2.5
private const val SLOWEST_LAP_S = 40.0
