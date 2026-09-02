package lv.bolwarra.wetter.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honesty guarantee of the rain curve.
 *
 * A smooth line through forecast points is only acceptable if it cannot claim
 * more than the points do. These tests are the proof of that: no negative
 * rainfall between dry hours, no invented peak higher than the heaviest hour,
 * and every drawn value between the two samples it sits between.
 *
 * The naive alternative fails all three, which is why this file exists.
 */
class MonotoneCurveTest {

    /** Samples the curve densely across every interval. */
    private fun sample(values: List<Float>, steps: Int = 40): List<Float> {
        val tangents = MonotoneCurve.tangents(values)
        val out = mutableListOf<Float>()
        for (i in 0 until values.size - 1) {
            for (s in 0..steps) {
                out += MonotoneCurve.valueAt(values, tangents, i, s / steps.toFloat())
            }
        }
        return out
    }

    @Test
    fun `the curve passes through every sample`() {
        val values = listOf(0f, 1.8f, 6.9f, 0.3f, 0f)
        val tangents = MonotoneCurve.tangents(values)

        for (i in 0 until values.size - 1) {
            assertEquals(values[i], MonotoneCurve.valueAt(values, tangents, i, 0f), 1e-4f)
            assertEquals(values[i + 1], MonotoneCurve.valueAt(values, tangents, i, 1f), 1e-4f)
        }
    }

    @Test
    fun `a shower never dips below zero on the way in or out`() {
        // The case that rules out Catmull-Rom: flat, flat, spike, flat. A
        // non-monotone spline swings negative approaching the peak.
        val values = listOf(0f, 0f, 4f, 0f, 0f)

        val lowest = sample(values).min()
        assertTrue("curve reached $lowest mm, which is not a thing", lowest >= -1e-4f)
    }

    @Test
    fun `the curve never draws more rain than the heaviest hour`() {
        val values = listOf(0f, 0.2f, 6.9f, 0.4f, 0f)

        val highest = sample(values).max()
        assertTrue(
            "curve peaked at $highest against a maximum sample of 6.9",
            highest <= 6.9f + 1e-4f,
        )
    }

    @Test
    fun `every drawn value lies between the two samples it sits between`() {
        val values = listOf(0f, 3f, 1f, 5f, 2f, 0f, 0f, 8f)
        val tangents = MonotoneCurve.tangents(values)

        for (i in 0 until values.size - 1) {
            val low = minOf(values[i], values[i + 1]) - 1e-4f
            val high = maxOf(values[i], values[i + 1]) + 1e-4f
            for (s in 0..40) {
                val v = MonotoneCurve.valueAt(values, tangents, i, s / 40f)
                assertTrue(
                    "between ${values[i]} and ${values[i + 1]} the curve drew $v",
                    v in low..high,
                )
            }
        }
    }

    @Test
    fun `a flat stretch stays flat`() {
        // Eight dry hours must draw as a straight line on zero, not as a gentle
        // swell that looks like drizzle.
        val values = List(8) { 0f }
        assertTrue(sample(values).all { it == 0f })

        val steady = List(6) { 2.5f }
        assertTrue(sample(steady).all { kotlin.math.abs(it - 2.5f) < 1e-4f })
    }

    @Test
    fun `a rising series never falls`() {
        val values = listOf(0f, 0.5f, 1f, 3f, 7f)
        val drawn = sample(values)

        drawn.zipWithNext().forEach { (a, b) ->
            assertTrue("the curve fell from $a to $b in a rising series", b >= a - 1e-4f)
        }
    }

    @Test
    fun `degenerate series do not blow up`() {
        assertEquals(0, MonotoneCurve.tangents(emptyList()).size)
        assertEquals(1, MonotoneCurve.tangents(listOf(3f)).size)
        assertEquals(0f, MonotoneCurve.tangents(listOf(3f))[0], 1e-6f)

        val two = listOf(1f, 4f)
        val tangents = MonotoneCurve.tangents(two)
        assertEquals(1f, MonotoneCurve.valueAt(two, tangents, 0, 0f), 1e-4f)
        assertEquals(4f, MonotoneCurve.valueAt(two, tangents, 0, 1f), 1e-4f)
    }
}
