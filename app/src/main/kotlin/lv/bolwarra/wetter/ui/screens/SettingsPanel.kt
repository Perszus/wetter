package lv.bolwarra.wetter.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import lv.bolwarra.wetter.BuildConfig
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.domain.settings.PrecipitationUnit
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.settings.TemperatureUnit
import lv.bolwarra.wetter.domain.settings.ThemeChoice
import lv.bolwarra.wetter.domain.settings.WindUnit
import lv.bolwarra.wetter.ui.WetterViewModels
import lv.bolwarra.wetter.ui.components.ChoiceRow
import lv.bolwarra.wetter.ui.theme.WetterTheme
import lv.bolwarra.wetter.ui.theme.plateFor

@Composable
fun SettingsOverlay(onDismiss: () -> Unit) {
    // Attributions come from the container as plain strings. The panel never
    // learns that OpenMeteoProvider or MetNorwayProvider exist (docs/providers.md).
    val application = LocalContext.current.applicationContext as WetterApplication
    val model: SettingsViewModel = viewModel(factory = WetterViewModels.Factory)
    val preferences by model.preferences.collectAsStateWithLifecycle()

    SettingsPanel(
        attributions = application.container.attributions,
        preferences = preferences,
        onChange = model::set,
        onDismiss = onDismiss,
    )
}

/** The three groups, in the order somebody looks for them. */
private enum class SettingsGroup(val labelRes: Int) {
    GENERAL(R.string.section_general),
    APPEARANCE(R.string.section_appearance),
    ABOUT(R.string.section_about),
}

/**
 * A panel over the app: groups down the left, the chosen group on the right.
 *
 * ### It opens over the forecast rather than replacing it
 *
 * Settings is not somewhere you go. It is opened to change one thing and shut
 * again, usually within a few seconds, and the forecast underneath is the reason
 * the app was opened at all. A full screen on the back stack would take that
 * away for the duration and leave the app, briefly, not showing the one thing it
 * exists to show — and it would put the way *out* of settings on the system back
 * gesture, which is a different question from the one being asked.
 *
 * A panel says the same thing with its shape: the app is still there, this is on
 * top of it, and it will go away.
 *
 * ### Why a rail and not one long page
 *
 * A single scroll with three headings shows everything at once, which sounds
 * like the better answer and stops being one the moment there is a fourth group:
 * the panel grows downwards, headings stop being landmarks and become things to
 * scroll past, and finding a setting means remembering how far down it lives. A
 * rail keeps every group one tap away however many there are, and — the part
 * that matters more — the list of what is configurable is always on screen.
 * Nobody has to scroll to find out what this app lets them change.
 *
 * ### Settings stay minimal by policy
 *
 * docs/design-principles.md: nothing here that a sensible default already
 * answers. Units are here because no default is right for everybody, and even
 * the first guess — made from where the reader's first location is — is only a
 * guess. Theme is here because the phone's own setting is a good default and
 * some people want one app to disagree with it. Nothing else has earned a row.
 */
