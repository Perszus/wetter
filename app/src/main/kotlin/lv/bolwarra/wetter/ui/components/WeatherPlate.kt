// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.FaceReading
import lv.bolwarra.wetter.domain.MoonPhase
import lv.bolwarra.wetter.domain.SolarTime
import lv.bolwarra.wetter.domain.at
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.forecast.UmbrellaDay
import lv.bolwarra.wetter.domain.hazard.Hazard
import lv.bolwarra.wetter.domain.hazard.HazardSeverity
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.sky.Stargazing
import lv.bolwarra.wetter.ui.format.formatHazardWindow
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatWindSpeed
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.Emphasis
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The current reading as a thermostat dial: a porcelain disc with the
 * temperature and its word at the centre, the weather's picture on the glass, an
 * hour scale with a pointer for now, and a light travelling the rim at the speed
 * of the wind.
 *
 * Marks beside the dial (umbrella, wind level, stars, warning) explain themselves
 * when tapped. Wind direction is not shown here: the angle round this face
 * already means time of day.
 */
@Composable
fun WeatherPlate(
    forecast: WeatherForecast,
    now: Instant,
    /**
     * The fused precipitation timeline, so the umbrella reads the same rain as
     * the chart below it. Empty when only the model is available.
     */
    timeline: List<FusedPrecipitation> = emptyList(),
    /** Whatever in the next day could hurt somebody, worst first. */
    hazards: List<Hazard> = emptyList(),
    modifier: Modifier = Modifier,
    /**
     * Which mark is explaining itself. Hoisted so a tap anywhere outside the card,
     * including on the chart below, can dismiss it.
     */
    explaining: PlateMark? = null,
    onExplain: (PlateMark?) -> Unit = {},
    /** Notably poor air, which makes a heat hazard hazy on the glass. */
    hazyAir: Boolean = false,
) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val zone = forecast.location.zone

    // Resolved against the clock rather than taken from the fetch, so the reading
    // keeps pace with the moving parts of the face. Shared with the widgets.
    val face = FaceReading.of(forecast, timeline, hazards, now, hazyAir)
    val current = face.current
    val windSpeed = current.windSpeed ?: 0.0
    // Without a published gust, the gust is the mean.
    val windGust = (current.windGust ?: windSpeed).coerceAtLeast(windSpeed)
    val beamAngle = rememberBeamAngle(windSpeed)
    val showUmbrella = UmbrellaDay.isUmbrellaDay(forecast, timeline, now)

    // Cloud decks, which tell thin high cloud from low overcast for the stars mark.
    val hour = forecast.hourly.at(now)

    // The word from whatever actually looked: the radar where it can see.
    val seen = face.seen
    val glass = face.glass

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
            // The marks stand on the line tangent to the bottom of the dial, outside it;
            // the dial takes the width that is left.
            val lane = MARK_SIZE + MARK_GAP
            val dial = minOf(maxWidth - lane * 2, PLATE_MAX, maxHeight)

            // The reading's text and position, so the glass can keep a margin round its
            // glyphs. Measured against this box, so scrolling moves nothing here.
            var dialAt by remember { mutableStateOf(Offset.Zero) }
            var readingAt by remember { mutableStateOf(Offset.Zero) }
            var rowAt by remember { mutableStateOf(Offset.Zero) }
            var figureText by remember { mutableStateOf<TextLayoutResult?>(null) }
            var figureAt by remember { mutableStateOf(Offset.Zero) }
            var unitText by remember { mutableStateOf<TextLayoutResult?>(null) }
            var unitAt by remember { mutableStateOf(Offset.Zero) }
            var wordText by remember { mutableStateOf<TextLayoutResult?>(null) }
            var wordAt by remember { mutableStateOf(Offset.Zero) }
            val reading = listOfNotNull(
                figureText?.let { GlassText(it, readingAt + rowAt + figureAt - dialAt) },
                unitText?.let { GlassText(it, readingAt + rowAt + unitAt - dialAt) },
                wordText?.let { GlassText(it, readingAt + wordAt - dialAt) },
            )
            val radius = dial / 2
            val outwards = radius + MARK_GAP + MARK_SIZE / 2
            val downwards = radius * MARK_BASE_OF_CIRCLE

            Box(
                Modifier
                    .size(dial)
                    .onGloballyPositioned { dialAt = it.positionInParent() },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawPorcelain(
                        face = colors.surface,
                        // Pulled toward the face by the plate's relief factor, so the well is the
                        // same depth on every plate (see WetterColors.relief).
                        lip = lerp(colors.surface, colors.surfaceShade, colors.relief),
                        floorCatch = lerp(colors.surface, colors.surfaceRaised, colors.relief),
                        occlusion = colors.hairline,
                    )
                }
                // Between the porcelain and the instrument, so the edge, ticks and pointers stay
                // crisp over it. White light on both plates.
                GlassMarks(
                    name = glass,
                    ink = Color.White,
                    clear = reading,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    drawFiredEdge(colors.hairline)
                    drawHourTicks(colors.hairline)
                    // Ink rather than hairline, so the pointer stands apart from the scale.
                    drawTimeMark(now, zone, colors.textPrimary)
                    if (windSpeed >= STILL_AIR_MS) {
                        // Ink: the one moving thing on the face must be the most legible.
                        drawWindLight(beamAngle, colors.textPrimary)
                    }
                }
            }

            if (hazards.isNotEmpty()) {
                HazardMark(
                    severity = hazards.first().severity,
                    modifier = Modifier
                        .offset(x = outwards, y = -downwards)
                        .size(MARK_SIZE)
                        .quietlyClickable { toggle(PlateMark.HAZARD) },
                )
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
                    // The only coloured mark: it means rain, and rain owns that hue.
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
                modifier = Modifier
                    .padding(horizontal = spacing.xxl)
                    .onGloballyPositioned { readingAt = it.positionInParent() },
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.onGloballyPositioned { rowAt = it.positionInParent() },
                ) {
                    Text(
                        text = formatTemperature(current.temperature, units.temperature),
                        style = WetterTheme.type.reading,
                        color = colors.textPrimary,
                        onTextLayout = { figureText = it },
                        modifier = Modifier.onGloballyPositioned {
                            figureAt = it.positionInParent()
                        },
                    )
                    Text(
                        text = units.temperature.suffix,
                        style = WetterTheme.type.readingUnit,
                        color = colors.textTertiary,
                        onTextLayout = { unitText = it },
                        modifier = Modifier
                            .padding(start = 3.dp, top = 16.dp)
                            .onGloballyPositioned { unitAt = it.positionInParent() },
                    )
                }
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(seen.labelRes()),
                    style = WetterTheme.type.title,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    onTextLayout = { wordText = it },
                    modifier = Modifier.onGloballyPositioned { wordAt = it.positionInParent() },
                )
            }
        }

        MarkExplanation(
            mark = explaining,
            sky = sky,
            hazards = hazards,
            now = now,
            zone = zone,
            windSpeedMs = windSpeed,
            windGustMs = windGust,
            onDismiss = { onExplain(null) },
        )
    }
}

