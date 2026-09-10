package lv.bolwarra.wetter.ui.format

import java.time.Duration
import kotlin.math.abs
import lv.bolwarra.wetter.domain.settings.PrecipitationUnit
import lv.bolwarra.wetter.domain.settings.TemperatureUnit
import lv.bolwarra.wetter.domain.settings.WindUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last few centimetres, where a correct number becomes a wrong one.
 *
 * Everything below the formatter has been checked against something external.
 * This checks the conversion arithmetic against defined constants, and the
 * rendering against the handful of boundaries where rounding turns a reading
 * into a lie.
 */
class ReadingReliabilityTest {

    // ---- A1: conversions, against the definitions -----------------------------

    @Test
    fun `temperature converts at the defined points`() {
        // The three anchors of the Fahrenheit scale, including the one place
        // the two scales cross - which catches a sign error that the other two
        // would not.
        assertEquals(32.0, TemperatureUnit.FAHRENHEIT.from(0.0), 0.0001)
        assertEquals(212.0, TemperatureUnit.FAHRENHEIT.from(100.0), 0.0001)
        assertEquals(-40.0, TemperatureUnit.FAHRENHEIT.from(-40.0), 0.0001)
        assertEquals(0.0, TemperatureUnit.CELSIUS.from(0.0), 0.0001)
        assertEquals(-273.15, TemperatureUnit.CELSIUS.from(-273.15), 0.0001)
    }

    @Test
    fun `a difference is not converted like a reading`() {
        // One degree of difference is 1.8 F, not 33.8. This is the arithmetic
        // that the deleted local-correction figure used, and the distinction is
        // exactly the kind that survives review and fails in the field - so it
        // is pinned here whether or not anything currently calls it.
        assertEquals(1.8, TemperatureUnit.FAHRENHEIT.difference(1.0), 0.0001)
        assertEquals(-1.8, TemperatureUnit.FAHRENHEIT.difference(-1.0), 0.0001)
        assertEquals(0.0, TemperatureUnit.FAHRENHEIT.difference(0.0), 0.0001)
        assertEquals(1.0, TemperatureUnit.CELSIUS.difference(1.0), 0.0001)

        // And they must genuinely differ, or one of them is wrong.
        assertTrue(
            "difference() and from() must not be the same function",
            abs(TemperatureUnit.FAHRENHEIT.from(1.0) - TemperatureUnit.FAHRENHEIT.difference(1.0)) >
                30.0,
        )
    }

    @Test
    fun `wind converts at the defined ratios`() {
        // 1 m/s is exactly 3.6 km/h; a knot is exactly 1852 m per hour; a mile
        // is exactly 1609.344 m.
        assertEquals(3.6, WindUnit.KILOMETRES_PER_HOUR.from(1.0), 0.0001)
        assertEquals(3600.0 / 1609.344, WindUnit.MILES_PER_HOUR.from(1.0), 0.0001)
        assertEquals(3600.0 / 1852.0, WindUnit.KNOTS.from(1.0), 0.0001)
        assertEquals(1.0, WindUnit.METRES_PER_SECOND.from(1.0), 0.0001)

        // Beaufort 8, the app's gale threshold, in each unit a reader may pick.
        assertEquals(61.92, WindUnit.KILOMETRES_PER_HOUR.from(17.2), 0.01)
        assertEquals(33.44, WindUnit.KNOTS.from(17.2), 0.01)
    }

    @Test
    fun `precipitation converts at the defined ratio`() {
        // An inch is exactly 25.4 mm.
        assertEquals(1.0, PrecipitationUnit.INCHES.from(25.4), 0.0001)
        assertEquals(0.0, PrecipitationUnit.INCHES.from(0.0), 0.0001)
        assertEquals(25.4, PrecipitationUnit.MILLIMETRES.from(25.4), 0.0001)
    }

    // ---- E2: the boundaries where rounding lies -------------------------------

