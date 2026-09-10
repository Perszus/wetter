package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * One setting: what it is, and the answers, laid out as a row of segments.
 *
 * A segmented row rather than a dropdown or a dialog. Every one of these has two
 * to four answers and all of them fit across a phone, so the whole question and
 * its whole answer can be read at once - a menu would hide the alternatives
 * behind a tap and tell the reader nothing except what they already chose.
 *
 * It is the same shape as the domain switcher above the forecast, on purpose:
 * this app has one control for choosing one of a few things, and a second one
 * that looked different would be a second thing to learn.
 *
 * ### The selected segment is filled, not ticked
 *
 * A tick or a radio dot puts a second mark inside a control that is already
 * capable of showing its own state. Filling the chosen segment says it with the
 * thing itself, and leaves the row readable at a glance down a page of settings:
 * the answers are the only ink that moves.
 */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Composable so an option can name itself from a string resource, which
     * every one of them here does - a unit's name is translated text, not a
     * property of the unit.
     */
    describe: @Composable (T) -> String,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = WetterTheme.type.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(spacing.s))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TRACK_RADIUS))
                .background(colors.surfaceSunken)
                .padding(TRACK_INSET),
            horizontalArrangement = Arrangement.spacedBy(TRACK_INSET),
        ) {
            options.forEach { option ->
                val chosen = option == selected
                Text(
                    text = describe(option),
                    style = WetterTheme.type.body,
                    color = if (chosen) colors.textPrimary else colors.textTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(SEGMENT_RADIUS))
                        .background(if (chosen) colors.surfaceRaised else Color.Transparent)
                        // Selectable rather than clickable, so a screen reader
                        // says "selected" instead of "button" - the row is one
                        // question with one answer, not four separate buttons.
                        .clickable(role = Role.RadioButton) { onSelect(option) }
                        .padding(vertical = spacing.s, horizontal = spacing.xs),
                )
            }
        }
    }
}

private val TRACK_RADIUS = 10.dp
private val SEGMENT_RADIUS = 8.dp

/** The gap that makes the selected segment read as sitting *in* the track. */
private val TRACK_INSET = 3.dp
