package lv.bolwarra.wetter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.ui.format.formatTemperature
import lv.bolwarra.wetter.ui.format.labelRes
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The present reading: one large number, the sky in words, and what it feels
 * like. Nothing is boxed, and the temperature is the only thing set large —
 * everything below it on the screen is about what happens next, which is the
 * actual subject of the app.
 */
@Composable
fun CurrentConditions(
    current: CurrentWeather,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors

    Column(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = formatTemperature(current.temperature),
                style = WetterTheme.type.reading,
                color = colors.textPrimary,
            )
            // The unit rides at cap height rather than sitting on the baseline,
            // so the numeral keeps the optical left edge of the column.
            Text(
                text = stringResource(R.string.unit_celsius),
                style = WetterTheme.type.readingUnit,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 6.dp, top = 10.dp),
            )
        }

        Spacer(Modifier.height(WetterTheme.spacing.xs))

        Text(
            text = stringResource(current.condition.labelRes()),
            style = WetterTheme.type.title,
            color = colors.textSecondary,
        )

        current.apparentTemperature?.let { apparent ->
            Spacer(Modifier.height(WetterTheme.spacing.xs))
            Text(
                text = stringResource(
                    R.string.current_feels_like,
                    formatTemperature(apparent),
                ),
                style = WetterTheme.type.meta,
                color = colors.textTertiary,
            )
        }
    }
}
