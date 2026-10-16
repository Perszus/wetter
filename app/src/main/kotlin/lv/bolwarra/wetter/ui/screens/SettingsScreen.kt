// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.BuildConfig
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.domain.settings.PrecipitationUnit
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.settings.TemperatureUnit
import lv.bolwarra.wetter.domain.settings.ThemeChoice
import lv.bolwarra.wetter.domain.settings.WindUnit
import lv.bolwarra.wetter.notify.HazardNotifier
import lv.bolwarra.wetter.ui.WetterViewModels
import lv.bolwarra.wetter.ui.components.ChoiceRow
import lv.bolwarra.wetter.ui.components.ScreenTitle
import lv.bolwarra.wetter.ui.theme.WetterTheme
import lv.bolwarra.wetter.ui.theme.plateFor
import lv.bolwarra.wetter.work.Heartbeat

/** The pages the settings list opens, in the order they are listed. */
private enum class SettingsPage(val titleRes: Int) {
    GENERAL(R.string.section_general),
    UNITS(R.string.section_units),
    APPEARANCE(R.string.section_appearance),
    ABOUT(R.string.section_about),
}

/**
 * Settings, laid out as in the other Bolwarra apps: a short summary of what the
 * app is showing, a list of rows each opening its own page, and About last.
 *
 * Settings stay minimal by policy (docs/design-principles.md): units, because no
 * default suits everybody; the theme; warnings; and what the phone is holding
 * back from the background updates.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = WetterViewModels.Factory),
) {
    val application = LocalContext.current.applicationContext as WetterApplication
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    when (val open = page) {
        null -> SettingsList(preferences, summary, onOpen = { page = it }, onBack = onBack)
        else -> SettingsSubPage(stringResource(open.titleRes), onBack = { page = null }) {
            when (open) {
                SettingsPage.GENERAL -> GeneralPage(preferences, viewModel::set)
                SettingsPage.UNITS -> UnitsPage(preferences, viewModel::set)
                SettingsPage.APPEARANCE -> AppearancePage(preferences, viewModel::set)
                SettingsPage.ABOUT -> AboutPage(application.container.attributions)
            }
        }
    }
}

@Composable
private fun SettingsList(
    preferences: Preferences,
    summary: SettingsViewModel.Summary?,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = WetterTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
    ) {
        ScreenTitle(stringResource(R.string.nav_settings), onBack = onBack)
        Spacer(Modifier.height(spacing.l))
        if (summary != null) {
            InfoStrip(summary)
            Spacer(Modifier.height(spacing.section))
        }

        NavRow(
            title = stringResource(R.string.section_general),
            subtitle = stringResource(
                if (preferences.warnings) {
                    R.string.settings_warnings_on
                } else {
                    R.string.settings_warnings_off
                },
            ),
            onClick = { onOpen(SettingsPage.GENERAL) },
        )
        Hairline()
        NavRow(
            title = stringResource(R.string.section_units),
            subtitle = listOf(
                "°" + preferences.temperature.suffix,
                preferences.wind.label,
                preferences.precipitation.label,
            ).joinToString(" · "),
            onClick = { onOpen(SettingsPage.UNITS) },
        )
        Hairline()
        NavRow(
            title = stringResource(R.string.section_appearance),
            subtitle = stringResource(preferences.theme.labelRes()),
            onClick = { onOpen(SettingsPage.APPEARANCE) },
        )
        Hairline()
        NavRow(
            title = stringResource(R.string.section_about),
            subtitle = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            onClick = { onOpen(SettingsPage.ABOUT) },
        )
        Spacer(Modifier.height(spacing.xxl))
    }
}

/** What the app is showing right now: the place, who forecast it, and how fresh it is. */
@Composable
private fun InfoStrip(summary: SettingsViewModel.Summary) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(CARD_RADIUS))
            .background(colors.surfaceRaised)
            .padding(vertical = spacing.l),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoCell(summary.place, stringResource(R.string.settings_place), Modifier.weight(1f))
        CellDivider()
        InfoCell(
            summary.source ?: "–",
            stringResource(R.string.settings_source),
            Modifier.weight(1f),
        )
        CellDivider()
        InfoCell(
            age(summary.fetchedAt),
            stringResource(R.string.settings_updated),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun InfoCell(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = WetterTheme.colors
    Column(
        modifier.padding(horizontal = WetterTheme.spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = WetterTheme.type.body,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = label, style = WetterTheme.type.meta, color = colors.textTertiary, maxLines = 1)
    }
}

@Composable
private fun CellDivider() {
    Box(
        Modifier
            .width(WetterTheme.spacing.hairline)
            .fillMaxHeight(DIVIDER_SHARE)
            .background(WetterTheme.colors.hairline),
    )
}

/** How long ago, in the fewest words. */
@Composable
private fun age(at: Instant?): String {
    if (at == null) return "–"
    val minutes = Duration.between(at, Instant.now()).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.settings_age_now)
        minutes < MINUTES_PER_HOUR -> stringResource(R.string.settings_age_minutes, minutes)
        minutes < MINUTES_PER_DAY -> stringResource(
            R.string.settings_age_hours,
            minutes / MINUTES_PER_HOUR,
        )
        else -> stringResource(R.string.settings_age_days, minutes / MINUTES_PER_DAY)
    }
}