/**
 * Clickable without the ripple: these are marks on a face, and the card that
 * appears is the acknowledgement. Click semantics are unchanged.
 */
private fun Modifier.quietlyClickable(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/** The marks beside the dial, each of which can explain itself. */
enum class PlateMark { UMBRELLA, WIND, STARS, HAZARD }

/**
 * What a tapped mark means, shown beneath the dial so it never covers the
 * temperature, and dismissed by the next tap.
 */
@Composable
private fun MarkExplanation(
    mark: PlateMark?,
    sky: Stargazing.Sky,
    hazards: List<Hazard>,
    now: Instant,
    zone: ZoneId,
    windSpeedMs: Double,
    windGustMs: Double,
    onDismiss: () -> Unit,
) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    // Kept separately from `mark` so the text survives the collapse animation
    // instead of blanking as it starts.
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
            PlateMark.HAZARD -> stringResource(R.string.hazard_title)
        }
        // No paragraph for hazards: the rows are the answer.
        val body: String? = when (shown) {
            PlateMark.UMBRELLA -> stringResource(R.string.explain_umbrella_body)
            PlateMark.WIND -> stringResource(R.string.explain_wind_body)
            PlateMark.STARS -> if (sky.moonWashed) {
                stringResource(R.string.explain_stars_body_moon)
            } else {
                stringResource(R.string.explain_stars_body)
            }
            PlateMark.HAZARD -> null
        }
        // The wind card shows the figure the indicator and the light are derived from.
        val reading = when (shown) {
            // Gusts are stated as a number; the light holds a steady pace.
            PlateMark.WIND -> if (windGustMs > windSpeedMs + GUST_WORTH_SAYING) {
                stringResource(
                    R.string.explain_wind_reading,
                    formatWindSpeed(windSpeedMs, units.wind),
                    formatWindSpeed(windGustMs, units.wind),
                )
            } else {
                formatWindSpeed(windSpeedMs, units.wind)
            }
            PlateMark.UMBRELLA -> null
            // The share of open sky behind the judgement.
            PlateMark.STARS -> stringResource(
                R.string.explain_stars_reading,
                (sky.clarity * PERCENT).roundToInt(),
            )
            PlateMark.HAZARD -> null
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
            if (shown == PlateMark.HAZARD) {
                // Every hazard, not just the worst: a gale followed by ice is two things to know.
                hazards.forEach { hazard ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(hazard.labelRes()),
                            style = WetterTheme.type.body,
                            color = if (hazard.severity == HazardSeverity.DANGER) {
                                colors.warning
                            } else {
                                colors.textPrimary
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatHazardWindow(hazard, now, zone),
                            style = WetterTheme.type.meta,
                            color = colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.height(spacing.s))
            }
            if (shown == PlateMark.WIND) {
                // The three bands as a short scale, with the current one picked out.
                val level = windLevel(windSpeedMs)
                WindBands(level)
                Spacer(Modifier.height(spacing.m))
            }
            if (body != null) {
                Text(text = body, style = WetterTheme.type.body, color = colors.textSecondary)
            }
        }
    }
}

/**
 * The three wind bands as a scale, formatted from the same constants the
 * indicator switches on.
 */
@Composable
private fun WindBands(currentLevel: Int) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors
    val moderate = formatWindSpeed(MODERATE_WIND_MS, units.wind)
    val strong = formatWindSpeed(STRONG_WIND_MS, units.wind)

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
 * Wind as three stacked lines that fill like a level. All three are always
 * drawn, so the level can be read.
 */
@Composable
private fun WindLevels(speedMs: Double, modifier: Modifier = Modifier) {
    val colour = WetterTheme.colors.textPrimary
    val level = windLevel(speedMs)
    val description = stringResource(windLevelLabel(level))

    Canvas(modifier.semantics { contentDescription = description }) {
        val gap = size.height / (WIND_LINES + 1)
        repeat(WIND_LINES) { index ->
            val lit = index >= WIND_LINES - level
            drawWave(
                y = gap * (index + 1),
                gap = gap,
                colour = colour.copy(alpha = if (lit) Emphasis.FULL else Emphasis.GHOST),
            )
        }
    }
}

/** One wind line: a shallow wave. */
private fun DrawScope.drawWave(y: Float, gap: Float, colour: Color) {
    val amplitude = gap * WAVE_AMPLITUDE
    val path = Path()
    path.moveTo(0f, y)
    var x = 0f
    while (x <= size.width) {
        val phase = (x / size.width) * WAVE_CYCLES * TAU
        // Minus, because screen y grows downward.
        path.lineTo(x, y - amplitude * sin(phase))
        x += WAVE_RESOLUTION
    }
    drawPath(path, colour, style = Stroke(width = WAVE_STROKE.toPx(), cap = StrokeCap.Round))
}

/** Three bands near the Beaufort boundaries: a breeze, a wind, and a strong wind. */
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
 * The light's angle, advanced every frame by however far the current wind
 * justifies. Integrating rather than running a fixed-duration animation means a
 * change of wind changes the speed smoothly, without restarting the lap.
 */
