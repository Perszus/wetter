package lv.bolwarra.wetter.data.provider.rainviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colours here are real ones, sampled from live tiles over Europe. The
 * ordering they are asserted to have is the one two independent spatial checks
 * established: intensity falling with distance from a storm core, and each
 * family enclosed by the one below it.
 */
class RainViewerPaletteTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Measured palette, faintest first. */
    private val faintestTan = argb(20, 99, 97, 89)
    private val strongestTan = argb(190, 222, 208, 151)
    private val darkestCool = argb(255, 0, 71, 104)
    private val brightestCool = argb(255, 136, 221, 238)
    private val darkestWarm = argb(255, 93, 0, 0)
    private val brightestWarm = argb(255, 255, 238, 0)
    private val magenta = argb(255, 255, 159, 255)

    @Test
    fun `nothing drawn is no rain`() {
        assertEquals(0f, RainViewerPalette.rateOf(argb(0, 0, 0, 0)), 0.0001f)
    }

    @Test
    fun `the families run in the order the storms put them in`() {
        val ladder = listOf(
            faintestTan,
            strongestTan,
            darkestCool,
            brightestCool,
            darkestWarm,
            brightestWarm,
            magenta,
        ).map { RainViewerPalette.rateOf(it) }

        ladder.zipWithNext { lighter, heavier ->
            assertTrue("$lighter should be under $heavier", lighter < heavier)
        }
    }

    @Test
    fun `the translucent ramp rises with its alpha`() {
        // Within the faint family, alpha alone is the intensity - which is what
        // makes it the low end of one scale rather than a separate overlay.
        val rising = listOf(20, 46, 84, 130, 190).map {
            RainViewerPalette.fractionOf(it, 120, 115, 100)
        }
        rising.zipWithNext { a, b -> assertTrue("$a should be under $b", a < b) }
    }

    @Test
    fun `the faintest trace is a drizzle and the top of the scale is not`() {
        val drizzle = RainViewerPalette.rateOf(faintestTan)
        val extreme = RainViewerPalette.rateOf(magenta)

        assertTrue("faintest echo was $drizzle mm/h", drizzle < 0.2f)
        // The top of the scale is torrential, but capped at the point where the
        // echo stops being rain: about as hard as rain ever actually falls,
        // rather than the four-figure nonsense an uncapped Z-R relation gives.
        assertTrue("top of scale was $extreme mm/h", extreme in 50f..150f)
    }

    @Test
    fun `Marshall-Palmer matches the values it is known by`() {
        // The textbook anchors for Z = 200 R^1.6.
        assertEquals(0.65, RainViewerPalette.millimetresPerHour(20.0), 0.05)
        assertEquals(11.5, RainViewerPalette.millimetresPerHour(40.0), 0.5)
        assertEquals(48.6, RainViewerPalette.millimetresPerHour(50.0), 2.0)
    }

    @Test
    fun `every family stays inside its own band of the scale`() {
        // The bands are contiguous and non-overlapping, so a colour cannot be
        // read as belonging to a heavier family than it does.
        val tan = RainViewerPalette.fractionOf(190, 222, 208, 151)
        val cool = RainViewerPalette.fractionOf(255, 0, 71, 104)
        val warm = RainViewerPalette.fractionOf(255, 93, 0, 0)

        assertTrue(tan <= 0.37 + 0.0001)
        assertTrue(cool >= 0.37 - 0.0001 && cool <= 0.67 + 0.0001)
        assertTrue(warm >= 0.67 - 0.0001 && warm <= 0.97 + 0.0001)
    }

    @Test
    fun `an unfamiliar colour is clamped rather than sent off the scale`() {
        // The palette has shifted between samples - 65, 67 and 70 levels on
        // different days - so the decoder must not assume it has seen every
        // colour. Anything unexpected has to land somewhere sane.
        listOf(
            argb(255, 255, 255, 255),
            argb(255, 0, 0, 0),
            argb(128, 7, 7, 7),
            argb(255, 17, 200, 3),
        ).forEach { colour ->
            val fraction = RainViewerPalette.fractionOf(
                (colour ushr 24) and 0xFF,
                (colour ushr 16) and 0xFF,
                (colour ushr 8) and 0xFF,
                colour and 0xFF,
            )
            assertTrue("fraction $fraction out of range", fraction in 0.0..1.0)
            assertTrue(RainViewerPalette.rateOf(colour).isFinite())
        }
    }
}
