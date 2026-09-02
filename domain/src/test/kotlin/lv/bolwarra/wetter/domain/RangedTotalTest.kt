package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The headline total has to describe the same stretch as the chart under it.
 *
 * The window rolls by the minute; the data arrives by the hour. Everything here
 * is about what happens where those two disagree.
 */
class RangedTotalTest {

    private val start = Instant.parse("2026-09-03T14:00:00Z")

    private fun hours(vararg millimetres: Double?) = millimetres.mapIndexed { index, mm ->
        HourlyWeather(
            timestamp = start.plus(Duration.ofHours(index.toLong())),
            temperature = null,
            precipitationProbability = null,
            precipitation = mm,
            rain = null,
            snowfall = null,
            condition = WeatherCondition.CLEAR,
            windSpeed = null,
            cloudCover = null,
            isDay = true,
        )
    }

    @Test
    fun `whole hours add up as they always did`() {
        val total = hours(1.0, 2.0, 3.0)
            .totalPrecipitation(start, start.plus(Duration.ofHours(3)))
        assertEquals(6.0, total!!, 0.0001)
    }

    @Test
    fun `a part-finished first hour is charged only for what is left`() {
        // Asked at 14:45 about the next hour: three quarters of the 14:00 hour
        // has already fallen and is not part of the answer.
        val from = start.plus(Duration.ofMinutes(45))
        val total = hours(4.0, 0.0).totalPrecipitation(from, from.plus(Duration.ofHours(1)))

        // A quarter of the first hour's 4 mm, and none of the dry hour after it.
        assertEquals(1.0, total!!, 0.0001)
    }

    @Test
    fun `a part-covered last hour is charged only for the part covered`() {
        val total = hours(0.0, 8.0).totalPrecipitation(start, start.plus(Duration.ofMinutes(90)))
        assertEquals(4.0, total!!, 0.0001)
    }

    @Test
    fun `a range inside a single hour takes its share`() {
        val from = start.plus(Duration.ofMinutes(15))
        val total = hours(6.0).totalPrecipitation(from, from.plus(Duration.ofMinutes(20)))
        assertEquals(2.0, total!!, 0.0001)
    }

    @Test
    fun `hours outside the range contribute nothing`() {
        val total = hours(5.0, 0.0, 5.0).totalPrecipitation(
            start.plus(Duration.ofHours(1)),
            start.plus(Duration.ofHours(2)),
        )
        assertEquals(0.0, total!!, 0.0001)
    }

    @Test
    fun `nothing reported is not the same as nothing falling`() {
        // A dash, not a zero: the difference between a dry six hours and a
        // provider that did not answer the question.
        assertNull(hours(null, null).totalPrecipitation(start, start.plus(Duration.ofHours(2))))
        assertNull(
            emptyList<HourlyWeather>().totalPrecipitation(start, start.plus(Duration.ofHours(2))),
        )
    }

    @Test
    fun `an empty or backwards range reports nothing`() {
        assertNull(hours(3.0).totalPrecipitation(start, start))
        assertNull(hours(3.0).totalPrecipitation(start.plus(Duration.ofHours(1)), start))
    }

    @Test
    fun `a widening stitched series pro-rates against its own step`() {
        // Past the horizon the rows step every six hours (docs/providers.md), so
        // a row's millimetres are spread over six hours, not one. Assuming an
        // hour would charge the window six times the rain.
        val wide = listOf(
            hours(12.0).first(),
            hours(0.0).first().copy(timestamp = start.plus(Duration.ofHours(6))),
        )
        val total = wide.totalPrecipitation(start, start.plus(Duration.ofHours(3)))

        assertEquals(6.0, total!!, 0.0001)
    }
}
