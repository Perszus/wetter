package lv.bolwarra.wetter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * "tnum" locks every digit to the same advance width. Without it a temperature
 * ticking 9 -> 10, or a column of times, visibly jitters — which is exactly the
 * kind of imprecision an instrument must not show. Every style that can carry a
 * number sets it.
 */
private const val TABULAR = "tnum"

/**
 * The type scale.
 *
 * Deliberately narrow: one display size, two heading sizes, two body sizes and
 * two label sizes. A weather screen that needs a ninth size has a hierarchy
 * problem, not a typography problem (docs/design-principles.md).
 *
 * The system sans is used as-is. Roboto's tabular figures are good, it costs no
 * APK size, and it ships on every device — a bundled face is an open question,
 * not a default (docs/decisions.md).
 */
object WetterType {

    /** The current temperature, and nothing else. Light weight keeps 60sp calm. */
    val reading = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.03).em,
        fontFeatureSettings = TABULAR,
    )

    /** The degree symbol and unit riding alongside [reading]. */
    val readingUnit = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 24.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.em,
    )

    /** The location name in the header. */
    val place = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.005).em,
    )

    val headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = TABULAR,
    )

    val title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
    )

    /** Timestamps, data age, secondary readings. */
    val meta = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TABULAR,
    )

    /**
     * The rules that head each section — set small, tracked wide and rendered in
     * caps by the caller. Wide tracking is what makes 11sp read as a label rather
     * than as shrunken body text.
     */
    val sectionLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.14.em,
    )

    /** Hour ticks under the timeline, and any other axis. */
    val axis = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.01.em,
        textAlign = TextAlign.Center,
        fontFeatureSettings = TABULAR,
    )

    /** Numbers inside a column that must align: daily highs and lows. */
    val figure = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
    )
}

/**
 * Material's slots, filled from the scale above, so a Material component dropped
 * into a screen inherits Wetter's type instead of importing a second one.
 */
val WetterTypography = Typography(
    displayLarge = WetterType.reading,
    displayMedium = WetterType.reading,
    headlineLarge = WetterType.headline,
    headlineMedium = WetterType.headline,
    headlineSmall = WetterType.place,
    titleLarge = WetterType.place,
    titleMedium = WetterType.title,
    titleSmall = WetterType.title,
    bodyLarge = WetterType.body,
    bodyMedium = WetterType.body,
    bodySmall = WetterType.meta,
    labelLarge = WetterType.title,
    labelMedium = WetterType.meta,
    labelSmall = WetterType.sectionLabel,
)
