package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The location, and the two ways out of the weather screen.
 *
 * The place name is the navigation control — tapping it opens the location list.
 * That removes a whole navigation bar from the bottom of the screen for the cost
 * of one chevron, which is the trade docs/design-principles.md asks for.
 */
@Composable
fun WeatherHeader(
    locationName: String?,
    onOpenLocations: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpenLocations)
                .defaultMinSize(minHeight = spacing.touchTarget)
                .padding(vertical = spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    text = locationName ?: stringResource(R.string.state_choose_location),
                    style = WetterTheme.type.place,
                    color = if (locationName != null) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.nav_locations),
                tint = colors.textTertiary,
                modifier = Modifier
                    .padding(start = spacing.xs)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp),
            )
        }

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.nav_settings),
                tint = colors.textTertiary,
            )
        }
    }
}
