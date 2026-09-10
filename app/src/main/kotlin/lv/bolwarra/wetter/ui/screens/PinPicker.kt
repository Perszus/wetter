package lv.bolwarra.wetter.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    /** One fix from the device, or null for every way that can fail. */
    locate: suspend () -> Coordinates?,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing
    val colors = WetterTheme.colors
    val scope = rememberCoroutineScope()

    // Nothing is chosen until somebody taps the map. The screen used to open
    // holding an answer it had been given by nobody, which made the confirm
    // button a trap: pressing it saved wherever the map happened to have opened.
    var chosen by remember { mutableStateOf<Coordinates?>(null) }
    var name by remember { mutableStateOf<PlaceName?>(null) }

    // Where the map is looking. Separate from the choice, and only moved from
    // here when the reader asks to be found.
    var centre by remember { mutableStateOf(start) }
    var locating by remember { mutableStateOf(false) }
    var located by remember { mutableStateOf<Boolean?>(null) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            located = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            locating = true
            val here = locate()
            locating = false
            located = here != null
            if (here != null) {
                centre = here
                chosen = here
            }
        }
    }

    // Asked once the map has been still for a moment, never during a drag.
    //
    // The lookup runs against a volunteer-run server, and a pin dragged across
    // a city would otherwise fire one request per frame at it. Restarting on
    // every change and pausing first means exactly one request per place
    // somebody actually stops on - and the name clears the instant the map
    // moves, so what is on screen is never a label for somewhere else.
    LaunchedEffect(chosen) {
        name = null
        val at = chosen ?: return@LaunchedEffect
        delay(SETTLE_MS)
        name = nameOf(at)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen),
    ) {
        ScreenTitle(stringResource(R.string.locations_pin_title), onBack = onCancel)
        Spacer(Modifier.height(spacing.m))

        MapPicker(
            centre = centre,
            chosen = chosen,
            onPick = { chosen = it },
            tiles = tiles,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(spacing.m)),
        )

        Spacer(Modifier.height(spacing.s))

        // Asking to be found, and what came of it.
        //
        // The permission is requested at the moment it is used rather than on
        // the way into the screen. A prompt that arrives before anybody has
        // asked for anything is a prompt with no context, and it is refused more
        // often than not - deservedly.
        TextButton(
            onClick = {
                located = null
                permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            },
            enabled = !locating,
        ) {
            Text(
                text = stringResource(
                    if (locating) {
                        R.string.locations_pin_locating
                    } else {
                        R.string.locations_pin_locate
                    },
                ),
                style = WetterTheme.type.body,
                color = if (locating) colors.textTertiary else colors.interactive,
            )
        }

        // Only on failure. Success is its own message: the map moves and a pin
        // appears on it, which says more than a line of text could.
        if (located == false) {
            Text(
                text = stringResource(R.string.locations_pin_not_located),
                style = WetterTheme.type.meta,
                color = colors.textTertiary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(spacing.s))

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
            // The instruction until there is something to report. A blank line
            // here would leave the screen looking finished when it is not, and
            // the one thing a reader needs to know is that the map is waiting to
            // be tapped.
            text = chosen?.format() ?: stringResource(R.string.locations_pin_hint),
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
            TextButton(
                onClick = { chosen?.let { onConfirm(it, name) } },
                // Dead until a point exists, because there is nothing to
                // confirm. Greyed rather than hidden: a button that appears when
                // you tap the map is a surprise, and one that is plainly waiting
                // is an instruction.
                enabled = chosen != null,
            ) {
                Text(
                    text = stringResource(R.string.locations_pin_confirm),
                    style = WetterTheme.type.body,
                    color = if (chosen == null) colors.textDisabled else colors.interactive,
                )
            }
        }
        Spacer(Modifier.height(spacing.m))
    }
}

/** Long enough that a drag is one lookup, short enough to feel immediate. */
private const val SETTLE_MS = 700L
