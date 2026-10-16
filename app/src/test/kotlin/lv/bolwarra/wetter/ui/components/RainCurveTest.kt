// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.components

import androidx.compose.ui.unit.dp
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chart's horizontal scale, which must never move: pixels per hour is set by
 * the window alone, and the track grows to fit rather than the content shrinking,
 * so an hour is the same width every day.
 */
class RainCurveTest {

    private val screen = 360.dp
    private val window: Duration = Duration.ofHours(6)

    @Test
    fun `a day at six hours a screen is four screens wide`() {
        assertEquals(
            (screen * 4f).value,
            trackWidth(screen, Duration.ofHours(24), window).value,
            0.01f,
        )
    }

    @Test
    fun `exactly one window fills the screen and no more`() {
        assertEquals(screen.value, trackWidth(screen, window, window).value, 0.01f)
    }

    @Test
    fun `a short series is not stretched to fill the screen twice over`() {
        // The provider stopped at eighteen hours. That is three screens of chart
        // at this scale, not four screens of the same eighteen hours spread
        // thinner - which would silently redraw every hour at a new width and
        // make the same rain look different on a day a provider ran short.
        assertEquals(
            (screen * 3f).value,
            trackWidth(screen, Duration.ofHours(18), window).value,
            0.01f,
        )
    }

    @Test
    fun `a series shorter than the window still fills the screen`() {
        // Nothing to scroll to, so nothing to scroll: a full-width chart with a
        // little slack in it beats a narrow chart with a gap beside it. This is
        // the one case where an hour is drawn wider than the scale says, and it
        // is the case where there is no second hour to compare it against.
        assertEquals(
            screen.value,
            trackWidth(screen, Duration.ofHours(2), window).value,
            0.01f,
        )
    }

    @Test
    fun `a window of nothing cannot divide by zero`() {
        assertEquals(
            screen.value,
            trackWidth(screen, Duration.ofHours(24), Duration.ZERO).value,
            0.01f,
        )
    }
}