@Composable
private fun rememberBeamAngle(windSpeedMs: Double): Float {
    // Read inside the frame callback so the loop sees the latest wind without restarting.
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
 * Seconds per lap at [windSpeedMs], or null in still air. The rate rises with
 * the wind (exaggerated by [DRAMA]) between [SLOWEST_LAP_S] and [FASTEST_LAP_S].
 */
private fun lapSecondsFor(windSpeedMs: Double): Float? {
    if (windSpeedMs < STILL_AIR_MS) return null
    val ratio = REFERENCE_MS / windSpeedMs
    return (REFERENCE_LAP_S * Math.pow(ratio, DRAMA))
        .coerceIn(FASTEST_LAP_S, SLOWEST_LAP_S)
        .toFloat()
}

/**
 * The body of the dial: a shallow porcelain well lit from above, shadowed under
 * the near lip and catching light on the far wall.
 */
internal fun DrawScope.drawPorcelain(face: Color, lip: Color, floorCatch: Color, occlusion: Color) {
    val radius = ringRadius()

    // A recess, not a dome: darker at the top under the lip, lighter at the bottom.
    // A diffuse ramp, because the surface is matte.
    drawCircle(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to lip,
                FORM_MIDPOINT to face,
                1f to floorCatch,
            ),
            startY = center.y - radius,
            endY = center.y + radius,
        ),
        radius = radius,
    )

    // Contact shadow under the lip, in the edge's ink and kept to the rim so it
    // never becomes a vignette.
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                RIM_CONTACT to Color.Transparent,
                1f to occlusion.copy(alpha = Emphasis.MUTED),
            ),
            center = Offset(center.x, center.y + radius * OCCLUSION_BIAS),
            radius = radius * OCCLUSION_SPREAD,
        ),
        radius = radius,
    )
}

/** The fired edge: one hairline. */
internal fun DrawScope.drawFiredEdge(edge: Color) {
    drawCircle(color = edge, radius = ringRadius(), style = Stroke(EDGE_WIDTH.toPx()))
}

/** One tick an hour, inside the edge. Twelve at the top, clockwise. */
internal fun DrawScope.drawHourTicks(colour: Color) {
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
 * The pointer for now: a small triangle inside the rim aimed at the hour scale,
 * so it reads as pointing rather than as another tick, and never crosses the
 * temperature.
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
 * The travelling light: one sweep gradient rotated whole, in the face's own tone
 * rather than the rain colour, so it is never read as rain.
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
 * The circle everything is measured from: the edge's stroke straddles it, so
 * the ring sits flush with the bounds.
 */
private fun DrawScope.ringRadius(): Float = size.minDimension / 2f - EDGE_WIDTH.toPx() / 2f

/**
 * Where an instant sits on the face, in drawArc degrees (from three o'clock).
 * Twelve hours to a turn, like a clock, so the pointer visibly moves.
 */
private fun angleOf(instant: Instant, zone: ZoneId): Float {
    val local = instant.atZone(zone)
    val minutes = (local.hour % HOURS_ON_FACE) * 60 +
        local.minute +
        local.second / SECONDS_PER_MINUTE
    return -QUARTER_TURN + minutes / MINUTES_ON_FACE * FULL_TURN
}

private val PLATE_MAX = 240.dp

private val MARK_SIZE = 26.dp

/** Clear air between the glass and the marks, so they are beside it, not on it. */
private val MARK_GAP = 10.dp

/** The marks stand on the line tangent to the bottom of the dial. */
private val MARK_BASE_OF_CIRCLE = 1f

/** Where the face's own tone sits in the fall of light: a little above centre. */
private const val FORM_MIDPOINT = 0.46f

/** How far out the edge begins turning away: rim only. */
private const val RIM_CONTACT = 0.90f

/** How far the occlusion is pushed toward the far wall, so it gathers at the top. */
private const val OCCLUSION_BIAS = 0.30f

private const val OCCLUSION_SPREAD = 1.24f

private val EDGE_WIDTH = 3.dp
private val TICK_GAP = 6.dp
private val TICK_LONG = 7.dp
private val TICK_SHORT = 3.5.dp

private val MARK_LENGTH = 11.dp

/** Half the pointer's base. */
private val MARK_WIDTH = 3.dp

private const val HOURS_ON_FACE = 12
private const val DEGREES_PER_HOUR = 360f / HOURS_ON_FACE
private const val MINUTES_ON_FACE = HOURS_ON_FACE * 60f

/** Long ticks at twelve, three, six and nine. */
private const val QUARTERS = 3
private const val QUARTER_TURN = 90f
private const val FULL_TURN = 360f
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val SECONDS_PER_MINUTE = 60f

private val CARD_CORNER = 14.dp

/** Below this a gust is not meaningfully different from the mean. */
private const val GUST_WORTH_SAYING = 0.6

private const val TAU = 6.2831855f

private const val BEAM_ALPHA = Emphasis.MUTED

private const val WIND_LINES = 3
private const val WAVE_CYCLES = 1f

/** As a fraction of the gap between lines, so neighbouring waves cannot touch. */
private const val WAVE_AMPLITUDE = 0.42f
private const val WAVE_RESOLUTION = 1f
private val WAVE_STROKE = 2.dp

/**
 * Near the Beaufort 4 and 6 boundaries (5.5 and 10.8 m/s), rounded to whole
 * numbers so the scale the card prints and the levels the indicator switches at
 * are the same numbers.
 */

private const val MODERATE_WIND_MS = 6.0
private const val STRONG_WIND_MS = 11.0

/** Below this the air is still and the light holds. */
private const val STILL_AIR_MS = 0.5

/** The anchor for the rotation: a 5 m/s breeze takes four seconds a lap. */
private const val REFERENCE_MS = 5.0
private const val REFERENCE_LAP_S = 4.0

/**
 * How much the lap time exaggerates a difference in wind, so that ordinary days
 * look different at a glance. An indicator, not a model of a rotor.
 */
private const val DRAMA = 2.0

/** One lap a second, reached near 10 m/s, where the level indicator turns strong. */
private const val FASTEST_LAP_S = 1.0

/** A light breeze: a slow crawl. */
private const val SLOWEST_LAP_S = 22.0

/**
 * The mark for a night worth looking up at: one bright four-pointed star and two
 * lesser ones, concave-sided so they read as flares.
 */
@Composable
private fun StarsMark(modifier: Modifier = Modifier) {
    val colour = WetterTheme.colors.night
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
        val angle = step * (TAU / (STAR_POINTS * 2)) - TAU / 4
        val x = centre.x + reach * cos(angle)
        val y = centre.y + reach * sin(angle)
        if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, colour)
}

