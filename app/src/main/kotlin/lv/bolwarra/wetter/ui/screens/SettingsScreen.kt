package lv.bolwarra.wetter.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import lv.bolwarra.wetter.BuildConfig
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.ui.components.ScreenTitle
import lv.bolwarra.wetter.ui.components.SectionHeader
import lv.bolwarra.wetter.ui.theme.WetterTheme

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Attributions come from the container as plain strings. The screen never
    // learns that OpenMeteoProvider or MetNorwayProvider exist (docs/providers.md).
    val application = LocalContext.current.applicationContext as WetterApplication
    SettingsScreen(
        attributions = application.container.attributions,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Settings stay minimal by policy (docs/design-principles.md). Units, default location,
 * theme and refresh behaviour arrive in the settings phase — and nothing that a
 * sensible default already answers.
 *
 * The About section is here from the start because attribution to the weather
 * providers is an obligation, not a feature (docs/providers.md).
 */
@Composable
fun SettingsScreen(
    attributions: List<String>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = WetterTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTitle(stringResource(R.string.nav_settings), onBack = onBack)
        Spacer(Modifier.height(spacing.xl))

        SectionHeader(label = stringResource(R.string.section_about))
        Spacer(Modifier.height(spacing.m))
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = WetterTheme.type.body,
            color = WetterTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.about_licence),
            style = WetterTheme.type.meta,
            color = WetterTheme.colors.textTertiary,
        )

        Spacer(Modifier.height(spacing.section))

        SectionHeader(label = stringResource(R.string.section_sources))
        Spacer(Modifier.height(spacing.m))
        attributions.forEach { attribution ->
            Text(
                text = attribution,
                style = WetterTheme.type.body,
                color = WetterTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(spacing.s))
        }
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.about_sources_detail),
            style = WetterTheme.type.meta,
            color = WetterTheme.colors.textTertiary,
        )

        Spacer(Modifier.height(spacing.section))
    }
}

@Preview(name = "Settings · light", showBackground = true)
@Composable
private fun SettingsPreview() {
    WetterTheme(darkTheme = false) {
        SettingsScreen(
            attributions = listOf(
                "Weather data by Open-Meteo.com, licensed CC BY 4.0",
                "Weather data from MET Norway (met.no), licensed CC BY 4.0",
            ),
            onBack = {},
        )
    }
}
