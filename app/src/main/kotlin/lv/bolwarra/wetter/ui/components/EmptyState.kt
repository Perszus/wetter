package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Shown when there is genuinely nothing to draw. States what is true and offers
 * the one action that changes it — no illustration, no apology.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(WetterTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = WetterTheme.type.title,
            color = WetterTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Spacer(Modifier.height(WetterTheme.spacing.s))
            Text(
                text = detail,
                style = WetterTheme.type.body,
                color = WetterTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(WetterTheme.spacing.l))
            TextButton(onClick = onAction) {
                Text(actionLabel.uppercase(), style = WetterTheme.type.sectionLabel)
            }
        }
    }
}
