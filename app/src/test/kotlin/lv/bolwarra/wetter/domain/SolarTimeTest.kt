package lv.bolwarra.wetter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Solar geometry, checked against things that must be true rather than against a
 * table of numbers copied from somewhere.
 *
 * The invariants — longer days in June than December in the north, the reverse
 * in the south, twelve hours at the equator, no sunrise at all inside the arctic
 * circle in midwinter — would each fail loudly for a sign error, an
 * hour-angle mistake or a degrees/radians slip, which are the three ways this
 * algorithm actually goes wrong. Solar noon and the equinox day length are then
 * derived from first principles in the tests themselves, which pins the absolute
 * accuracy without trusting a number nobody in the repository can re-derive.
 */
class SolarTimeTest {

    private val riga = ZoneId.of("Europe/Riga")
    private val rigaLat = 56.9496
    private val rigaLon = 24.1052

    @Test
    fun `the sun rises before it sets`() {
        val day = SolarTime.sunriseSunset(LocalDate.of(2026, 3, 14), rigaLat, rigaLon, riga)
        assertNotNull(day.sunrise)
        assertNotNull(day.sunset)
        assertTrue(day.sunrise!!.isBefore(day.sunset!!))
    }

    @Test
    fun `northern days are long in June and short in December`() {
        val june = SolarTime.sunriseSunset(LocalDate.of(2026, 6, 21), rigaLat, rigaLon, riga).dayLength!!
        val december = SolarTime.sunriseSunset(LocalDate.of(2026, 12, 21), rigaLat, rigaLon, riga).dayLength!!

        assertTrue("Riga's midsummer day should exceed 17 hours", june.toMinutes() > 17 * 60)
        assertTrue("Riga's midwinter day should be under 7 hours", december.toMinutes() < 7 * 60)
    }

    @Test
    fun `the southern hemisphere has the opposite seasons`() {
        val zone = ZoneId.of("Pacific/Auckland")
        val june = SolarTime.sunriseSunset(LocalDate.of(2026, 6, 21), -36.85, 174.76, zone).dayLength!!
        val december = SolarTime.sunriseSunset(LocalDate.of(2026, 12, 21), -36.85, 174.76, zone).dayLength!!

        assertTrue("Auckland's June day should be the shorter one", june < december)
    }

    @Test
    fun `the equator has about twelve hours of daylight all year`() {
        val zone = ZoneId.of("Africa/Nairobi")
        listOf(
            LocalDate.of(2026, 3, 21),
            LocalDate.of(2026, 6, 21),
            LocalDate.of(2026, 9, 21),
            LocalDate.of(2026, 12, 21),
        ).forEach { date ->
            val minutes = SolarTime.sunriseSunset(date, 0.0, 36.8, zone).dayLength!!.toMinutes()
            assertTrue(
                "equator day length on $date was $minutes minutes",
                abs(minutes - 12 * 60) < 15,
            )
        }
    }

    @Test
    fun `inside the arctic circle midsummer has no sunset`() {
        // Longyearbyen, Svalbard.
        val zone = ZoneId.of("Arctic/Longyearbyen")
        val day = SolarTime.sunriseSunset(LocalDate.of(2026, 6, 21), 78.22, 15.65, zone)

        assertNull(day.sunrise)
        assertNull(day.sunset)
        assertTrue("midsummer above the arctic circle is polar day", day.isPolarDay)
        assertFalse(day.hasSunriseAndSunset)
    }

    @Test
    fun `inside the arctic circle midwinter has no sunrise`() {
        val zone = ZoneId.of("Arctic/Longyearbyen")
        val day = SolarTime.sunriseSunset(LocalDate.of(2026, 12, 21), 78.22, 15.65, zone)

        assertNull(day.sunrise)
        assertNull(day.sunset)
        assertFalse("midwinter above the arctic circle is polar night", day.isPolarDay)
    }

    @Test
    fun `polar day and polar night report the right daylight flag`() {
        val midsummerNoon = LocalDate.of(2026, 6, 21).atTime(0, 0)
            .atZone(ZoneId.of("Arctic/Longyearbyen")).toInstant()
        assertTrue("the sun is up at midnight in Svalbard in June", SolarTime.isDaylight(midsummerNoon, 78.22, 15.65))

        val midwinterNoon = LocalDate.of(2026, 12, 21).atTime(12, 0)
            .atZone(ZoneId.of("Arctic/Longyearbyen")).toInstant()
        assertFalse("the sun is down at noon in Svalbard in December", SolarTime.isDaylight(midwinterNoon, 78.22, 15.65))
    }

