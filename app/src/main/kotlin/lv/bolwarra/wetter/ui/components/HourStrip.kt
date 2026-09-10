package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.curve.RainCurveBands
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.formatTime
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.Emphasis
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * A day, hour by hour, travelled sideways.
 *
 * A column per hour: the time, the sky as a mark, the temperature, and how hard
 * it is raining. Twenty-four of them do not fit across a phone and are not meant
 * to — the strip is pushed rather than squeezed, which is the same rule the Today
 * chart follows. A column is a fixed width, so the same hour is the same size on
 * every day of the week and two days can be compared by scrolling to the same
 * place in each.
 *
 * ### The rain is a bar, not a number
 *
 * `0.4 mm` in a table is a number almost nobody has a feel for, which is the
 * finding that fixed the Today chart's vertical axis, and it is no truer in a
 * column than it was on a chart. So the rain is height, on [RainCurveBands] —
 * the same fixed axis the chart and the widget are drawn against. A bar here and
 * a peak there mean the same wetness, because they are measured from the same
 * geometry.
 *
 * A dry hour draws nothing at all. That is not an omission: the floor of that
 * axis is dry, and the question this strip is skimmed for is which hours are
 * wet.
 *
 * ### Finding the wet hour
 *
 * The bar alone was not enough, and the case that proved it is the ordinary one:
 * a Baltic Friday whose forecast is drizzle, one wet hour in twenty-four, at
 * 0.4 mm. The day is honestly marked as wet, and on this track that hour drew a
 * two-pixel stub you had to scroll past the other twenty-three to find. A
 * reader's fair conclusion was that the day's mark was lying.
 *
 * So a wet hour is said three times over, at three sizes: the hour is washed in
 * the rain colour, so it can be picked out of a strip while it is still moving;
 * its time is set in that colour, which survives at any size and in any theme;
 * and the bar says how hard, once you have stopped on it. Only the third of
 * those is a measurement. The other two exist to get you to it.
 */
@Composable
fun HourStrip(hours: List<HourlyWeather>, zone: ZoneId, modifier: Modifier = Modifier) {
    val spacing = WetterTheme.spacing

    if (hours.isEmpty()) {
        // Beyond a provider's hourly reach. Said plainly, because a drawer that
        // opens onto nothing reads as something broken - and it is a real limit
        // rather than a fault: MET Norway drops to six-hourly steps after about
        // two and a half days, and six-hourly steps are not hours.
        Text(
            text = stringResource(R.string.week_no_hours),
            style = WetterTheme.type.meta,
            color = WetterTheme.colors.textTertiary,
        )
        return
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            hours.forEach { hour -> HourColumn(hour, zone) }
        }
        // The floor the bars stand on, drawn once under the whole strip rather
        // than once per column. Per column it was a row of short dashes with
        // gaps between them, which on a dry morning - every bar empty - read as
        // stray marks rather than as a baseline.
        //
        // It does not scroll with the hours, and does not need to: a floor is
        // the same everywhere along a fixed axis, which is the point of having
        // one.
        HairlineRule()
    }
}

@Composable
private fun HourColumn(hour: HourlyWeather, zone: ZoneId) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    // The same bar the sentence uses, so a strip cannot contradict the words
    // above it. A trace hour keeps its place on the curve and loses its mark.
    val wet = hour.intensity.isWorthNaming

    Column(
        modifier = Modifier
            .width(COLUMN)
            .clip(RoundedCornerShape(COLUMN_RADIUS))
            // The wash is the only thing here that can be seen without stopping.
            // Kept at the faintest step the palette has: it has to survive being
            // skimmed, not compete with the bar it is pointing at.
            .background(
                if (wet) colors.textPrimary.copy(alpha = Emphasis.GHOST) else Color.Transparent,
            )
            .padding(vertical = spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatTime(hour.timestamp, zone),
            style = WetterTheme.type.axis,
            // Night is dimmer than day, which is the one piece of context the
            // hour label can carry for free. Without it the strip reads as
            // twenty-four equivalent hours, and half of them are the middle of
            // the night.
            //
            // A wet hour overrides that, because which hours are wet is what
            // this app is for and the difference between two in the afternoon
            // and two in the morning is not.
            color = when {
                wet -> colors.textPrimary
                hour.isDay -> colors.textSecondary
                else -> colors.textTertiary
            },
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        ConditionGlyph(
            condition = hour.appearance,
            contentDescription = stringResource(hour.appearance.labelRes()),
            size = HOUR_GLYPH,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = formatTemperature(hour.temperature, units.temperature),
            style = WetterTheme.type.figure,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.xs))
        RainMark(hour.precipitationRate)
    }
}

/**
 * One hour's rain, as height on the shared axis.
 *
 * The band is held at full height whether anything is in it or not, so the
 * hours stay on one scale and a wet hour among dry ones is a bar rising out of
 * a flat run rather than a row that grew taller than its neighbours.
 */
@Composable
private fun RainMark(millimetresPerHour: Double?) {
    val colors = WetterTheme.colors
    // Spread inside the light band, the same way the widget's short track is.
    // Without it the drizzle this strip most often has to report is a couple of
    // pixels tall - see RainCurveBands.spreadWithinLight.
    val fraction = RainCurveBands
        .spreadWithinLight(RainCurveBands.heightFraction((millimetresPerHour ?: 0.0).toFloat()))
        .coerceIn(0f, 1f)

    Box(
        modifier = Modifier.width(MARK_WIDTH).height(MARK_HEIGHT),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(topStart = MARK_WIDTH / 2, topEnd = MARK_WIDTH / 2))
                    .background(colors.textPrimary),
            )
        }
    }
}

/** Wide enough for "12:00" in the axis face, and no wider. */
private val COLUMN = 48.dp

/** Smaller than the day's mark: this is a footnote to that one, not a rival. */
private val HOUR_GLYPH = 18.dp

private val MARK_WIDTH = 10.dp
private val COLUMN_RADIUS = 8.dp

/**
 * Shorter than the Today chart's track, and deliberately so. This is a glance at
 * which hours are wet, not a curve to be read off; the full height would give a
 * dry day a tall empty box under every hour of it.
 */
private val MARK_HEIGHT = 22.dp