/** One row of the list: its name, what it is set to, and a chevron. */
@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = spacing.m + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = WetterTheme.type.body,
                color = colors.textPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = WetterTheme.type.meta,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(CHEVRON),
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(WetterTheme.spacing.hairline)
            .background(WetterTheme.colors.hairline),
    )
}

/** A page the list opens: its title with a way back, then its contents. */
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = WetterTheme.spacing
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
    ) {
        ScreenTitle(title, onBack = onBack)
        Spacer(Modifier.height(spacing.l))
        content()
        Spacer(Modifier.height(spacing.xxl))
    }
}

@Composable
private fun ColumnScope.GeneralPage(preferences: Preferences, onChange: (Preferences) -> Unit) {
    val context = LocalContext.current
    // The app asks for the notification permission once; this row is the way
    // back for somebody who refused it, and goes to the system's own page if
    // asking again is no longer possible.
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) context.openNotificationSettings()
        }

    ChoiceRow(
        label = stringResource(R.string.setting_warnings),
        options = listOf(true, false),
        selected = preferences.warnings,
        onSelect = { wanted ->
            onChange(preferences.copy(warnings = wanted))
            if (wanted) context.makeWarningsPossible(request::launch)
        },
        describe = {
            stringResource(if (it) R.string.setting_warnings_on else R.string.setting_warnings_off)
        },
    )
    Spacer(Modifier.height(WetterTheme.spacing.l))
    BackgroundUpdates()
}

@Composable
private fun ColumnScope.UnitsPage(preferences: Preferences, onChange: (Preferences) -> Unit) {
    val spacing = WetterTheme.spacing
    ChoiceRow(
        label = stringResource(R.string.setting_temperature),
        options = TemperatureUnit.entries,
        selected = preferences.temperature,
        onSelect = { onChange(preferences.copy(temperature = it)) },
        describe = { "°" + it.suffix },
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
 * What stands between the app and keeping itself fresh, and the way past it:
 * precise timing (Android 12's "Alarms & reminders"), which lets the background
 * wake land on a radar sweep, and unrestricted battery, which on several makers'
 * phones is what keeps background work running at all. Shown only while one of
 * them is missing, and re-checked on every return.
 */
@Composable
private fun ColumnScope.BackgroundUpdates() {
    val context = LocalContext.current
    val spacing = WetterTheme.spacing
    val colors = WetterTheme.colors
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var exact by remember { mutableStateOf(Heartbeat.exactAllowed(context)) }
    var unrestricted by remember { mutableStateOf(context.batteryUnrestricted()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            exact = Heartbeat.exactAllowed(context)
            unrestricted = context.batteryUnrestricted()
        }
    }
    if (exact && unrestricted) return

    Text(
        text = stringResource(R.string.setting_background),
        style = WetterTheme.type.body,
        color = colors.textSecondary,
    )
    Spacer(Modifier.height(spacing.xs))
    Text(
        text = stringResource(R.string.setting_background_why),
        style = WetterTheme.type.meta,
        color = colors.textTertiary,
    )
    Spacer(Modifier.height(spacing.s))
    if (!exact) {
        ActionRow(stringResource(R.string.setting_background_exact)) {
            context.openExactAlarmSettings()
        }
    }
    if (!unrestricted) {
        ActionRow(stringResource(R.string.setting_background_battery)) { context.openAppDetails() }
    }
}

/** A row that takes the reader to a system page: a card, so it reads as something to press. */
@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.s)
            .clip(RoundedCornerShape(ITEM_RADIUS))
            .background(colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = spacing.m, vertical = spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = WetterTheme.type.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(CHEVRON),
        )
    }
}

