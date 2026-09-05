package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.ui.WetterViewModels
import lv.bolwarra.wetter.ui.components.HairlineRule
import lv.bolwarra.wetter.ui.components.ScreenTitle
import lv.bolwarra.wetter.ui.preview.SampleWeather
import lv.bolwarra.wetter.ui.theme.WetterTheme

@Composable
fun LocationsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationsViewModel = viewModel(factory = WetterViewModels.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    var pinning by rememberSaveable { mutableStateOf(false) }

    if (pinning) {
        PinPicker(
            start = Coordinates(selected.latitude, selected.longitude),
            tiles = viewModel.tiles,
            nameOf = viewModel::nameOf,
            onCancel = { pinning = false },
            onConfirm = { point, name ->
                viewModel.savePin(point, name)
                pinning = false
                onBack()
            },
            modifier = modifier,
        )
        return
    }

    LocationsScreen(
        state = state,
        selected = selected,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::clearQuery,
        onSelect = { location ->
            viewModel.select(location)
            onBack()
        },
        onRemove = viewModel::remove,
        onPinOnMap = { pinning = true },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Choosing a place.
 *
 * One list, not two. While nothing has been typed it shows the places already
 * kept; once something has, it shows what matches. A screen with a permanent
 * "results" section sitting empty above the real list spends most of its height
 * on nothing, and the two are never both useful at the same moment.
 */
@Composable
fun LocationsScreen(
    state: LocationsUiState,
    selected: WeatherLocation,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSelect: (WeatherLocation) -> Unit,
    onRemove: (WeatherLocation) -> Unit,
    onPinOnMap: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing
    val colors = WetterTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen),
    ) {
        ScreenTitle(stringResource(R.string.nav_locations), onBack = onBack)
        Spacer(Modifier.height(spacing.m))

        SearchField(
            query = state.query,
            onQueryChange = onQueryChange,
            onClear = onClearQuery,
            onSubmit = { keyboard?.hide() },
        )
        Spacer(Modifier.height(spacing.s))

        // Under the search box rather than behind a menu, because it answers
        // the question the search box just failed at. A gazetteer knows
        // settlements; somebody looking for their own street finds nothing and
        // needs to be told, here, that there is another way to say where.
        TextButton(onClick = onPinOnMap) {
            Text(
                text = stringResource(R.string.locations_pin_on_map),
                style = WetterTheme.type.body,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.height(spacing.s))

        // Deduplicated by the same function that keys the list, so the two
        // cannot disagree about what one place is - which is exactly how this
        // crashed before, with a tolerant sameness test on one side and exact
        // coordinates on the other.
        val showing = (if (state.searchable) state.results else state.places)
            .distinctBy { it.rowKey() }

        // A word about what the list currently is, so a search that matched
        // nothing cannot be mistaken for the saved places having vanished.
        val note = when {
            state.searchable && state.failed -> stringResource(R.string.locations_search_failed)
            state.foundNothing -> stringResource(R.string.locations_no_matches, state.query.trim())
            state.searchable -> null
            state.places.isEmpty() -> stringResource(R.string.locations_none_saved)
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                style = WetterTheme.type.body,
                color = colors.textTertiary,
                modifier = Modifier.padding(vertical = spacing.m),
            )
        }

        LazyColumn {
            items(showing, key = { it.rowKey() }) { location ->
                val isSelected = location.latitude == selected.latitude &&
                    location.longitude == selected.longitude
                LocationRow(
                    location = location,
                    isSelected = isSelected,
                    // A search result is not yet anywhere to be removed from, and
                    // the place you are currently looking at cannot be forgotten
                    // while you are looking at it - the list would simply put it
                    // back, which reads as the button not working.
                    onRemove = if (state.searchable || isSelected) {
                        null
                    } else {
                        ({ onRemove(location) })
                    },
                    onClick = { onSelect(location) },
                )
                HairlineRule()
            }
        }
    }
}

/**
 * The search box.
 *
 * Deliberately plain: no placeholder animation, no leading label, and the
 * clear button appears only once there is something to clear.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = WetterTheme.colors

    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.merge(WetterTheme.type.body),
        placeholder = {
            Text(
                text = stringResource(R.string.locations_search_hint),
                style = WetterTheme.type.body,
                color = colors.textTertiary,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(SEARCH_ICON),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.locations_clear),
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .size(SEARCH_ICON)
                        .clickable(onClick = onClear),
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        shape = RoundedCornerShape(percent = 50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceRaised,
            unfocusedContainerColor = colors.surfaceRaised,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = colors.interactive,
            // The underline a filled field normally carries fights the pill.
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LocationRow(
    location: WeatherLocation,
    isSelected: Boolean,
    onRemove: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = spacing.touchTarget)
            .padding(vertical = spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = location.name,
                style = WetterTheme.type.title,
                // Selection is shown by weight of colour rather than by a tick.
                // One row in the list is darker than the rest; that is enough.
                color = if (isSelected) colors.interactive else colors.textPrimary,
            )
            val place = listOfNotNull(location.region, location.country).joinToString(", ")
            if (place.isNotEmpty()) {
                Text(
                    text = place,
                    style = WetterTheme.type.meta,
                    color = colors.textTertiary,
                )
            }
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(spacing.touchTarget)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.Transparent)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.locations_forget, location.name),
                    tint = colors.textTertiary,
                    modifier = Modifier.size(REMOVE_ICON),
                )
            }
        }
    }
}

private val SEARCH_ICON = 20.dp
private val REMOVE_ICON = 18.dp

@Preview(name = "Locations · light", showBackground = true)
@Composable
private fun LocationsPreview() {
    WetterTheme(darkTheme = false) {
        LocationsScreen(
            state = LocationsUiState(places = listOf(SampleWeather.location)),
            selected = SampleWeather.location,
            onQueryChange = {},
            onClearQuery = {},
            onSelect = {},
            onRemove = {},
            onPinOnMap = {},
            onBack = {},
        )
    }
}