@Composable
fun SettingsPanel(
    attributions: List<String>,
    preferences: Preferences,
    onChange: (Preferences) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    var group by rememberSaveable { mutableStateOf(SettingsGroup.GENERAL) }

    Dialog(
        onDismissRequest = onDismiss,
        // The platform width is sized for a message with two buttons under it.
        // This is a panel, and it is measured against the screen instead.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxWidth(PANEL_WIDTH)
                .fillMaxHeight(PANEL_HEIGHT)
                .clip(RoundedCornerShape(PANEL_RADIUS))
                .background(colors.surface)
                // A hairline edge, because the panel sits on the app's own ground
                // and the two are close in tone by design. Without it the corners
                // are the only thing saying where one stops.
                .border(spacing.hairline, colors.hairline, RoundedCornerShape(PANEL_RADIUS)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.l, end = spacing.s, top = spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.nav_settings),
                        style = WetterTheme.type.title,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_close),
                            tint = colors.textTertiary,
                        )
                    }
                }

                Rule(Modifier.fillMaxWidth().height(spacing.hairline))

                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        modifier = Modifier
                            .weight(RAIL_SHARE)
                            .fillMaxHeight()
                            .padding(spacing.s),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        SettingsGroup.entries.forEach { entry ->
                            GroupItem(
                                label = stringResource(entry.labelRes),
                                chosen = entry == group,
                                onClick = { group = entry },
                            )
                        }
                    }

                    Rule(Modifier.width(spacing.hairline).fillMaxHeight())

                    // Crossfaded rather than slid. The rail does not move, so
                    // there is nothing for a slide to be relative to, and a panel
                    // arriving from a direction would imply the groups are laid
                    // out in that direction.
                    Crossfade(
                        targetState = group,
                        label = "settings group",
                        modifier = Modifier.weight(1f - RAIL_SHARE).fillMaxHeight(),
                    ) { shown ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(spacing.l),
                        ) {
                            when (shown) {
                                SettingsGroup.GENERAL -> GeneralGroup(preferences, onChange)
                                SettingsGroup.APPEARANCE -> AppearanceGroup(preferences, onChange)
                                SettingsGroup.ABOUT -> AboutGroup(attributions)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A divider, taking its shape from whichever way the modifier stretches it. */
@Composable
private fun Rule(modifier: Modifier) {
    Box(modifier.background(WetterTheme.colors.hairline))
}

/**
 * One name in the rail.
 *
 * The chosen one is filled rather than ticked or underlined, the same way the
 * chosen segment of a [ChoiceRow] is: this app has one way of showing which of a
 * few things is selected, and a second one would be a second thing to learn.
 *
 * The unselected labels are secondary rather than tertiary ink. Tertiary is the
 * weight of a caption — something not meant to be read — and these are the
 * navigation: every one has to be readable before it can be chosen.
 */
@Composable
private fun GroupItem(label: String, chosen: Boolean, onClick: () -> Unit) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    Text(
        text = label,
        style = WetterTheme.type.body,
        color = if (chosen) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ITEM_RADIUS))
            .background(if (chosen) colors.surfaceRaised else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = spacing.s, vertical = spacing.m),
    )
}

@Composable
private fun ColumnScope.GeneralGroup(preferences: Preferences, onChange: (Preferences) -> Unit) {
    val spacing = WetterTheme.spacing

    // First, because it is the only setting here that changes what the app does
    // rather than how it writes a number - and the only one that lets it speak
    // when nobody has opened it.
    ChoiceRow(
        label = stringResource(R.string.setting_warnings),
        options = listOf(true, false),
        selected = preferences.warnings,
        onSelect = { onChange(preferences.copy(warnings = it)) },
        describe = {
            stringResource(
                if (it) R.string.setting_warnings_on else R.string.setting_warnings_off,
            )
        },
    )
    Spacer(Modifier.height(spacing.l))

    ChoiceRow(
        label = stringResource(R.string.setting_temperature),
        options = TemperatureUnit.entries,
        selected = preferences.temperature,
        onSelect = { onChange(preferences.copy(temperature = it)) },
        describe = { it.suffix },
    )
    Spacer(Modifier.height(spacing.l))

    ChoiceRow(
        label = stringResource(R.string.setting_wind),
        options = WindUnit.entries,
        selected = preferences.wind,
        onSelect = { onChange(preferences.copy(wind = it)) },
        describe = { it.label },
    )
    Spacer(Modifier.height(spacing.l))

    ChoiceRow(
        label = stringResource(R.string.setting_precipitation),
        options = PrecipitationUnit.entries,
        selected = preferences.precipitation,
        onSelect = { onChange(preferences.copy(precipitation = it)) },
        describe = { it.label },
    )
}

/**
 * The two plates, as a pair of cards.
 *
 * The cards are drawn in the plate that is *in force*, not each in its own. An
 * earlier version painted every cell in the theme it offered, which is what the
 * apps this borrows from do — and with a dozen themes that is right, because the
 * picker is then a set of samples and the samples are the information. With two
 * it is worse than useless: the panel around them is one of the two, so one card
 * vanished into the background and the other was the only thing on screen with a
 * different ground, which reads as the selected one whether it is or not.
 *
 * So the cards match the app and the *swatch* carries the difference — a disc of
 * each plate's own ground, which is the whole of what separates these two.
 * Paper is a pale disc, the zone plate a near-black one, and both are ringed so
 * neither disappears into the card it sits on when its own theme is in force.
 */
