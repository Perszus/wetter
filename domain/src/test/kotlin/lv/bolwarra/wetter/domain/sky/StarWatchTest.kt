package lv.bolwarra.wetter.domain.sky

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarWatchTest {

    // Midday UTC in March, so the scan starts in daylight and has a whole night
    // ahead of it - the ordinary case somebody opens the app in.
    private val noon = Instant.parse("2026-03-14T12:00:00Z")

    private val riga = 56.9496 to 24.1052
    private val svalbardInSummer = 78.22 to 15.63

    private fun hours(count: Int, cloud: (Int) -> Int) = List(count) { index ->
        HourlyWeather(
            timestamp = noon.plus(Duration.ofHours(index.toLong())),
            temperature = 2.0,
            apparentTemperature = null,
            precipitationProbability = null,
            precipitation = 0.0,
            rain = null,
            snowfall = null,
            condition = WeatherCondition.CLEAR,
            windSpeed = null,
            windGust = null,
            uvIndex = null,
            cloudLow = cloud(index),
            cloudMedium = 0,
            cloudHigh = 0,
            cloudCover = cloud(index),
            isDay = true,
        )
    }

    private fun tonight(hours: List<HourlyWeather>, place: Pair<Double, Double> = riga) =
        StarWatch.tonight(hours, noon, place.first, place.second, moonIllumination = 0.0)

    @Test
    fun `a clear night is found whole`() {
        val night = tonight(hours(24) { 0 })
        val best = night.best
        assertNotNull(best)
        // It cannot start before the sky is dark, and cannot end after it is not.
        assertTrue(!best!!.from.isBefore(night.darkFrom!!))
        assertTrue(best.to.isAfter(best.from))
    }

    @Test
    fun `darkness is found, and comes after the daylight it starts in`() {
        val night = tonight(hours(24) { 0 })
        assertNotNull(night.darkFrom)
        assertTrue(night.darkFrom!!.isAfter(noon))
        assertTrue(night.darkUntil!!.isAfter(night.darkFrom))
    }

    @Test
    fun `an overcast night has darkness but nothing worth naming`() {
        val night = tonight(hours(24) { 100 })
        assertNotNull(night.darkFrom)
        assertNull(night.best)
        assertTrue(night.isWorthShowing)
    }

    @Test
    fun `the longest break wins, not the first`() {
        // Clear at 20-21h, then clear again 23h-03h. Both are dark; the second
        // is longer and is the one worth setting an alarm for.
        val night = tonight(
            hours(24) { index ->
                when (index) {
                    in 8..9 -> 0
                    in 11..15 -> 0
                    else -> 100
                }
            },
        )
        val best = night.best!!
        assertEquals(noon.plus(Duration.ofHours(11)), best.from)
        assertEquals(noon.plus(Duration.ofHours(16)), best.to)
    }

    @Test
    fun `a polar summer has no darkness and says so`() {
        // Svalbard in June: the sun never goes below the horizon at all, let
        // alone twelve degrees under it. Nothing to report is the right answer,
        // not a window of zero length.
        val june = Instant.parse("2026-06-21T12:00:00Z")
        val midnightSun = StarWatch.tonight(
            List(24) { index ->
                hours(24) { 0 }[index].copy(timestamp = june.plus(Duration.ofHours(index.toLong())))
            },
            june,
            svalbardInSummer.first,
            svalbardInSummer.second,
            moonIllumination = 0.0,
        )
        assertNull(midnightSun.darkFrom)
        assertNull(midnightSun.best)
        assertTrue(!midnightSun.isWorthShowing)
    }

    @Test
    fun `rain keeps a clear sky off the list`() {
        val wet = hours(24) { 0 }.map { it.copy(precipitation = 1.2) }
        assertNull(tonight(wet).best)
    }
}
