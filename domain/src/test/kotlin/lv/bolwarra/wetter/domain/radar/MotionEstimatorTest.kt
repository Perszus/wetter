package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimator is given fields it made itself, moved by an amount it was not
 * told, and asked to say how far they moved.
 */
class MotionEstimatorTest {

    private val start: Instant = Instant.parse("2026-09-03T12:00:00Z")
    private val tenMinutesOn: Instant = start.plus(Duration.ofMinutes(10))

    @Test
    fun `a known translation is recovered`() {
        // Twenty pixels right and ten up over ten minutes.
        val before = RadarTestFields.pattern(start)
        val after = RadarTestFields.pattern(tenMinutesOn, shiftX = 20f, shiftY = -10f)

        val motion = MotionEstimator.estimate(before, after)
        assertNotNull("no motion estimated at all", motion)

        val mean = motion!!.mean()
        assertEquals(2.0f, mean.x, 0.15f)
        assertEquals(-1.0f, mean.y, 0.15f)
    }

    @Test
    fun `the recovered speed is a plausible ground speed`() {
        val before = RadarTestFields.pattern(start)
        val after = RadarTestFields.pattern(tenMinutesOn, shiftX = 20f, shiftY = -10f)
        val motion = MotionEstimator.estimate(before, after)!!

        // 2.24 px/min at roughly 673 m/px is about 90 km/h - fast, and exactly
        // the sort of speed a squall line runs at. If the Mercator conversion
        // were skipped this would come out in the thousands.
        val kmh = motion.meanSpeedKmh(RadarTestFields.geometry())
        assertTrue("implausible ground speed: $kmh km/h", kmh in 60.0..120.0)
    }

    @Test
    fun `a still field is measured as still`() {
        val before = RadarTestFields.pattern(start)
        val after = RadarTestFields.pattern(tenMinutesOn)

        val motion = MotionEstimator.estimate(before, after)!!
        assertEquals(0f, motion.mean().x, 0.05f)
        assertEquals(0f, motion.mean().y, 0.05f)
    }

    @Test
    fun `an empty sky produces no estimate rather than a guess`() {
        // The important negative case. With nothing to track, any displacement
        // matches as well as any other, and a fabricated vector would advect a
        // dry map into a confident forecast of dry weather somewhere else.
        val before = RadarTestFields.dry(start)
        val after = RadarTestFields.dry(tenMinutesOn)

        assertNull(MotionEstimator.estimate(before, after))
    }

    @Test
    fun `frames out of order or simultaneous are refused`() {
        val a = RadarTestFields.pattern(start)
        val b = RadarTestFields.pattern(start)
        assertNull(MotionEstimator.estimate(a, b))

        val earlier = RadarTestFields.pattern(start)
        val later = RadarTestFields.pattern(start.minus(Duration.ofMinutes(10)))
        assertNull(MotionEstimator.estimate(earlier, later))
    }

    @Test
    fun `a confident match scores higher than a featureless one`() {
        val moved = MotionEstimator.estimate(
            RadarTestFields.pattern(start),
            RadarTestFields.pattern(tenMinutesOn, shiftX = 20f, shiftY = -10f),
        )!!

        // A structured field gives a sharp minimum in the cost surface.
        assertTrue("confidence was ${moved.confidence}", moved.confidence > 0.3f)
        assertTrue(moved.confidence <= 1f)
    }

    @Test
    fun `motion survives a coverage hole down one side`() {
        // Real composites have edges and blocked sectors. Unobserved pixels take
        // no part in the cost, so the estimate must come from what is visible
        // rather than being dragged towards whatever displacement hides the most
        // of the field behind the hole.
        val before = RadarTestFields.pattern(start, holes = true)
        val after = RadarTestFields.pattern(tenMinutesOn, shiftX = 20f, shiftY = -10f, holes = true)

        val motion = MotionEstimator.estimate(before, after)
        assertNotNull(motion)
        assertEquals(2.0f, motion!!.mean().x, 0.3f)
        assertEquals(-1.0f, motion.mean().y, 0.3f)
    }

