package lv.bolwarra.wetter.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.unit.dp
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
            Text(
                text = label.uppercase(),
                style = WetterTheme.type.sectionLabel,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f, fill = false),
            )
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