/** Companions, drawn back so the main star is read first. */
private const val LESSER_STAR = Emphasis.MUTED

private const val STAR_POINTS = 4

/** How far the star pinches between its points. */
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

/**
 * The mark for weather that can hurt somebody: a warning triangle in the
 * palette's warning colour. The threshold for raising it is set in
 * [lv.bolwarra.wetter.domain.hazard.Hazards].
 */
@Composable
private fun HazardMark(severity: HazardSeverity, modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors
    val description = stringResource(R.string.plate_hazard)
    val colour = hazardInk(severity, colors.warning)

    Canvas(modifier.semantics { contentDescription = description }) {
        drawHazardTriangle(colour)
    }
}

/** A warning still to come is drawn fainter than one in force. */
internal fun hazardInk(severity: HazardSeverity, warning: Color): Color =
    if (severity == HazardSeverity.DANGER) warning else warning.copy(alpha = WARNING_AHEAD)

/** The triangle and its bang, centred in whatever box it is given. */
internal fun DrawScope.drawHazardTriangle(colour: Color) {
    val width = size.minDimension
    val height = width * TRIANGLE_RATIO
    val left = (size.width - width) / 2f
    val top = (size.height - height) / 2f
    val corner = width * TRIANGLE_CORNER

    val path = Path().apply {
        moveTo(left + width / 2f, top)
        lineTo(left + width, top + height)
        lineTo(left, top + height)
        close()
    }
    drawPath(path, colour, style = Stroke(width = corner, join = StrokeJoin.Round))

    // The bang: a bar and a dot, inset so the triangle reads first.
    val barTop = top + height * BANG_TOP
    val barBottom = top + height * BANG_BOTTOM
    drawLine(
        colour,
        Offset(left + width / 2f, barTop),
        Offset(left + width / 2f, barBottom),
        strokeWidth = corner,
        cap = StrokeCap.Round,
    )
    drawCircle(
        colour,
        radius = corner * 0.6f,
        center = Offset(
            left + width / 2f,
            top + height * BANG_DOT,
        ),
    )
}

/** A hazard that has not started yet is drawn back this far. */
private const val WARNING_AHEAD = Emphasis.STRONG

/** Slightly wider than tall, which is how a road sign reads. */
private const val TRIANGLE_RATIO = 0.88f

private const val TRIANGLE_CORNER = 0.1f
private const val BANG_TOP = 0.34f
private const val BANG_BOTTOM = 0.62f
private const val BANG_DOT = 0.78f
