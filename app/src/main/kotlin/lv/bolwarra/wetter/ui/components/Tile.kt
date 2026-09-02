package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val TILE_RADIUS = 12.dp