private fun Context.batteryUnrestricted(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

/** "Alarms & reminders" for this app, on Android 12 and later. */
private fun Context.openExactAlarmSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/**
 * The app's own info page, where battery restriction lives on every phone, with
 * the maker's autostart switch beside it on phones that have one.
 */
private fun Context.openAppDetails() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/**
 * The two plates as cards in the plate in force, each with a swatch of its own
 * ground: with only two themes, the swatch is what tells them apart.
 */
@Composable
private fun ColumnScope.AppearancePage(preferences: Preferences, onChange: (Preferences) -> Unit) {
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

/**
 * About, as in the other Bolwarra apps: the name and version, what the app is
 * for, the data it is built on, the links, and who made it. The data credits
 * are a licence obligation, so they are listed in full.
 */
@Composable
private fun ColumnScope.AboutPage(attributions: List<String>) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    val context = LocalContext.current
    val open: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.app_name),
            style = WetterTheme.type.headline,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = WetterTheme.type.meta,
            color = colors.textTertiary,
        )
    }
    Spacer(Modifier.height(spacing.section))

    Text(
        text = stringResource(R.string.about_purpose),
        style = WetterTheme.type.body,
        color = colors.textSecondary,
    )
    Spacer(Modifier.height(spacing.l))
    FEATURES.forEach { (lead, gloss) -> Bullet(stringResource(lead), stringResource(gloss)) }

    Divider()
    Caption(stringResource(R.string.section_sources))
    attributions.forEach { attribution ->
        Text(
            text = attribution,
            style = WetterTheme.type.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(vertical = spacing.xs),
        )
    }
    Spacer(Modifier.height(spacing.xs))
    Text(
        text = stringResource(R.string.about_sources_detail),
        style = WetterTheme.type.meta,
        color = colors.textTertiary,
    )

    Divider()
    Caption(stringResource(R.string.about_links))
    LinkRow(WEBSITE_LABEL) { open(WEBSITE) }
    LinkRow(EMAIL) { open("mailto:$EMAIL") }
    LinkRow(stringResource(R.string.about_source_code)) { open(SOURCE_CODE) }
    Text(
        text = stringResource(R.string.about_licence),
        style = WetterTheme.type.meta,
        color = colors.textTertiary,
    )

    Spacer(Modifier.height(spacing.section))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.about_made_by),
            style = WetterTheme.type.meta,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.about_copyright),
            style = WetterTheme.type.meta,
            color = colors.textTertiary.copy(alpha = FOOTNOTE_ALPHA),
        )
    }
}

@Composable
private fun Bullet(lead: String, gloss: String) {
    val colors = WetterTheme.colors
    val spacing = WetterTheme.spacing
    Row(Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Text(
            text = "•",
            style = WetterTheme.type.body,
            color = colors.textTertiary,
            modifier = Modifier.width(BULLET),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Medium)) {
                    append(lead)
                }
                withStyle(SpanStyle(color = colors.textSecondary)) { append(" — $gloss") }
            },
            style = WetterTheme.type.body,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Divider() {
    val spacing = WetterTheme.spacing
    Spacer(Modifier.height(spacing.xl))
    Hairline()
    Spacer(Modifier.height(spacing.l))
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text.uppercase(),
        style = WetterTheme.type.groupLabel,
        color = WetterTheme.colors.textTertiary,
    )
    Spacer(Modifier.height(WetterTheme.spacing.s))
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    val colors = WetterTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = WetterTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = WetterTheme.type.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(text = "↗", style = WetterTheme.type.body, color = colors.textTertiary)
    }
}

private fun ThemeChoice.labelRes(): Int = when (this) {
    ThemeChoice.PURE_WHITE -> R.string.theme_pure_white
    ThemeChoice.PURE_BLACK -> R.string.theme_pure_black
}

/**
 * Do whatever is still needed for a warning to arrive: nothing if notifications
 * are allowed, the runtime request if it can still be asked, and otherwise the
 * system's own notification page.
 */
private fun Context.makeWarningsPossible(ask: (String) -> Unit) {
    if (HazardNotifier(this).isAllowed()) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        ask(Manifest.permission.POST_NOTIFICATIONS)
        return
    }
    openNotificationSettings()
}

/** The system's own page for this app's notifications. */
private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/** What the app does, as a lead and its gloss. */
private val FEATURES = listOf(
    R.string.about_feature_rain to R.string.about_feature_rain_gloss,
    R.string.about_feature_source to R.string.about_feature_source_gloss,
    R.string.about_feature_warnings to R.string.about_feature_warnings_gloss,
    R.string.about_feature_offline to R.string.about_feature_offline_gloss,
    R.string.about_feature_widgets to R.string.about_feature_widgets_gloss,
)

private const val WEBSITE = "https://bolwarratechnologies.com/"
private const val WEBSITE_LABEL = "bolwarratechnologies.com"
private const val EMAIL = "bolwarratek@gmail.com"
private const val SOURCE_CODE = "https://github.com/Perszus/wetter"

private val CARD_RADIUS = 14.dp
private val ITEM_RADIUS = 10.dp
private val CHOSEN_EDGE = 2.dp
private val SWATCH = 20.dp
private val CHEVRON = 20.dp
private val BULLET = 16.dp

private const val GLOSS = 0.45f
private const val DIVIDER_SHARE = 0.6f
private const val FOOTNOTE_ALPHA = 0.7f
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * 60L
