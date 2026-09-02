package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The precipitation timeline — the thing the app is for.
 *
 * One bar per hour, height for how hard it is falling, drawn against a track
 * that darkens through the night. Everything before now is dimmed, so the
 * boundary between dim and bright *is* the current moment; the hairline only
 * makes it exact. That is why there is no "NOW" caption — the chart already
 * says it, and a label would be a second copy of something to disagree with.
 *
 * Ordinary Compose layout, not a `Canvas` and not a charting library: a bar is a
 * `Box` with a height fraction, and an hour is a column. That keeps the whole
 * component readable and means a change to it is a layout change rather than a
 * drawing problem.
 *
 * ### The scale is absolute, and that is a decision
 *
 * Full height is [CEILING_MM_PER_HOUR] millimetres in the hour, always — never
 * scaled to whatever the heaviest hour in view happens to be. A relative scale
 * would draw a day of drizzle exactly like a day of downpours, which is a lie
 * told in the one place the app exists to be truthful. The cost is that a
 * genuinely light day looks light, which is correct.
 */
@Composable
fun RainTimeline(
    hours: List<HourlyWeather>,
    now: Instant,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    if (hours.isEmpty()) return

    val colors = WetterTheme.colors
    val ordered = hours.sortedBy { it.timestamp }
    val start = ordered.first().timestamp
    val end = ordered.last().timestamp.plus(HOUR)

    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(TRACK_HEIGHT),
        ) {
            val trackWidth = maxWidth

            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                ordered.forEach { hour ->
                    HourColumn(
                        hour = hour,
                        isPast = !hour.timestamp.plus(HOUR).isAfter(now),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            val elapsed = Duration.between(start, now)
            val span = Duration.between(start, end)
            if (!elapsed.isNegative && elapsed <= span && !span.isZero) {
                val fraction = elapsed.toMillis().toFloat() / span.toMillis().toFloat()
                NowMarker(
                    modifier = Modifier.offset(
                        // Half the line's width back, so the hairline straddles
                        // the instant rather than starting at it.
                        x = trackWidth * fraction - MARKER_WIDTH / 2,
                    ),
                )
            }
        }

        HairlineRule()
        Spacer(Modifier.height(WetterTheme.spacing.s))

        Row(Modifier.fillMaxWidth()) {
            ordered.forEach { hour ->
                val localHour = hour.timestamp.atZone(zone).hour
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // Every third hour. Labelling all twenty-four turns the axis
                    // into a grey band and none of them can be read.
                    if (localHour % AXIS_LABEL_EVERY == 0) {
                        Text(
                            text = "%02d".format(localHour),
                            style = WetterTheme.type.axis,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HourColumn(hour: HourlyWeather, isPast: Boolean, modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors

    Box(
        modifier = modifier
            .fillMaxHeight()
            // The night wash is drawn per hour with no gap between columns, so
            // adjacent night hours merge into one continuous band.
            .background(if (hour.isDay) Color.Transparent else colors.night)
            .alpha(if (isPast) PAST_ALPHA else 1f),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val fraction = hour.barFraction()
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(BAR_WIDTH_FRACTION)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(topStart = BAR_CAP, topEnd = BAR_CAP))
                    .background(hour.barColour(colors.precipitationMuted, colors.precipitation)),
            )
        }
    }
}

@Composable
private fun NowMarker(modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors
    Box(modifier.fillMaxHeight().width(MARKER_WIDTH)) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(MARKER_WIDTH)
                .background(colors.textPrimary.copy(alpha = MARKER_ALPHA)),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(MARKER_DOT)
                .background(colors.textPrimary, CircleShape),
        )
    }
}

/**
 * How tall this hour's bar is, as a fraction of the track.
 *
 * Anything wet gets at least [MIN_VISIBLE_FRACTION] so that a trace of drizzle
 * is a visible stub rather than nothing — the difference between "barely" and
 * "not at all" is one somebody deciding whether to take a coat actually wants.
 */
private fun HourlyWeather.barFraction(): Float {
    if (!intensity.isWet) return 0f
    val mm = precipitation ?: return MIN_VISIBLE_FRACTION
    return (mm / CEILING_MM_PER_HOUR).toFloat().coerceIn(MIN_VISIBLE_FRACTION, 1f)
}

/**
 * Height says how much; colour says how sure.
 *
 * A confident hour is full strength, a doubtful one washes towards the muted
 * tone. The range is deliberately narrow — wide enough to notice across the
 * chart, never so wide that a low-probability hour reads as a different
 * quantity rather than the same one held less firmly.
 *
 * No probability at all means full strength. The provider gave an amount without
 * a confidence; muting it would be the app inventing a doubt nothing told it
 * about.
 */
private fun HourlyWeather.barColour(muted: Color, full: Color): Color {
    val probability = precipitationProbability ?: return full
    val confidence = (probability / 100f).coerceIn(0f, 1f)
    return lerp(muted, full, MIN_CONFIDENCE_TINT + (1f - MIN_CONFIDENCE_TINT) * confidence)
}

private val HOUR: Duration = Duration.ofHours(1)

private val TRACK_HEIGHT = 104.dp
private val MARKER_WIDTH = 1.5.dp
private val MARKER_DOT = 5.dp
private val BAR_CAP = 2.dp

/** A shower at the top of the heavy band fills the track. Above that it clips. */
private const val CEILING_MM_PER_HOUR = 8.0

private const val BAR_WIDTH_FRACTION = 0.62f
private const val MIN_VISIBLE_FRACTION = 0.045f
private const val PAST_ALPHA = 0.32f
private const val MARKER_ALPHA = 0.55f

/** Even a 10% hour keeps some of the rain hue, or the chart loses the bar entirely. */
private const val MIN_CONFIDENCE_TINT = 0.35f

private const val AXIS_LABEL_EVERY = 3
