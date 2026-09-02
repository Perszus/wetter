package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import java.time.Instant
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.ui.components.Tile
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * The month: how this one is going, against how it usually goes.
 *
 * Not built yet, and it is the page that needs a second data source. No service
 * forecasts a month — Open-Meteo stops at sixteen days and MET Norway at nine —
 * so this page cannot be more forecast. It is month-to-date actuals from
 * Open-Meteo's archive, measured against the long-run normal, with the fortnight
 * of forecast that remains on the end.
 *
 * The stub says so plainly rather than showing an empty page, because an empty
 * page reads as something broken.
 */
@Composable
fun MonthPage(forecast: WeatherForecast, now: Instant, modifier: Modifier = Modifier) {
    val spacing = WetterTheme.spacing

    Column(modifier.fillMaxWidth()) {
        Tile(label = stringResource(R.string.tile_month_pending)) {
            Text(
                text = stringResource(R.string.month_pending),
                style = WetterTheme.type.body,
                color = WetterTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(spacing.s))
            Text(
                text = stringResource(R.string.month_pending_detail),
                style = WetterTheme.type.meta,
                color = WetterTheme.colors.textTertiary,
            )
        }
    }
}