    @Test
    fun `the flow is interpolated between block centres, not stepped`() {
        val motion = MotionEstimator.estimate(
            RadarTestFields.pattern(start),
            RadarTestFields.pattern(tenMinutesOn, shiftX = 20f, shiftY = -10f),
        )!!

        // Walking across a block boundary must not jump. A stepped field would
        // make advection tear along the seams.
        val samples = (0 until 200 step 4).map { motion.at(it.toFloat(), 128f).x }
        samples.zipWithNext { a, b ->
            assertTrue("flow jumped from $a to $b", kotlin.math.abs(a - b) < 0.5f)
        }
    }

    @Test
    fun `mismatched geometries are a programming error, not a silent wrong answer`() {
        val small = RadarTestFields.pattern(start, RadarTestFields.geometry(1))
        val large = RadarTestFields.pattern(tenMinutesOn, RadarTestFields.geometry(2))
        val result = runCatching { MotionEstimator.estimate(small, large) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `a run of frames is tracked from the whole run`() {
        // Four frames drifting steadily. The answer should be the same motion
        // the last pair alone would give, because the field really is moving
        // that way - the longer baseline must not distort a clean case.
        val start = Instant.parse("2026-09-04T12:00:00Z")
        val frames = (0..3).map { step ->
            RadarTestFields.pattern(
                at = start.plus(Duration.ofMinutes(step * 10L)),
                shiftX = step * 6f,
                shiftY = 0f,
            )
        }

        val fromRun = MotionEstimator.estimate(frames)!!
        val fromPair = MotionEstimator.estimate(frames[2], frames[3])!!

        assertEquals(fromPair.meanVector().x, fromRun.meanVector().x, 0.15f)
        assertEquals(fromPair.meanVector().y, fromRun.meanVector().y, 0.15f)
    }

    @Test
    fun `two spans that agree are believed at least as much as one`() {
        val start = Instant.parse("2026-09-04T12:00:00Z")
        val frames = (0..3).map { step ->
            RadarTestFields.pattern(
                at = start.plus(Duration.ofMinutes(step * 10L)),
                shiftX = step * 6f,
            )
        }

        val fromRun = MotionEstimator.estimate(frames)!!
        // A steady drift agrees with itself, so nothing is discounted.
        assertTrue(
            "agreement should not penalise a consistent field: ${fromRun.confidence}",
            fromRun.confidence >= MotionEstimator.estimate(frames[2], frames[3])!!.confidence *
                0.95f,
        )
    }

    @Test
    fun `a field that reverses is trusted less than one that does not`() {
        // The check sharpness cannot make. Each pair matches cleanly on its own;
        // only comparing two spans reveals that the motion is not real.
        val start = Instant.parse("2026-09-04T12:00:00Z")
        val steady = (0..3).map { step ->
            RadarTestFields.pattern(
                at = start.plus(Duration.ofMinutes(step * 10L)),
                shiftX = step * 6f,
            )
        }
        val reversing = listOf(0f, 18f, 6f, 0f).mapIndexed { step, shift ->
            RadarTestFields.pattern(
                at = start.plus(Duration.ofMinutes(step * 10L)),
                shiftX = shift,
            )
        }

        val confident = MotionEstimator.estimate(steady)!!.confidence
        val unstable = MotionEstimator.estimate(reversing)!!.confidence
        assertTrue(
            "steady $confident should beat reversing $unstable",
            confident > unstable,
        )
    }

    @Test
    fun `agreement never falls to nothing`() {
        // A field can be wrong about where the rain is going and still be right
        // about where it is, which needs no motion at all.
        val start = Instant.parse("2026-09-04T12:00:00Z")
        val a = MotionEstimator.estimate(
            RadarTestFields.pattern(start, shiftX = 0f),
            RadarTestFields.pattern(start.plus(Duration.ofMinutes(10)), shiftX = 8f),
        )!!
        val b = MotionEstimator.estimate(
            RadarTestFields.pattern(start, shiftX = 8f),
            RadarTestFields.pattern(start.plus(Duration.ofMinutes(10)), shiftX = 0f),
        )!!
        assertTrue(MotionEstimator.agreementBetween(a, b) >= MotionEstimator.LEAST_AGREEMENT)
    }
}