    @Test
    fun `daylight is true between the computed sunrise and sunset`() {
        val date = LocalDate.of(2026, 3, 14)
        val day = SolarTime.sunriseSunset(date, rigaLat, rigaLon, riga)
        val sunrise = day.sunrise!!
        val sunset = day.sunset!!
        val midday = sunrise.plusSeconds((sunset.epochSecond - sunrise.epochSecond) / 2)

        assertTrue(SolarTime.isDaylight(midday, rigaLat, rigaLon))
        assertFalse(SolarTime.isDaylight(sunrise.minusSeconds(1800), rigaLat, rigaLon))
        assertFalse(SolarTime.isDaylight(sunset.plusSeconds(1800), rigaLat, rigaLon))
    }

    @Test
    fun `solar noon falls where the longitude and the equation of time put it`() {
        // Derived rather than looked up. Riga keeps UTC+2 in mid-March, whose
        // meridian is 30 degrees east; at 24.105 east the sun is 5.9 degrees —
        // about 23.6 minutes — late on the clock. The equation of time near
        // 14 March is roughly minus nine minutes, delaying it a further nine.
        // So solar noon should sit at about 12:33 local, and sunrise and sunset
        // must straddle it symmetrically.
        val day = SolarTime.sunriseSunset(LocalDate.of(2026, 3, 14), rigaLat, rigaLon, riga)
        val sunrise = day.sunrise!!
        val sunset = day.sunset!!
        val noon = sunrise
            .plusSeconds((sunset.epochSecond - sunrise.epochSecond) / 2)
            .atZone(riga)
            .toLocalTime()

        val minutesOff = abs(noon.toSecondOfDay() / 60 - (12 * 60 + 33))
        assertTrue("solar noon computed as $noon, expected about 12:33", minutesOff <= 3)
    }

    @Test
    fun `day length on the equinox is about twelve hours everywhere`() {
        // The strongest absolute check available without a reference table: on the
        // equinox the terminator runs through both poles, so every latitude gets
        // the same day. It is a few minutes over twelve because sunrise is defined
        // by the sun's upper limb and refraction lifts it further.
        val equinox = LocalDate.of(2026, 3, 20)
        listOf(
            Triple(rigaLat, rigaLon, riga),
            Triple(-36.85, 174.76, ZoneId.of("Pacific/Auckland")),
            Triple(40.71, -74.01, ZoneId.of("America/New_York")),
        ).forEach { (latitude, longitude, zone) ->
            val minutes = SolarTime.sunriseSunset(equinox, latitude, longitude, zone).dayLength!!.toMinutes()
            assertTrue(
                "equinox day length at latitude $latitude was $minutes minutes",
                minutes in (12 * 60)..(12 * 60 + 20),
            )
        }
    }

    @Test
    fun `the days lengthen towards the equinox at the expected rate`() {
        // Six days before the equinox at 57 degrees north the day gains roughly
        // four and a half minutes daily, so it should be a little under twelve
        // hours — which is what pins the absolute answer, not just its shape.
        val before = SolarTime.sunriseSunset(LocalDate.of(2026, 3, 14), rigaLat, rigaLon, riga).dayLength!!
        val equinox = SolarTime.sunriseSunset(LocalDate.of(2026, 3, 20), rigaLat, rigaLon, riga).dayLength!!

        val gainedPerDay = (equinox.toMinutes() - before.toMinutes()) / 6.0
        assertTrue("gained $gainedPerDay minutes per day", gainedPerDay in 3.5..5.5)
        assertTrue("six days out the day was ${before.toMinutes()} minutes", before.toMinutes() in 690..710)
    }

    @Test
    fun `elevation peaks near local solar noon`() {
        val date = LocalDate.of(2026, 6, 21)
        val samples = (0..47).map { half ->
            val instant = date.atStartOfDay(riga).toInstant().plusSeconds(half * 1800L)
            instant to SolarTime.elevationDegrees(instant, rigaLat, rigaLon)
        }
        val highest = samples.maxBy { it.second }
        val localHour = highest.first.atZone(riga).hour

        assertEquals("the sun should be highest around 13:00 local in summer", 13, localHour)
    }
}
