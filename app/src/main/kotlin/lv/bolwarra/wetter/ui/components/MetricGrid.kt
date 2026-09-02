package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import lv.bolwarra.wetter.ui.theme.WetterTheme

/** One labelled reading. */
data class Metric(val label: String, val value: String)

/**
 * Secondary readings, two to a row.
 *
 * Two columns rather than a list, because these are short pairs and a single
 * column of them would run the tile down the page for no gain. Labels sit above
 * values rather than beside them so the numbers line up in a column the eye can
 * run down — which is the only reason to put them in a grid at all.
 */
@Composable
fun MetricGrid(metrics: List<Metric>, modifier: Modifier = Modifier) {
    val spacing = WetterTheme.spacing

    Column(modifier.fillMaxWidth()) {
        metrics.chunked(COLUMNS).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(spacing.l))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.l),
            ) {
                row.forEach { metric ->
                    MetricCell(metric, Modifier.weight(1f))
                }
                // Keeps a final odd metric in the left column at its own width
                // instead of letting it stretch across the tile.
                repeat(COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricCell(metric: Metric, modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors
    Column(modifier) {
        Text(
            text = metric.label.uppercase(),
            style = WetterTheme.type.sectionLabel,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(WetterTheme.spacing.xs))
        Text(
            text = metric.value,
            style = WetterTheme.type.figure,
            color = colors.textPrimary,
        )
    }
}

private const val COLUMNS = 2
