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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.domain.location.PlaceName
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
    onConfirm: (Coordinates, PlaceName?) -> Unit,
    nameOf: suspend (Coordinates) -> PlaceName?,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing
    val colors = WetterTheme.colors
    var chosen by remember { mutableStateOf(start) }
    var name by remember { mutableStateOf<PlaceName?>(null) }

    // Asked once the map has been still for a moment, never during a drag.
    //
    // The lookup runs against a volunteer-run server, and a pin dragged across
    // a city would otherwise fire one request per frame at it. Restarting on
    // every change and pausing first means exactly one request per place
    // somebody actually stops on - and the name clears the instant the map
    // moves, so what is on screen is never a label for somewhere else.
    LaunchedEffect(chosen) {
        name = null
        delay(SETTLE_MS)
        name = nameOf(chosen)
    }

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

        // The address when there is one, the coordinate when there is not.
        //
        // The coordinate is always shown underneath either way: it is the real
        // identity of the place - the thing the forecast is actually fetched
        // for - and a street name is a description of it that most of the earth
        // does not have.
        val place = name
        if (place != null) {
            Text(
                text = place.label,
                style = WetterTheme.type.body,
                color = colors.textPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            val under = listOfNotNull(place.region, place.country).joinToString(", ")
            if (under.isNotEmpty()) {
                Text(
                    text = under,
                    style = WetterTheme.type.meta,
                    color = colors.textTertiary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Text(
            text = chosen.format(),
            style = if (place == null) WetterTheme.type.body else WetterTheme.type.meta,
            color = colors.textTertiary,
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
            TextButton(onClick = { onConfirm(chosen, name) }) {
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

/** Long enough that a drag is one lookup, short enough to feel immediate. */
private const val SETTLE_MS = 700L
