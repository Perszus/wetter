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
 * How anything on this screen opens and shuts.
 *
 * One pair of transitions rather than each caller reaching for the defaults,
 * because two panels on the same page opening at visibly different rates is the
 * sort of thing nobody consciously notices and everybody feels.
 *
 * ### Opening and closing are not the same gesture
 *
 * Opening decelerates: it arrives quickly and settles, which reads as the panel
 * coming to rest where it belongs. Closing accelerates away and is shorter,
 * because once you have asked for something to go there is nothing to look at
 * and a slow exit is just latency. The default symmetric curve makes both feel
 * slightly wrong in opposite directions.
 *
 * The fade is deliberately quicker than the height on the way out, so the box
 * empties before it finishes collapsing. Fading and shrinking in lockstep leaves
 * text legible right down to a few pixels tall, which looks like a fault.
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
