package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.bolwarra.wetter.R
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
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    LocationsScreen(
        locations = viewModel.locations,
        selected = selected,
        onSelect = { location ->
            viewModel.select(location)
            onBack()
        },
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Choosing a place.
 *
 * The list is currently the built-in set; search, saved locations and the
 * optional use of the device's own position are the location phase's work. The
 * screen is written against a list and a selection, so gaining a search field
 * above it will not change anything below.
 */
@Composable
fun LocationsScreen(
    locations: List<WeatherLocation>,
    selected: WeatherLocation,
    onSelect: (WeatherLocation) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen),
    ) {
        ScreenTitle(stringResource(R.string.nav_locations), onBack = onBack)
        Spacer(Modifier.height(spacing.m))

        LazyColumn {
            items(locations, key = { "${it.latitude},${it.longitude}" }) { location ->
                LocationRow(
                    location = location,
                    isSelected = location.name == selected.name,
                    onClick = { onSelect(location) },
                )
                HairlineRule()
            }
        }
    }
}

@Composable
private fun LocationRow(location: WeatherLocation, isSelected: Boolean, onClick: () -> Unit) {
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
                color = if (isSelected) colors.accent else colors.textPrimary,
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
    }
}

@Preview(name = "Locations · light", showBackground = true)
@Composable
private fun LocationsPreview() {
    WetterTheme(darkTheme = false) {
        LocationsScreen(
            locations = listOf(SampleWeather.location),
            selected = SampleWeather.location,
            onSelect = {},
            onBack = {},
        )
    }
}
