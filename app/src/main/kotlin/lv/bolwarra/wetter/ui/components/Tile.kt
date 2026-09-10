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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * One reading, on its own ground.
 *
 * A tile is a block of raised surface with a tracked label, and deliberately not
 * a Material card: no shadow, no border, no elevation. The separation comes from
 * a single step of tone, which is enough to group the contents and quiet enough
 * that a page of tiles still reads as one instrument rather than as a pile of
 * floating objects.
 *
 * The label repeats the section vocabulary used elsewhere — small, tracked wide,
 * upper case — so a tile heading and a section heading are recognisably the same
 * kind of thing.
 *
 * @param trailing a reading that belongs to the whole tile, set at its right:
 *   the day's total, the peak, the count. It is the one number somebody can take
 *   from the tile without reading the rest of it.
 */
@Composable
fun Tile(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    /**
     * Whether new numbers are on their way, drawn as a turning arc beside the
     * label.
     *
     * After the label and not over the content, because the content is not
     * wrong while this is happening - it is the last forecast, which is still
     * the best answer there is until a better one lands. Covering it with a
     * spinner would hide a true reading to announce that a truer one is coming.
     */
    busy: Boolean = false,
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
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = WetterTheme.type.meta,
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(spacing.m))
        content()
    }
}

/**
 * A tile that stays shut until asked.
 *
 * For readings that are real but not daily: dew point, pressure, the moon. Left
 * on the main screen they cost every reader attention every day to serve the few
 * who want them once, and the rain chart is what this app is for. Behind one tap
 * they cost nothing and are still there.
 *
 * Collapsed by default and not remembered between launches. Somebody who opened
 * it yesterday to check the pressure did not thereby ask for it every morning.
 *
 * @param summary a hint at what is inside, shown only while collapsed - a closed
 *   drawer labelled only "Advanced" gives no reason to open it.
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
    // Turned on the same curve and over the same time as the panel it belongs
    // to, so the arrow and the drawer arrive together rather than the arrow
    // finishing first and waiting.
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
 * The mark for "asking again": the dial's own triangle, turning.
 *
 * Drawn rather than borrowed, and drawn as a triangle rather than a ring
 * because this app already has a small filled triangle that means "where you
 * are" - the mark inside the dial's rim. Reusing that shape makes the second
 * one read as the same family rather than as a control panel part that wandered
 * in from another app.
 *
 * A solid triangle also survives being small far better than a hairline arc: at
 * ten density-independent pixels a stroked ring is two or three physical pixels
 * wide and turns into a grey smudge, while a filled shape keeps its corners and
 * its direction.
 */
@Composable
private fun TurningArc(colour: Color, modifier: Modifier = Modifier) {
    val turning = rememberInfiniteTransition(label = "tile busy")
    val angle by turning.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            // Linear, because an eased rotation reads as a stutter rather than
            // as a turn.
            animation = tween(durationMillis = TURN_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tile busy angle",
    )

    Canvas(modifier.size(MARK_SIZE)) {
        rotate(degrees = angle) {
            // Equilateral, built from three points a third of a turn apart on
            // one circle rather than from a width and a height. That is what
            // makes the sides equal, and it also puts the centre of the shape
            // on the centre of rotation - a triangle laid out as a box wobbles
            // as it turns, because its centroid is not the middle of the box.
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
