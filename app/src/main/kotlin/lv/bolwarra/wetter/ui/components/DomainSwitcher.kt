package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lv.bolwarra.wetter.ui.screens.WeatherDomain
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Which page you are on: Today, Week, Month.
 *
 * A recessed track with the selected segment raised out of it. Only the selected
 * segment is filled, because the one thing this control is here to say is which
 * page you are looking at — lighting all three would say nothing.
 *
 * Selection is shown by ground and weight rather than by colour, and that is the
 * whole reason this does not use the accent. Precipitation owns the only
 * saturated hue in the app; spending it on a navigation control that is on
 * screen at all times would put a permanent blue block above a chart whose
 * entire job is to be the blue thing you look at.
 */
@Composable
fun DomainSwitcher(
    selected: WeatherDomain,
    onSelect: (WeatherDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val track = RoundedCornerShape(TRACK_RADIUS)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WetterTheme.spacing.touchTarget)
            .clip(track)
            .background(colors.surfaceSunken)
            .padding(SEGMENT_INSET)
            .selectableGroup(),
    ) {
        WeatherDomain.entries.forEach { domain ->
            val isSelected = domain == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(SEGMENT_RADIUS))
                    .background(
                        if (isSelected) colors.surfaceRaised else colors.surface.copy(alpha = 0f),
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(domain) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(domain.label),
                    style = WetterTheme.type.title,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) colors.textPrimary else colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

private val TRACK_RADIUS = 10.dp
private val SEGMENT_RADIUS = 7.dp
private val SEGMENT_INSET = 3.dp
