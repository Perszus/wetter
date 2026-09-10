package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zones that do not run on the whole hour.
 *
 * About a fifth of the people on earth live on a clock offset from UTC by half
 * or three quarters of an hour - India alone is 1.4 billion - and their forecast
 * rows land at :15, :30 or :45 past the UTC hour. Every piece of logic that
 * reaches for "the hour we are in" has to find it there.
 *
 * Written after a measured failure: in Kathmandu at 21:54 local the rain tile
 * read `0.0 mm/h` while the dial beside it said `Drizzle` and the hour covering
 * the reader held 0.4 mm. The window had been opened by truncating the clock to
 * a whole UTC hour, which landed before the row covering now, dropped it, and
 * left the lookup falling off the front into a default of zero.
 */
class SubHourZoneTest {

    /** Kathmandu is UTC+05:45, so a local hour begins at :15 past the UTC one. */
    private val nepal = ZoneId.of("Asia/Kathmandu")

    private fun hour(at: String, precipitation: Double) = HourlyWeather(
        timestamp = Instant.parse(at),
        temperature = 21.0,
        apparentTemperature = null,
        precipitationProbability = null,
        precipitation = precipitation,
        rain = null,
        snowfall = null,
        condition = WeatherCondition.DRIZZLE,
        windSpeed = null,
        windGust = null,
        uvIndex = null,
        cloudCover = null,
        cloudLow = null,
        cloudMedium = null,
        cloudHigh = null,
        isDay = false,
    )

    /** 21:00, 22:00 and 23:00 on the Kathmandu clock. */
    private val series = listOf(
        hour("2026-09-10T15:15:00Z", 0.4),
        hour("2026-09-10T16:15:00Z", 0.4),
        hour("2026-09-10T17:15:00Z", 0.0),
    )

    /** 21:54 local, which is inside the first row. */
    private val now: Instant = Instant.parse("2026-09-10T16:09:00Z")

    @Test
    fun `the window opens on the hour the reader is actually in`() {
        val window = series.window(now, 6)
        assertTrue("the window is empty", window.isNotEmpty())
        assertEquals(
            "the window must start at 21:00 local, not 22:00",
            Instant.parse("2026-09-10T15:15:00Z"),
            window.first().timestamp,
        )
    }

    @Test
    fun `the rate for now is the rate of the hour covering now`() {
        // The failure exactly: this returned null, and the caller's elvis turned
        // that into 0.0 mm/h on the main screen while it was drizzling.
        val row = series.window(now, 6).at(now)
        assertNotNull("no row covers 21:54 local", row)
        assertEquals(0.4, row!!.precipitation!!, 0.0001)
    }

    @Test
    fun `hourCovering finds the row that has begun, not the clock's hour`() {
        assertEquals(
            Instant.parse("2026-09-10T15:15:00Z"),
            series.hourCovering(now),
        )
        // On the boundary the new row has begun and is the answer.
        assertEquals(
            Instant.parse("2026-09-10T16:15:00Z"),
            series.hourCovering(Instant.parse("2026-09-10T16:15:00Z")),
        )
    }

    @Test
    fun `a whole-hour zone is completely unaffected`() {
        // The fix must be a no-op wherever the clock and UTC agree, which is
        // most of the world and every existing test.
        val onTheHour = listOf(
            hour("2026-09-10T15:00:00Z", 0.4),
            hour("2026-09-10T16:00:00Z", 0.7),
            hour("2026-09-10T17:00:00Z", 0.0),
        )
        val at = Instant.parse("2026-09-10T16:09:00Z")
        assertEquals(Instant.parse("2026-09-10T16:00:00Z"), onTheHour.hourCovering(at))
        assertEquals(
            Instant.parse("2026-09-10T16:00:00Z"),
            onTheHour.window(at, 6).first().timestamp,
        )
    }

    @Test
    fun `a forecast entirely in the future still opens a window`() {
        // A cache read a moment before the first row. There is no row that has
        // begun, so the clock is the only answer left, and the window must open
        // on what is coming rather than be empty.
        val future = series.map {
            it.copy(timestamp = it.timestamp.plus(Duration.ofHours(5)))
        }
        assertTrue(future.window(now, 12).isNotEmpty())
    }

    @Test
    fun `an empty series does not throw`() {
        assertTrue(emptyList<HourlyWeather>().window(now, 6).isEmpty())
        assertEquals(
            now.truncatedTo(java.time.temporal.ChronoUnit.HOURS),
            emptyList<HourlyWeather>().hourCovering(now),
        )
    }

    @Test
    fun `every sub-hour zone on earth finds its current row`() {
        // The full set of offsets that are not whole hours.
        val zones = listOf(
            "Asia/Kolkata", "Asia/Kathmandu", "Asia/Tehran", "Asia/Kabul",
            "Asia/Yangon", "America/St_Johns", "Pacific/Chatham",
            "Australia/Lord_Howe", "Australia/Adelaide", "Pacific/Marquesas",
        )
        val reference = Instant.parse("2026-09-10T16:09:00Z")

        zones.forEach { id ->
            val zone = ZoneId.of(id)
            val offset = zone.rules.getOffset(reference).totalSeconds
            if (offset % 3600 == 0) return@forEach

            // Rows on that zone's own clock hours, which is what a provider
            // asked for local time returns.
            val localHour = reference.atZone(zone).truncatedTo(
                java.time.temporal.ChronoUnit.HOURS,
            )
            val rows = (0..3).map {
                hour(localHour.plusHours(it.toLong()).toInstant().toString(), 0.4)
            }

            val row = rows.window(reference, 6).at(reference)
            assertNotNull("$id: no row covers now", row)
            assertEquals("$id: wrong row", 0.4, row!!.precipitation!!, 0.0001)
        }
    }
}
