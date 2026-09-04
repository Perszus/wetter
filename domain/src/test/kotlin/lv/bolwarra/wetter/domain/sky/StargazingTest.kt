package lv.bolwarra.wetter.domain.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StargazingTest {

    private fun sky(
        low: Int? = 0,
        medium: Int? = 0,
        high: Int? = 0,
        sun: Double = -20.0,
        moon: Double = 0.0,
        rain: Double? = 0.0,
    ) = Stargazing.assess(low, medium, high, sun, moon, rain)

    @Test
    fun `a clear dark night is worth going out for`() {
        assertTrue(sky().isWorthIt)
    }

    @Test
    fun `daylight is not`() {
        assertFalse(sky(sun = 30.0).isWorthIt)
    }

    @Test
    fun `dusk is not dark yet`() {
        // Civil twilight: the brightest planets, and nothing else.
        assertFalse(sky(sun = -6.0).isWorthIt)
    }

    @Test
    fun `low cloud is a lid and high cloud is a veil`() {
        // The whole reason this reads the decks instead of the total: the same
        // figure means opposite things depending on which deck it is in.
        assertFalse(sky(low = 60).isWorthIt)
        assertTrue(sky(high = 60).isWorthIt)
    }

    @Test
    fun `a heavily veiled sky is not a good one either`() {
        // Cirrus is thin, not free. Eighty per cent of it leaves the bright
        // stars and takes the rest, and that is not a night worth a mark.
        assertFalse(sky(high = 80).isWorthIt)
    }

    @Test
    fun `decks are combined as cover, not added`() {
        // Two half-covered decks leave a quarter of the sky open, not none.
        val clarity = Stargazing.clarityOf(50, 50, 0)!!
        assertEquals(0.25f, clarity, 1e-4f)
    }

    @Test
    fun `rain settles it whatever the cloud says`() {
        assertFalse(sky(rain = 0.4).isWorthIt)
    }

    @Test
    fun `a full moon grades the night down without calling it off`() {
        val dark = sky(moon = 0.0)
        val bright = sky(moon = 1.0)
        assertTrue(bright.isWorthIt)
        assertTrue(bright.quality < dark.quality)
        assertTrue(bright.moonWashed)
    }

    @Test
    fun `deeper darkness scores better than the gate`() {
        assertTrue(sky(sun = -25.0).quality > sky(sun = -13.0).quality)
    }

    @Test
    fun `no cloud reading is not a clear sky`() {
        // The one failure worth designing out: promising a good night from a
        // reading nobody took.
        assertNull(Stargazing.clarityOf(null, null, null))
        assertFalse(sky(low = null, medium = null, high = null).isWorthIt)
    }
}
