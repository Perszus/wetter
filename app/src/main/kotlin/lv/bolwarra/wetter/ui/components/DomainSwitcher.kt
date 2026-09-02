package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * A pill, centred, sized to its words rather than stretched across the screen —
 * a control this small has no business being as wide as the content it switches.
 *
 * Only the selected segment is filled, because the one thing this is here to say
 * is which page you are looking at; lighting all three would say nothing. The
 * fill is a raised ground rather than the accent, and that is deliberate:
 * precipitation owns the only saturated hue in this app, and a permanent
 * coloured block sitting directly above the rain chart would compete with the
 * thing it is there to make you look at.
 *
 * The horizontal padding inside each segment is doing real work. On a fully
 * rounded shape the ends curve away from the text, so a label set tight against
 * them reads as crowded even when it technically fits.
 */
@Composable
fun DomainSwitcher(
    selected: WeatherDomain,
    onSelect: (WeatherDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val pill = RoundedCornerShape(percent = 50)

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(TRACK_HEIGHT)
                .clip(pill)
                .background(colors.surfaceSunken)
                .border(width = 1.dp, color = colors.hairline, shape = pill)
                .padding(TRACK_INSET)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WeatherDomain.entries.forEach { domain ->
                val isSelected = domain == selected
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(pill)
                        .background(if (isSelected) colors.surfaceRaised else colors.surfaceSunken)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(domain) },
                        )
                        .padding(horizontal = SEGMENT_PADDING),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(domain.label),
                        style = WetterTheme.type.body,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) colors.textPrimary else colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private val TRACK_HEIGHT = 38.dp
private val TRACK_INSET = 3.dp

/** Enough that a fully rounded end never crowds the word inside it. */
private val SEGMENT_PADDING = 20.dp
