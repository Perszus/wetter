// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/**
 * How anything on this screen opens and shuts, shared so panels open at the same
 * rate.
 *
 * Opening decelerates and settles; closing accelerates away and is shorter, since
 * there is nothing to look at once something is asked to go. On the way out the
 * fade is quicker than the collapse, so text is gone before the box is a few
 * pixels tall.
 */
internal object Reveal {

    private const val OPEN_MS = 260
    private const val CLOSE_MS = 190

    /** Decelerating, for arrival. */
    private val settling = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Accelerating, for departure. */
    private val leaving = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    val enter: EnterTransition =
        expandVertically(animationSpec = tween(OPEN_MS, easing = settling)) +
            fadeIn(animationSpec = tween(OPEN_MS, easing = settling))

    /** For anything that should move in step with the panel, like a chevron. */
    val chevron: FiniteAnimationSpec<Float> = tween(OPEN_MS, easing = settling)

    val exit: ExitTransition =
        shrinkVertically(animationSpec = tween(CLOSE_MS, easing = leaving)) +
            fadeOut(animationSpec = tween(CLOSE_MS / 2, easing = leaving))
}
