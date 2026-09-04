package lv.bolwarra.wetter.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette's rules, enforced.
 *
 * The point of generating tones rather than picking them is that the rules can
 * be checked. Measuring the hand-picked palette this replaced found the most
 * used colour in the app failing WCAG AA in both themes, two roles holding
 * identical values under different names, and a temperature that was the same
 * colour as a severe-weather alert. Every one of those would have been caught
 * here on the day it was introduced.
 *
 * Contrast is computed from the sRGB values the screen actually receives, not
 * from the lightness they were asked for, so gamut clamping cannot hide a
 * failure behind a correct-looking specification.
 */
class PaletteTest {

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    /**
     * CIE chroma, which is what "loud" means here.
     *
     * The first version of this test used the spread between the largest and
     * smallest sRGB channel, which is not chroma and ranked a saturated blue
     * below a duller orange - the channels simply do not distribute evenly
     * across hues. Converting properly is twenty lines and is the difference
     * between a rule and a coincidence.
     */
    private fun chroma(c: Color): Double {
        fun linear(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(c.red)
        val g = linear(c.green)
        val b = linear(c.blue)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val a = 500.0 * (f(x) - f(y))
        val bb = 200.0 * (f(y) - f(z))
        return hypot(a, bb)
    }

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    private val plates = mapOf("light" to LightWetterColors, "dark" to DarkWetterColors)

    /** Every role that carries text or a line, and the bar it must clear. */
    private fun readable(p: WetterColors) = mapOf(
        "textPrimary" to (p.textPrimary to 12.0),
        "textSecondary" to (p.textSecondary to 6.8),
        "textTertiary" to (p.textTertiary to 4.5),
        "precipitation" to (p.precipitation to 4.5),
        "interactive" to (p.interactive to 4.5),
        "temperatureWarm" to (p.temperatureWarm to 4.5),
        "temperatureCool" to (p.temperatureCool to 4.5),
        "warning" to (p.warning to 4.5),
        "danger" to (p.danger to 4.5),
        "positive" to (p.positive to 4.5),
        "negative" to (p.negative to 4.5),
        "informational" to (p.informational to 4.5),
    )

    @Test
    fun `everything that is read is readable, on both plates`() {
        plates.forEach { (name, p) ->
            readable(p).forEach { (role, spec) ->
                val (colour, floor) = spec
                val measured = contrast(colour, p.surface)
                assertTrue(
                    "$name/$role measured $measured against its ground, needs $floor",
                    measured >= floor,
                )
            }
        }
    }

    @Test
    fun `the two plates are one system, not two palettes`() {
        // The same role carries the same weight in both, which is what makes the
        // dark theme the light theme seen at night rather than a second design.
        readable(LightWetterColors).forEach { (role, _) ->
            val light =
                contrast(
                    readable(LightWetterColors).getValue(role).first,
                    LightWetterColors.surface,
                )
            val dark =
                contrast(readable(DarkWetterColors).getValue(role).first, DarkWetterColors.surface)
            assertEquals("$role differs between plates", light, dark, 0.6)
        }
    }

    @Test
    fun `no role is a duplicate of another under a second name`() {
        plates.forEach { (name, p) ->
            val roles = mapOf(
                "surface" to p.surface,
                "surfaceRaised" to p.surfaceRaised,
                "surfaceSunken" to p.surfaceSunken,
                "surfaceStrong" to p.surfaceStrong,
                "hairline" to p.hairline,
                "gridline" to p.gridline,
                "textPrimary" to p.textPrimary,
                "textSecondary" to p.textSecondary,
                "textTertiary" to p.textTertiary,
                "textDisabled" to p.textDisabled,
                "interactive" to p.interactive,
                "precipitation" to p.precipitation,
                "temperatureWarm" to p.temperatureWarm,
                "temperatureCool" to p.temperatureCool,
                "day" to p.day,
                "night" to p.night,
                "warning" to p.warning,
                "danger" to p.danger,
                "positive" to p.positive,
                "negative" to p.negative,
            )
            val byValue = roles.entries.groupBy { it.value }.filter { it.value.size > 1 }
            assertTrue(
                "$name has duplicate roles: " +
                    byValue.values.joinToString { g -> g.joinToString("=") { it.key } },
                byValue.isEmpty(),
            )
        }
    }

    @Test
    fun `neither ground is an extreme`() {
        // Pure white is a light source rather than a surface; pure black leaves
        // nothing for a sunken surface to recede into.
        plates.forEach { (name, p) ->
            val l = luminance(p.surface)
            assertTrue("$name ground is white", l < 0.97)
            assertTrue("$name ground is black", l > 0.008)
        }
    }

    @Test
    fun `light falls from above on both plates`() {
        // A dark theme that darkens its raised surfaces is lighting the scene
        // from below, which reads as a hole rather than as a step.
        plates.forEach { (name, p) ->
            assertTrue(
                "$name raised is not lifted",
                luminance(p.surfaceRaised) > luminance(p.surface),
            )
            assertTrue(
                "$name sunken is not recessed",
                luminance(p.surfaceSunken) < luminance(p.surface),
            )
        }
    }

    @Test
    fun `disabled sits below the readable bar, deliberately`() {
        plates.forEach { (name, p) ->
            val measured = contrast(p.textDisabled, p.surface)
            assertTrue("$name disabled reads as live at $measured", measured < 3.0)
            assertTrue("$name disabled is invisible at $measured", measured > 1.8)
        }
    }

    @Test
    fun `rain is the loudest hue, and temperature cannot out-shout it`() {
        // The app's oldest rule. Chroma is the enforcement, so it is measured as
        // distance from the neutral axis rather than trusted to the eye.
        plates.forEach { (name, p) ->
            assertTrue(
                "$name: temperature is as loud as rain",
                chroma(p.temperatureWarm) < chroma(p.precipitation),
            )
            assertTrue(
                "$name: temperature is as loud as rain",
                chroma(p.temperatureCool) < chroma(p.precipitation),
            )
            // Danger is the one role allowed to be louder, because it outranks
            // every other thing on the screen by definition.
            assertTrue(
                "$name: danger is quieter than rain",
                chroma(p.danger) >= chroma(p.precipitation),
            )
        }
    }

    @Test
    fun `structure is present but never draws the eye`() {
        plates.forEach { (name, p) ->
            val hairline = contrast(p.hairline, p.surface)
            val gridline = contrast(p.gridline, p.surface)
            assertTrue("$name hairline invisible at $hairline", hairline > 1.4)
            assertTrue("$name hairline shouts at $hairline", hairline < 2.4)
            assertTrue("$name gridline louder than hairline", gridline < hairline)
        }
    }

    @Test
    fun `the filled band carries its own text`() {
        plates.forEach { (name, p) ->
            val measured = contrast(p.onSurfaceStrong, p.surfaceStrong)
            assertTrue("$name band text measured $measured", measured >= 4.5)
        }
    }
}
