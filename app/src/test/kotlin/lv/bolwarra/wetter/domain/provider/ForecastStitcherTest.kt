package lv.bolwarra.wetter.domain.provider

import lv.bolwarra.wetter.domain.model.WeatherLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Joining a short hourly forecast to a longer one.
 *
 * The properties that matter are that no hour is duplicated, no hour is
 * invented, none of the better source's data is discarded to tidy the join, and
 * a day's summary always comes from whichever source drew that day's bars.
 */
class ForecastStitcherTest {

    private val now: Instant = Instant.parse("2026-03-14T09:00:00Z")
    private val horizon: Duration = Duration.ofDays(6)

    private val riga = WeatherLocation(
        name = "Rīga",
        latitude = 56.9496,
        longitude = 24.1052,
        zone = ZoneId.of("Europe/Riga"),
    )

    private fun forecast(providerId: String, hours: Int, at: Instant = now) =
        FakeWeatherProvider.forecastFrom(providerId, riga, at, hourlyHours = hours)

    // --- deciding whether to bother ---------------------------------------------

    @Test
    fun `coverage is measured from now, not from the first hour`() {
        val started = forecast("regional", hours = 60, at = now.minus(Duration.ofHours(10)))

        // 60 rows from ten hours ago reach 49 hours past now, not 59.
        assertEquals(Duration.ofHours(49), ForecastStitcher.hourlyCoverage(started, now))
    }

    @Test
    fun `a forecast with no hourly rows covers nothing`() {
        val empty = forecast("regional", hours = 0)

        assertEquals(Duration.ZERO, ForecastStitcher.hourlyCoverage(empty, now))
        assertTrue(ForecastStitcher.needsExtending(empty, horizon, now))
    }

    @Test
    fun `a forecast that outruns the horizon is not short`() {
        val long = forecast("global", hours = 7 * 24)

        assertEquals(Duration.ZERO, ForecastStitcher.shortfall(long, horizon, now))
        assertFalse(ForecastStitcher.needsExtending(long, horizon, now))
    }

    @Test
    fun `a few missing hours are not worth a second request`() {
        // A forecast starting at local midnight is always a little short of any
        // round horizon by the time anyone looks at it. That is not a gap.
        val almost = forecast("global", hours = 144 - 6)

        assertTrue(ForecastStitcher.shortfall(almost, horizon, now) > Duration.ZERO)
        assertFalse(ForecastStitcher.needsExtending(almost, horizon, now))
    }

    @Test
    fun `stopping two days early is worth a second request`() {
        val short = forecast("regional", hours = 60)

        assertEquals(Duration.ofHours(85), ForecastStitcher.shortfall(short, horizon, now))
        assertTrue(ForecastStitcher.needsExtending(short, horizon, now))
    }

    // --- joining ------------------------------------------------------------------

    @Test
    fun `the join happens exactly where the first source stops`() {
        val primary = forecast("regional", hours = 60)
        val extension = forecast("global", hours = 7 * 24)

        val stitched = ForecastStitcher.stitch(primary, extension)

        assertEquals(7 * 24, stitched.hourly.size)
        assertEquals(primary.hourly.last().timestamp, stitched.hourly[59].timestamp)
        assertEquals(
            "the first added hour follows the last original one",
            primary.hourly.last().timestamp.plus(Duration.ofHours(1)),
            stitched.hourly[60].timestamp,
        )
    }

    @Test
    fun `no hour appears twice`() {
        val stitched = ForecastStitcher.stitch(forecast("regional", 60), forecast("global", 168))
        val timestamps = stitched.hourly.map { it.timestamp }

        assertEquals(timestamps.distinct(), timestamps)
        assertEquals(timestamps.sorted(), timestamps)
    }

    @Test
    fun `the better source keeps every hour it had`() {
        val primary = forecast("regional", hours = 60)
        val stitched = ForecastStitcher.stitch(primary, forecast("global", 168))

        // No good data is discarded to make the seam land on a day boundary.
        assertEquals(primary.hourly, stitched.hourly.take(60))
    }

    @Test
    fun `the primary stays the forecast's provider`() {
        val stitched = ForecastStitcher.stitch(forecast("regional", 60), forecast("global", 168))

        assertEquals("regional", stitched.provider.id)
        assertEquals("global", stitched.supplement!!.provider.id)
    }

    @Test
    fun `the supplement records where it took over`() {
        val primary = forecast("regional", hours = 60)
        val stitched = ForecastStitcher.stitch(primary, forecast("global", 168))

        assertEquals(
            primary.hourly.last().timestamp.plus(Duration.ofHours(1)),
            stitched.supplement!!.from,
        )
    }

    @Test
    fun `a day is summarised by whoever drew its hours`() {
        val primary = forecast("regional", hours = 30)
        val extension = forecast("global", hours = 168)
        val zone = riga.zone

        val stitched = ForecastStitcher.stitch(primary, extension)
        val seamDate = stitched.supplement!!.from.atZone(zone).toLocalDate()

        val firstDate = now.atZone(zone).toLocalDate()
        assertSame(
            "a day drawn entirely by the primary keeps the primary's summary",
            primary.daily.single { it.date == firstDate },
            stitched.daily.single { it.date == firstDate },
        )
        assertSame(
            "a day the extension drew is summarised by the extension",
            extension.daily.single { it.date == seamDate },
            stitched.daily.single { it.date == seamDate },
        )
    }

    @Test
    fun `days are not duplicated when both sources describe them`() {
        val stitched = ForecastStitcher.stitch(forecast("regional", 60), forecast("global", 168))
        val dates = stitched.daily.map { it.date }

        assertEquals(dates.distinct(), dates)
        assertEquals(dates.sorted(), dates)
    }

    // --- nothing to do -------------------------------------------------------------

    @Test
    fun `an extension that reaches no further changes nothing`() {
        val primary = forecast("regional", hours = 168)
        val extension = forecast("global", hours = 24)

        val stitched = ForecastStitcher.stitch(primary, extension)

        assertSame(primary, stitched)
        assertNull("nothing was added, so nothing supplemented it", stitched.supplement)
    }

    @Test
    fun `a primary with no hours at all takes the extension whole`() {
        val extension = forecast("global", hours = 48)
        val stitched = ForecastStitcher.stitch(forecast("regional", 0), extension)

        assertEquals(48, stitched.hourly.size)
        assertEquals(extension.hourly.first().timestamp, stitched.supplement!!.from)
    }
}
