// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
 * A column per hour: the time, the sky as a mark, the temperature, and how hard it
 * is raining. The strip scrolls rather than squeezes, and a column is a fixed
 * width, so the same hour is the same size on every day.
 *
 * The rain is height on [RainCurveBands], the axis the chart and widgets share; a
 * dry hour draws nothing. A wet hour is also washed and its time set in full ink,
 * so it can be picked out while the strip is still moving.
 */
@Composable
fun HourStrip(hours: List<HourlyWeather>, zone: ZoneId, modifier: Modifier = Modifier) {
    val spacing = WetterTheme.spacing

    if (hours.isEmpty()) {
        // Beyond a provider's hourly reach, said plainly so the drawer does not look
        // broken: MET Norway drops to six-hourly steps after about two and a half days.
        Text(
            text = stringResource(R.string.week_no_hours),
            style = WetterTheme.type.meta,
            color = WetterTheme.colors.textTertiary,
        )
        return
    }

    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fadedEdges(scroll)
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            hours.forEach { hour -> HourColumn(hour, zone) }
        }
        // The floor the bars stand on, drawn once under the whole strip. It does not
        // scroll: a floor is the same everywhere along a fixed axis.
        HairlineRule()
    }
}

/** Fades each end that has more beyond it, so the strip reads as continuing. */
private fun Modifier.fadedEdges(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = EDGE_FADE.toPx()
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            val start = size.width - fade
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.Transparent),
                    start,
                    size.width,
                ),
                topLeft = Offset(start, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun HourColumn(hour: HourlyWeather, zone: ZoneId) {
    val units = WetterTheme.units
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    // The same bar the sentence uses, so the strip cannot contradict the words above
    // it.
    val wet = hour.intensity.isWorthNaming

    Column(
        modifier = Modifier
            .width(COLUMN)
            .clip(RoundedCornerShape(COLUMN_RADIUS))
            // The wash, at the faintest step the palette has: it survives skimming without
            // competing with the bar.
            .background(
                if (wet) colors.textPrimary.copy(alpha = Emphasis.GHOST) else Color.Transparent,
            )
            .padding(vertical = spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatTime(hour.timestamp, zone),
            style = WetterTheme.type.axis,
            // Night hours are dimmer, which the label carries for free. A wet hour overrides
            // that.
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
            isDay = hour.isDay,
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
 * One hour's rain, as height on the shared axis. The band is held at full height
 * either way, so the hours stay on one scale.
 */
@Composable
private fun RainMark(millimetresPerHour: Double?) {
    val colors = WetterTheme.colors
    // Spread inside the light band, as on the widget's short track, so drizzle is more
    // than a couple of pixels (RainCurveBands.spreadWithinLight).
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

/** Smaller than the day's mark: a footnote to it. */
private val HOUR_GLYPH = 18.dp

private val MARK_WIDTH = 10.dp
private val EDGE_FADE = 28.dp
private val COLUMN_RADIUS = 8.dp

/**
 * Shorter than the Today chart's track: a glance at which hours are wet, not a
 * curve to read off.
 */
private val MARK_HEIGHT = 22.dp
