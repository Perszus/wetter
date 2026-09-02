package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * A secondary screen's heading: back, then the title on the same line.
 *
 * No app bar, no elevation, no centred title. The heading sits in the same
 * column as the content beneath it, so the left edge of the page is a single
 * unbroken line down the screen.
 */
@Composable
fun ScreenTitle(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = WetterTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            // Pulled left by the icon button's own padding so the glyph, not its
            // touch target, aligns with the screen's text column.
            modifier = Modifier.offset(x = -spacing.m),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = WetterTheme.colors.textSecondary,
            )
        }
        Spacer(Modifier.width(spacing.xs))
        Text(
            text = title,
            style = WetterTheme.type.place,
            color = WetterTheme.colors.textPrimary,
        )
    }
}
