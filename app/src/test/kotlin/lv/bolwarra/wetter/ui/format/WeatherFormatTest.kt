package lv.bolwarra.wetter.ui.format

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * How numbers become text. Small, but this is the last thing to touch a value
 * before somebody reads it, so the rounding and the missing case are worth
 * pinning.
 */
class WeatherFormatTest {

    // formatMillimetres deliberately formats in the user's locale, so the
    // expected separator depends on it. Pinning the locale keeps the test about
    // the rounding rule rather than about whoever runs it.
    private lateinit var original: Locale

    @Before
    fun pinLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `temperatures are rounded rather than truncated`() {
        assertEquals("18°", formatTemperature(18.4))
        assertEquals("19°", formatTemperature(18.5))
        assertEquals("-3°", formatTemperature(-2.6))
    }

    @Test
    fun `a temperature of zero is a reading, not a blank`() {
        assertEquals("0°", formatTemperature(0.0))
        assertEquals("0°", formatTemperature(-0.4))
    }

    @Test
    fun `an unknown temperature reads as a dash`() {
        // The distinction the whole nullable type exists for: "we don't know"
        // must not render as "zero degrees".
        assertEquals(NO_READING, formatTemperature(null))
    }

    @Test
    fun `millimetres keep a decimal only while it means something`() {
        assertEquals("0.4", formatMillimetres(0.4))
        assertEquals("9.9", formatMillimetres(9.94))
        assertEquals("12", formatMillimetres(12.4))
    }
}