    @Test
    fun `a temperature just below zero is not written as minus nothing`() {
        // -0.4 C rounds to zero, and "-0" is not a temperature. The reader sees
        // a minus sign and reads it as freezing.
        listOf(-0.4, -0.1, -0.01, -0.49).forEach { celsius ->
            val shown = formatTemperature(celsius, TemperatureUnit.CELSIUS)
            assertFalse(
                "$celsius rendered as $shown",
                shown.startsWith("-0°") || shown.startsWith("−0°"),
            )
        }
        // And the same crossing in Fahrenheit, which happens at a different
        // Celsius value entirely: -17.8 C is 0 F.
        listOf(-17.9, -18.0, -17.85).forEach { celsius ->
            val shown = formatTemperature(celsius, TemperatureUnit.FAHRENHEIT)
            assertFalse(
                "$celsius rendered as $shown",
                shown.startsWith("-0°") || shown.startsWith("−0°"),
            )
        }
    }

    @Test
    fun `a real zero is still written as zero`() {
        assertEquals("0°", formatTemperature(0.0, TemperatureUnit.CELSIUS))
        assertEquals("0°", formatTemperature(0.2, TemperatureUnit.CELSIUS))
    }

    @Test
    fun `an absent reading is absent, not zero`() {
        // The distinction the whole app rests on: a station that reported no
        // rain and a station that reported nothing are different facts.
        assertEquals(NO_READING, formatTemperature(null, TemperatureUnit.CELSIUS))
        assertEquals(NO_READING, formatMillimetres(null, PrecipitationUnit.MILLIMETRES))
        assertEquals(NO_READING, formatWindSpeed(null, WindUnit.METRES_PER_SECOND))
        assertEquals(NO_READING, formatPercent(null))
        assertEquals(NO_READING, formatPressure(null))

        // And a genuine zero is not absent.
        assertFalse(formatMillimetres(0.0, PrecipitationUnit.MILLIMETRES) == NO_READING)
        assertFalse(formatWindSpeed(0.0, WindUnit.METRES_PER_SECOND) == NO_READING)
    }

    @Test
    fun `inches keep enough decimals to say anything at all`() {
        // A millimetre of rain is 0.04 in. At one decimal every ordinary shower
        // in the world rounds to 0.0, which would make the imperial reader's
        // whole chart read dry.
        val oneMillimetre = formatMillimetres(1.0, PrecipitationUnit.INCHES)
        assertFalse(
            "1 mm shows as $oneMillimetre in inches",
            oneMillimetre.startsWith("0.0 ") || oneMillimetre == "0.0",
        )
        assertEquals(2, PrecipitationUnit.INCHES.decimals)
        assertEquals(1, PrecipitationUnit.MILLIMETRES.decimals)
    }

    @Test
    fun `extreme but real readings render without nonsense`() {
        // The coldest and hottest inhabited places, and a hurricane. None of
        // these should produce an empty string, a NaN or a stray sign.
        val cases = listOf(
            formatTemperature(-67.8, TemperatureUnit.CELSIUS),
            formatTemperature(56.7, TemperatureUnit.FAHRENHEIT),
            formatWindSpeed(113.0, WindUnit.KILOMETRES_PER_HOUR),
            formatMillimetres(305.0, PrecipitationUnit.MILLIMETRES),
            formatPressure(870.0),
            formatPressure(1083.8),
        )
        cases.forEach {
            assertTrue("empty or broken: '$it'", it.isNotBlank())
            assertFalse("NaN leaked into '$it'", it.contains("NaN"))
            assertFalse("infinity leaked into '$it'", it.contains("Infinity", ignoreCase = true))
        }
    }

    @Test
    fun `a duration of no length is not rendered as a day`() {
        // Polar day and polar night both produce a null sunrise, and anything
        // that reaches formatDuration with a zero or negative value must not
        // wrap round into a large number.
        assertEquals(NO_READING, formatDuration(null))
        val zero = formatDuration(Duration.ZERO)
        assertTrue("zero duration rendered as '$zero'", zero.startsWith("0"))
    }

    @Test
    fun `a percentage stays inside its own bounds`() {
        assertEquals("0%", formatPercent(0))
        assertEquals("100%", formatPercent(100))
    }
}
