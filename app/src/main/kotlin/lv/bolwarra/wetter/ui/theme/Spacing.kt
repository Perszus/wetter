package lv.bolwarra.wetter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A 4dp grid. Every gap in the app is one of these values; a literal `.dp` in a
 * layout is a bug unless it is a drawing dimension (a bar width, a stroke) rather
 * than a gap (docs/design-principles.md).
 */
@Immutable
data class Spacing(
    val hairline: Dp = 1.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** Vertical rhythm between two top-level sections of the weather screen. */
    val section: Dp = 28.dp,
    /** Horizontal inset from the screen edge. Held constant across every screen. */
    val screen: Dp = 20.dp,
    /** Minimum touch target, per accessibility guidance. */
    val touchTarget: Dp = 48.dp,
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }
