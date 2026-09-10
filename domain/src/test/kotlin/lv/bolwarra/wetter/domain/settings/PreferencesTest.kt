package lv.bolwarra.wetter.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conversions, held to their fixed points.
 *
 * Every one of these is a number somebody can check against the world rather
 * than against the code: water freezes, water boils, a hurricane is a hurricane
 * whichever unit it is written in. A conversion test that recomputes the formula
 * it is testing proves only that the formula was typed twice.
 */
class PreferencesTest {

    @Test
    fun `temperature converts at the two points everybody knows`() {
        assertEquals(32.0, TemperatureUnit.FAHRENHEIT.from(0.0), 1e-9)
        assertEquals(212.0, TemperatureUnit.FAHRENHEIT.from(100.0), 1e-9)
        // The one place the two scales agree, which is the cheapest way to catch
        // an offset applied in the wrong direction.
        assertEquals(-40.0, TemperatureUnit.FAHRENHEIT.from(-40.0), 1e-9)
    }

    @Test
    fun `a difference is not converted like a reading`() {
        // A degree of learned bias is 1.8 F, not 33.8. Putting a correction
        // through the reading conversion would report a fifth of a degree of
        // drift as a heatwave.
        assertEquals(1.8, TemperatureUnit.FAHRENHEIT.difference(1.0), 1e-9)
        assertEquals(0.0, TemperatureUnit.FAHRENHEIT.difference(0.0), 1e-9)
        assertEquals(-1.8, TemperatureUnit.FAHRENHEIT.difference(-1.0), 1e-9)
    }

    @Test
    fun `celsius and millimetres are left exactly alone`() {
        // The domain's own units. A conversion that is not the identity here
        // would corrupt every reading before it reached any other unit.
        assertEquals(17.3, TemperatureUnit.CELSIUS.from(17.3), 0.0)
        assertEquals(17.3, TemperatureUnit.CELSIUS.difference(17.3), 0.0)
        assertEquals(4.2, PrecipitationUnit.MILLIMETRES.from(4.2), 0.0)
        assertEquals(9.9, WindUnit.METRES_PER_SECOND.from(9.9), 0.0)
    }

    @Test
    fun `wind converts against the storm thresholds`() {
        // A metre per second is 3.6 km/h exactly, and ten of them is 22.4 mph
        // and 19.4 knots. Round figures at a round input, so a transposed digit
        // in a constant shows up as a number that is obviously wrong rather than
        // as one that is nearly right.
        assertEquals(36.0, WindUnit.KILOMETRES_PER_HOUR.from(10.0), 1e-9)
        assertEquals(22.37, WindUnit.MILES_PER_HOUR.from(10.0), 0.01)
        assertEquals(19.44, WindUnit.KNOTS.from(10.0), 0.01)

        // And the threshold everybody has heard quoted: hurricane force is
        // 64 knots, which is where Beaufort 12 starts at 32.9 m/s.
        assertEquals(64.0, WindUnit.KNOTS.from(32.9), 0.1)
    }

    @Test
    fun `an inch is twenty-five point four millimetres`() {
        assertEquals(1.0, PrecipitationUnit.INCHES.from(25.4), 1e-9)
        assertEquals(0.5, PrecipitationUnit.INCHES.from(12.7), 1e-9)
    }

    @Test
    fun `inches are written to two places and millimetres to one`() {
        // A millimetre of rain is 0.04 in. At one decimal every ordinary shower
        // rounds to the same number and the column stops saying anything.
        assertEquals(2, PrecipitationUnit.INCHES.decimals)
        assertEquals(1, PrecipitationUnit.MILLIMETRES.decimals)
    }

    @Test
    fun `the defaults are the units the domain computes in`() {
        // Not a matter of taste: these are what both providers publish and what
        // every threshold in the app is written against, so a fresh install
        // draws exactly what the domain holds.
        val fresh = Preferences()

        assertEquals(TemperatureUnit.CELSIUS, fresh.temperature)
        assertEquals(WindUnit.METRES_PER_SECOND, fresh.wind)
        assertEquals(PrecipitationUnit.MILLIMETRES, fresh.precipitation)
        // Black. A weather app is opened at the ends of the day rather than the
        // middle, and deliberately not from the phone's dark mode: there is no
        // "follow the system" here, because each of these is a whole design
        // rather than a light switch.
        assertEquals(ThemeChoice.PURE_BLACK, fresh.theme)
    }
}