@Composable
private fun ColumnScope.AppearanceGroup(preferences: Preferences, onChange: (Preferences) -> Unit) {
    val spacing = WetterTheme.spacing

    Text(
        text = stringResource(R.string.setting_theme),
        style = WetterTheme.type.body,
        color = WetterTheme.colors.textSecondary,
    )
    Spacer(Modifier.height(spacing.s))

    ThemeChoice.entries.forEach { choice ->
        ThemeCell(
            choice = choice,
            chosen = choice == preferences.theme,
            onSelect = { onChange(preferences.copy(theme = choice)) },
            modifier = Modifier.padding(bottom = spacing.s),
        )
    }
}

@Composable
private fun ThemeCell(
    choice: ThemeChoice,
    chosen: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    // Built only for its ground, which is the one colour the swatch shows.
    val sample = remember(choice) { plateFor(choice) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ITEM_RADIUS))
            .background(colors.surfaceRaised)
            .border(
                width = if (chosen) CHOSEN_EDGE else spacing.hairline,
                color = if (chosen) colors.textPrimary else colors.hairline,
                shape = RoundedCornerShape(ITEM_RADIUS),
            )
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = spacing.m, vertical = spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(SWATCH)
                .clip(CircleShape)
                .background(sample.surface)
                // Ringed so the pale disc still has an edge on paper and the dark
                // one still has an edge on the zone plate. Without it the swatch
                // for whichever theme is in force is an invisible hole.
                .border(spacing.hairline, colors.hairline, CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = GLOSS), Color.Transparent),
                        ),
                    ),
            )
        }
        Spacer(Modifier.width(spacing.m))
        Text(
            text = stringResource(choice.labelRes()),
            style = WetterTheme.type.body,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun ColumnScope.AboutGroup(attributions: List<String>) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing

    Text(
        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = WetterTheme.type.body,
        color = colors.textSecondary,
    )
    Spacer(Modifier.height(spacing.xs))
    Text(
        text = stringResource(R.string.about_licence),
        style = WetterTheme.type.meta,
        color = colors.textTertiary,
    )

    Spacer(Modifier.height(spacing.l))

    // Attribution to the weather services is an obligation rather than a feature
    // (docs/providers.md), so it sits in About rather than behind another tap.
    Text(
        text = stringResource(R.string.section_sources),
        style = WetterTheme.type.groupLabel,
        color = colors.textTertiary,
    )
    Spacer(Modifier.height(spacing.s))
    attributions.forEach { attribution ->
        Text(
            text = attribution,
            style = WetterTheme.type.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(spacing.s))
    }
    Spacer(Modifier.height(spacing.xs))
    Text(
        text = stringResource(R.string.about_sources_detail),
        style = WetterTheme.type.meta,
        color = colors.textTertiary,
    )
}

private fun ThemeChoice.labelRes(): Int = when (this) {
    ThemeChoice.PURE_WHITE -> R.string.theme_pure_white
    ThemeChoice.PURE_BLACK -> R.string.theme_pure_black
}

/**
 * Nearly the whole screen, but not quite.
 *
 * The margin is the point: it is what says the app is still there underneath and
 * that this will go away. A panel at full bleed is a screen with extra steps.
 */
private const val PANEL_WIDTH = 0.94f
private const val PANEL_HEIGHT = 0.76f

/**
 * A third to the rail.
 *
 * Enough for the longest group name at body size, and every point of it is a
 * point the segmented controls opposite do not have — which is why the unit
 * labels there are symbols rather than words.
 */
private const val RAIL_SHARE = 0.33f

private val PANEL_RADIUS = 16.dp
private val ITEM_RADIUS = 10.dp

/** Thick enough to read as chosen against a hairline, thin enough not to shout. */
private val CHOSEN_EDGE = 2.dp

private val SWATCH = 20.dp

/** The highlight that turns a disc into a sample of a surface. */
private const val GLOSS = 0.45f

@Preview(name = "Settings · light", showBackground = true)
@Composable
private fun SettingsPreview() {
    WetterTheme(units = Preferences(theme = ThemeChoice.PURE_WHITE)) {
        SettingsPanel(
            attributions = listOf(
                "Weather data by Open-Meteo.com, licensed CC BY 4.0",
                "Weather data from MET Norway (met.no), licensed CC BY 4.0",
            ),
            preferences = Preferences(),
            onChange = {},
            onDismiss = {},
        )
    }
}
