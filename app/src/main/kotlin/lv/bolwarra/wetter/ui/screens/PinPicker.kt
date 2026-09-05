package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.ui.components.ScreenTitle
import lv.bolwarra.wetter.ui.map.MapPicker
import lv.bolwarra.wetter.ui.map.TileLoader
import lv.bolwarra.wetter.ui.theme.WetterTheme

/**
 * Putting a point on the map.
 *
 * The answer to the question the place search cannot take: a gazetteer knows
 * settlements, so it returns nothing at all for a street address and nothing
 * whatever for a field behind a house. A coordinate needs no service to resolve
 * and is the exact thing every provider is asked for anyway.
 *
 * What it buys is worth being straight about. The forecast models interpolate to
 * their own grids, one to eleven kilometres wide, and a pin does not make one of
 * them resolve a street. Radar does: it samples at about a kilometre, and inside
 * the first hour the radar is what the timeline is made of. So a pin sharpens
 * the part of the forecast that is actually observed, and leaves the rest where
 * it was.
 *
 * The chosen point is shown as coordinates rather than a name because there is
 * no name - reverse geocoding is another service and another decision
 * (docs/decisions.md). The label is written in the form the search box can read
 * back, so a point kept this way can be found again by typing it.
 */
@Composable
fun PinPicker(
    start: Coordinates,
    tiles: TileLoader,
    onCancel: () -> Unit,
    onConfirm: (Coordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing
    val colors = WetterTheme.colors
    var chosen by remember { mutableStateOf(start) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen),
    ) {
        ScreenTitle(stringResource(R.string.locations_pin_title), onBack = onCancel)
        Spacer(Modifier.height(spacing.m))

        MapPicker(
            centre = start,
            onCentreChanged = { chosen = it },
            tiles = tiles,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(spacing.m)),
        )

        Spacer(Modifier.height(spacing.m))

        // The coordinate, always visible while aiming. It is the only feedback
        // there is that the map has moved to somewhere deliberate rather than
        // somewhere it drifted to, and it is what will be kept.
        Text(
            text = chosen.format(),
            style = WetterTheme.type.body,
            color = colors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(spacing.s))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    text = stringResource(R.string.locations_pin_cancel),
                    style = WetterTheme.type.body,
                    color = colors.textTertiary,
                )
            }
            TextButton(onClick = { onConfirm(chosen) }) {
                Text(
                    text = stringResource(R.string.locations_pin_confirm),
                    style = WetterTheme.type.body,
                    color = colors.precipitation,
                )
            }
        }
        Spacer(Modifier.height(spacing.m))
    }
}
