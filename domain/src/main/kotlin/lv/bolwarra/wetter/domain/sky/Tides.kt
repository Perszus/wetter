package lv.bolwarra.wetter.domain.sky

import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import lv.bolwarra.wetter.domain.MoonPhase

/**
 * How hard the moon and sun are pulling together, and so how far the sea moves.
 *
 * The moon raises the tide and the sun raises a smaller one of its own. Twice a
 * month they line up - at new moon, with the sun behind it, and at full moon,
 * with the sun opposite - and the two bulges add: spring tides, the highest highs
 * and the lowest lows of the month. At the quarters they pull at right angles
 * and partly cancel: neap tides, when the sea barely moves.
 *
 * "Spring" is nothing to do with the season. It is the older sense, as in a
 * spring of water - the tide springing forth.
 *
 * ### What this deliberately does not claim
 *
 * Not a tide table. When high water arrives and how deep it is are local facts,
 * set by the shape of the coast and the basin it sits in - the Mediterranean
 * barely moves while the Bay of Fundy swings sixteen metres on the same sky.
 * Those need harmonic constants from a tide gauge, which is a different kind of
 * data source entirely.
 *
 * What is global is the *rhythm*: everywhere with a sea, spring tides fall on
 * the same days, because the geometry causing them is the same geometry
 * everywhere. That is the part worth showing beside the moon, and the only part
 * this can honestly say.
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
