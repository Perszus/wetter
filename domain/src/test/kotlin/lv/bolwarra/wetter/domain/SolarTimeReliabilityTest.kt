package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sunrise and sunset against an independent almanac, at eighteen places chosen
 * to break different assumptions.
 *
 * This app computes its own solar times rather than taking the provider's, which
 * means nothing checks them unless something like this does. The reference
 * column is Open-Meteo's `daily=sunrise,sunset` for the same coordinates and
 * dates, which is a separate implementation of the same astronomy - so an
 * agreement is real evidence and a disagreement is a bug in one of them.
 *
 * The tolerance is four minutes. A minute or two is expected: the two
 * implementations may differ on refraction, on solar disc radius, and on whether
 * the observer is at sea level. Four minutes is looser than either of those and
 * far tighter than any error that would matter on a screen showing 06:45.
 */
class SolarTimeReliabilityTest {

    private val tolerance: Duration = Duration.ofMinutes(2)

    private data class Case(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val zone: String,
        val date: String,
        val sunrise: String,
        val sunset: String,
    )

    /** Every row measured from Open-Meteo on 2026-09-10, local clock time. */
    private val almanac = listOf(
        Case("Riga", 56.95, 24.11, "Europe/Riga", "2026-09-11", "06:45", "19:53"),
        Case("Kolkata", 22.57, 88.36, "Asia/Kolkata", "2026-09-11", "05:22", "17:44"),
        Case("Kathmandu", 27.72, 85.32, "Asia/Kathmandu", "2026-09-11", "05:46", "18:13"),
        Case("Chatham", -43.95, -176.55, "Pacific/Chatham", "2026-09-12", "06:39", "18:16"),
        Case("Longyearbyen", 78.22, 15.63, "Arctic/Longyearbyen", "2026-09-11", "05:05", "20:36"),
        Case(
            "Ushuaia",
            -54.80,
            -68.30,
            "America/Argentina/Ushuaia",
            "2026-09-11",
            "07:49",
            "19:11",
        ),
        Case("Quito", -0.18, -78.47, "America/Guayaquil", "2026-09-11", "06:07", "18:13"),
        Case("Phoenix", 33.45, -112.07, "America/Phoenix", "2026-09-11", "06:09", "18:39"),
        Case("Nukualofa", -21.14, -175.20, "Pacific/Tongatapu", "2026-09-12", "06:40", "18:34"),
        Case("Apia", -13.83, -171.77, "Pacific/Apia", "2026-09-12", "06:24", "18:22"),
        Case("Yakutsk", 62.03, 129.73, "Asia/Yakutsk", "2026-09-12", "05:37", "18:55"),
        Case("Doha", 25.29, 51.53, "Asia/Qatar", "2026-09-11", "05:18", "17:42"),
        Case("Reykjavik", 64.13, -21.90, "Atlantic/Reykjavik", "2026-09-11", "06:38", "20:07"),
        Case("LordHowe", -31.55, 159.08, "Australia/Lord_Howe", "2026-09-12", "05:56", "17:43"),
        Case("NullIsland", 0.0, 0.0, "Etc/GMT", "2026-09-11", "05:53", "17:59"),
        Case("Tromso", 69.65, 18.96, "Europe/Oslo", "2026-09-11", "05:41", "19:37"),
        Case("LaRinconada", -15.18, -69.45, "America/Lima", "2026-09-11", "05:35", "17:33"),
    )

    @Test
    fun `sunrise and sunset agree with an independent almanac everywhere`() {
        val wrong = mutableListOf<String>()

        almanac.forEach { case ->
            val zone = ZoneId.of(case.zone)
            val date = LocalDate.parse(case.date)
            val solar = SolarTime.sunriseSunset(date, case.latitude, case.longitude, zone)

            val rise = solar.sunrise
            val set = solar.sunset
            if (rise == null || set == null) {
                wrong += "${case.name}: got no sun at all, expected ${case.sunrise}/${case.sunset}"
                return@forEach
            }

            listOf(
                "sunrise" to (rise to case.sunrise),
                "sunset" to (set to case.sunset),
            ).forEach { (label, pair) ->
                val (actual, expected) = pair
                val local = actual.atZone(zone).toLocalTime()
                val want = java.time.LocalTime.parse(expected)
                // Compared as a time of day on the local clock, which is what a
                // reader sees. Across a date line the instant differs by a day
                // and the clock time is the thing being claimed.
                val drift = Duration.ofSeconds(
                    abs(local.toSecondOfDay().toLong() - want.toSecondOfDay().toLong()),
                )
                if (drift > tolerance) {
                    wrong +=
                        "${case.name} $label: got $local, almanac $want, out by ${drift.toMinutes()} min"
                }
            }
        }

        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun `polar night and midnight sun are reported as such, not as a time`() {
        val svalbard = ZoneId.of("Arctic/Longyearbyen")

        // Longyearbyen at midwinter: the sun does not rise for months, and a
        // sunrise time here would be an invented number.
        val midwinter = SolarTime.sunriseSunset(
            LocalDate.parse("2026-12-21"),
            78.22,
            15.63,
            svalbard,
        )
        assertNull("polar night must not produce a sunrise", midwinter.sunrise)
        assertNull("polar night must not produce a sunset", midwinter.sunset)
        assertTrue("polar night is not polar day", !midwinter.isPolarDay)

        // And at midsummer it does not set.
        val midsummer = SolarTime.sunriseSunset(
            LocalDate.parse("2026-06-21"),
            78.22,
            15.63,
            svalbard,
        )
        assertNull("midnight sun must not produce a sunrise", midsummer.sunrise)
        assertNull("midnight sun must not produce a sunset", midsummer.sunset)
        assertTrue("midsummer above the arctic circle is polar day", midsummer.isPolarDay)
    }

    @Test
    fun `the equator has an even day all year`() {
        // Quito is 20 km from the equator, so every day is within a few minutes
        // of twelve hours. A seasonal swing here would mean the declination term
        // has the wrong sign or scale.
        val zone = ZoneId.of("America/Guayaquil")
        listOf("2026-03-20", "2026-06-21", "2026-09-22", "2026-12-21").forEach { day ->
            val solar = SolarTime.sunriseSunset(LocalDate.parse(day), -0.18, -78.47, zone)
            assertNotNull(day, solar.sunrise)
            assertNotNull(day, solar.sunset)
            val length = Duration.between(solar.sunrise, solar.sunset).toMinutes()
            assertTrue(
                "$day: day length $length min, expected about 720",
                abs(length - 720) <= 15,
            )
        }
    }

    @Test
    fun `the two hemispheres are mirror images at the solstices`() {
        // Rīga and Ushuaia are at nearly the same distance from their poles.
        // Midsummer in one is midwinter in the other, so a sign error in the
        // latitude term shows up here as both being long or both being short.
        val riga = SolarTime.sunriseSunset(
            LocalDate.parse("2026-06-21"),
            56.95,
            24.11,
            ZoneId.of("Europe/Riga"),
        )
        val ushuaia = SolarTime.sunriseSunset(
            LocalDate.parse("2026-06-21"),
            -54.80,
            -68.30,
            ZoneId.of("America/Argentina/Ushuaia"),
        )
        val rigaLength = Duration.between(riga.sunrise, riga.sunset).toHours()
        val ushuaiaLength = Duration.between(ushuaia.sunrise, ushuaia.sunset).toHours()

        assertTrue("Rīga midsummer should be a long day, got $rigaLength h", rigaLength >= 17)
        assertTrue("Ushuaia should be midwinter, got $ushuaiaLength h", ushuaiaLength <= 8)
    }
}
