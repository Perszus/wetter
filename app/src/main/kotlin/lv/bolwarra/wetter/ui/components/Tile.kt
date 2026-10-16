// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * One reading, on its own ground: a block of raised surface with a tracked label.
 * Not a Material card: no shadow, border or elevation, just one step of tone, so a
 * page of tiles reads as one instrument.
 *
 * @param trailing a reading for the whole tile, set at its right: the one number
 * somebody can take without reading the rest.
 */
@Composable
fun Tile(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    /**
     * Whether new numbers are on their way, drawn as a turning mark beside the label
     * rather than over the content, which stays the best answer until a newer one
     * lands.
     */
    busy: Boolean = false,
    /**
     * Somewhere further to go from this reading, set after [trailing]. Its touch target
     * overhangs the header rather than heightening it.
     */
    action: TileAction? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TILE_RADIUS))
            .background(colors.surfaceRaised)
            .padding(horizontal = spacing.l, vertical = spacing.l),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.uppercase(),
                    style = WetterTheme.type.sectionLabel,
                    color = colors.textTertiary,
                )
                if (busy) {
                    Spacer(Modifier.width(spacing.s))
                    TurningArc(colour = colors.textTertiary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trailing != null) {
                    Text(
                        text = trailing,
                        style = WetterTheme.type.meta,
                        color = colors.textSecondary,
                    )
                }
                if (action != null) {
                    Spacer(Modifier.width(spacing.s))
                    ActionMark(action)
                }
            }
        }
        Spacer(Modifier.height(spacing.m))
        content()
    }
}

/** A way out of a tile: what it is called, how it is drawn, and where it goes. */
class TileAction(
    val label: String,
    val glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
    val onClick: () -> Unit,
)

/**
 * The action's mark, laid out at its glyph's size but touchable across a full
 * target. The overhang falls inside the tile's padding.
 */
@Composable
private fun ActionMark(action: TileAction) {
    val colors = WetterTheme.colors
    val target = WetterTheme.spacing.touchTarget
    Canvas(
        Modifier
            .layout { measurable, _ ->
                val side = target.roundToPx()
                val shown = ACTION_GLYPH.roundToPx()
                val placeable = measurable.measure(
                    androidx.compose.ui.unit.Constraints.fixed(side, side),
                )
                layout(shown, shown) {
                    placeable.place((shown - side) / 2, (shown - side) / 2)
                }
            }
            .clip(RoundedCornerShape(ACTION_RADIUS))
            .clickable(onClickLabel = action.label, onClick = action.onClick)
            .semantics { contentDescription = action.label },
    ) {
        val inset = (size.minDimension - ACTION_GLYPH.toPx()) / 2f
        inset(inset) { action.glyph(this, colors.textSecondary) }
    }
}

private val ACTION_GLYPH = 20.dp
private val ACTION_RADIUS = 12.dp

/**
 * A tile that stays shut until asked, for readings that are real but not daily.
 * Collapsed on every launch.
 *
 * @param summary a hint at what is inside, shown while collapsed.
 */
@Composable
fun ExpandableTile(
    label: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    var expanded by rememberSaveable { mutableStateOf(false) }
    // On the same curve and duration as the panel, so arrow and drawer arrive
    // together.
    val turn by animateFloatAsState(
        targetValue = if (expanded) HALF_TURN else 0f,
        animationSpec = Reveal.chevron,
        label = "chevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TILE_RADIUS))
            .background(colors.surfaceRaised)
            .clickable { expanded = !expanded }
            .padding(horizontal = spacing.l, vertical = spacing.l),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label.uppercase(),
                style = WetterTheme.type.sectionLabel,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f, fill = false),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary != null && !expanded) {
                    Text(
                        text = summary,
                        style = WetterTheme.type.meta,
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(spacing.s))
                }
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .size(CHEVRON)
                        .rotate(turn),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = Reveal.enter,
            exit = Reveal.exit,
        ) {
            Column {
                Spacer(Modifier.height(spacing.m))
                content()
            }
        }
    }
}

private val TILE_RADIUS = 12.dp
private val CHEVRON = 20.dp
private const val HALF_TURN = 180f

/**
 * The mark for "asking again": the dial's own triangle, turning. A filled shape
 * keeps its corners at ten dp, where a hairline ring smudges.
 */
@Composable
internal fun TurningArc(colour: Color, modifier: Modifier = Modifier) {
    val turning = rememberInfiniteTransition(label = "tile busy")
    val angle by turning.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            // Linear: an eased rotation reads as a stutter.
            animation = tween(durationMillis = TURN_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tile busy angle",
    )

    Canvas(modifier.size(MARK_SIZE)) {
        rotate(degrees = angle) {
            // Equilateral, from three points on one circle, so the centroid sits on the centre
            // of rotation and the shape does not wobble.
            val radius = size.minDimension / 2f
            val path = Path()
            repeat(SIDES) { corner ->
                val turn = Math.toRadians(START_DEGREES + corner * (360.0 / SIDES))
                val x = center.x + radius * cos(turn).toFloat()
                val y = center.y + radius * sin(turn).toFloat()
                if (corner == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, colour)
        }
    }
}

/** Sized to a section label's own height, so the row does not grow around it. */
private val MARK_SIZE = 10.dp

private const val SIDES = 3

/** Straight up, so a still frame reads as a mark rather than as a wedge. */
private const val START_DEGREES = -90.0

private const val TURN_MILLIS = 1100
