// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.sky

import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import lv.bolwarra.wetter.domain.MoonPhase

/**
 * The spring-neap rhythm: twice a month the moon and sun line up and their tides
 * add (spring tides); at the quarters they partly cancel (neap tides). "Spring"
 * as in a spring of water, not the season.
 *
 * Not a tide table: times and heights of high water are local to each coast. The
 * rhythm is global, so it is the part shown beside the moon.
 */
object Tides {

    /**
     * How close the tide is to its monthly extreme: 1 at spring, 0 at neap.
     *
     * The moon and sun tides add as vectors, and the angle between them is twice
     * the moon's elongation - twice, because a tide has two bulges and the shape
     * repeats every half turn. So the alignment goes round twice a month, which
     * is why there are two spring tides in it and not one.
     */
    fun strengthAt(instant: Instant): Double {
        val phase = MoonPhase.fractionAt(instant)
        return (1.0 + cos(4.0 * PI * phase)) / 2.0
    }

    fun stateAt(instant: Instant): TideState = when (val strength = strengthAt(instant)) {
        in SPRING_FROM..1.0 -> TideState.SPRING
        in 0.0..NEAP_UNTIL -> TideState.NEAP
        // Which way it is going is the useful half of "in between": a tide
        // building towards spring and one falling away from it look identical
        // in a single reading and mean opposite things for the week ahead.
        else -> if (strengthAt(instant.plusSeconds(A_DAY)) > strength) {
            TideState.BUILDING
        } else {
            TideState.EASING
        }
    }

    /**
     * Roughly two days either side of new and full moon, which is the window
     * inside which a coast actually sees spring tides rather than the instant
     * of alignment.
     */
    private const val SPRING_FROM = 0.8

    private const val NEAP_UNTIL = 0.2

    private const val A_DAY = 86_400L
}

enum class TideState {
    /** New or full moon: the sun and moon pulling together. Biggest range. */
    SPRING,

    /** Building towards spring. */
    BUILDING,

    /** A quarter moon: the two pulls at right angles. Smallest range. */
    NEAP,

    /** Falling away from spring, towards neap. */
    EASING,
}
