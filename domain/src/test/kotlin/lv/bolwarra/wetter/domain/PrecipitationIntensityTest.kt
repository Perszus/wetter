package lv.bolwarra.wetter.domain

import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity.Companion.ofRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intensity bands decide every bar height and every rain colour in the app,
 * so the boundaries are pinned here. Each threshold is tested from both sides —
 * an off-by-one at 2.5 mm/h is the difference between "light" and "moderate" on
 * the screen, and nothing else would catch it.
 */
class PrecipitationIntensityTest {

    @Test
    fun `no reading is not the same as dry, but draws the same`() {
        assertEquals(PrecipitationIntensity.NONE, ofRate(null))
        assertEquals(PrecipitationIntensity.NONE, ofRate(0.0))
    }

    @Test
    fun `below the trace threshold reports dry`() {
        assertEquals(PrecipitationIntensity.NONE, ofRate(0.09))
    }

    @Test
    fun `each threshold is inclusive at its lower bound`() {
        assertEquals(PrecipitationIntensity.TRACE, ofRate(0.1))
        assertEquals(PrecipitationIntensity.LIGHT, ofRate(0.5))
        assertEquals(PrecipitationIntensity.MODERATE, ofRate(2.5))
        assertEquals(PrecipitationIntensity.HEAVY, ofRate(7.6))
        assertEquals(PrecipitationIntensity.VIOLENT, ofRate(50.0))
    }

    @Test
    fun `each threshold is exclusive at its upper bound`() {
        assertEquals(PrecipitationIntensity.TRACE, ofRate(0.49))
        assertEquals(PrecipitationIntensity.LIGHT, ofRate(2.49))
        assertEquals(PrecipitationIntensity.MODERATE, ofRate(7.59))
        assertEquals(PrecipitationIntensity.HEAVY, ofRate(49.9))
    }

    @Test
    fun `a cloudburst does not fall off the top of the scale`() {
        assertEquals(PrecipitationIntensity.VIOLENT, ofRate(180.0))
    }

    @Test
    fun `isWet is true for everything above dry`() {
        assertFalse(PrecipitationIntensity.NONE.isWet)
        assertTrue(PrecipitationIntensity.TRACE.isWet)
        assertTrue(PrecipitationIntensity.VIOLENT.isWet)
    }

    @Test
    fun `the scale is monotonic across its own thresholds`() {
        val rates = listOf(0.0, 0.1, 0.5, 2.5, 7.6, 50.0)
        val bands = rates.map { ofRate(it).ordinal }
        assertEquals(bands.sorted(), bands)
        assertEquals(bands.distinct(), bands)
    }
}
