package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The app's one structural device: a small tracked label, an optional reading on
 * the right, and a hairline beneath.
 *
 * Sections are separated by a line and a gap rather than by cards. Cards imply
 * each block is a separate object; here they are consecutive readings from the
 * same instrument, and the eye should run straight down them (docs/design-principles.md).
 */
@Composable
fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Column(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                // Kotlin's uppercase() is locale-independent, so this cannot
                // surprise us in Turkish the way Java's toUpperCase() would.
                text = label.uppercase(),
                style = WetterTheme.type.sectionLabel,
                color = WetterTheme.colors.textTertiary,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = WetterTheme.type.meta,
                    color = WetterTheme.colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(WetterTheme.spacing.s))
        HairlineRule()
    }
}
