package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lv.bolwarra.wetter.ui.theme.WetterTheme

/** How much attention the status deserves. Never more than one line of it. */
enum class StatusTone { FRESH, STALE, FAILED }

/**
 * "Cached forecast · 42 min old", "Unable to update".
 *
 * A single quiet line with a small dot, not a banner and not a dialog. The app is
 * still showing real data in every one of these states, so the status must not
 * take attention away from it (docs/design-principles.md).
 */
@Composable
fun StatusLine(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val dot = when (tone) {
        StatusTone.FRESH -> colors.textTertiary
        StatusTone.STALE -> colors.temperatureWarm
        StatusTone.FAILED -> colors.warning
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .background(dot, CircleShape),
        )
        Spacer(Modifier.width(WetterTheme.spacing.s))
        Text(
            text = text,
            style = WetterTheme.type.meta,
            color = if (tone == StatusTone.FRESH) colors.textTertiary else colors.textSecondary,
        )
    }
}
