package lv.bolwarra.wetter.domain.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A day's row against the hours it is a summary of.
 *
 * The case these were written from, on a real stitched forecast for Rīga: Sunday
 * held 3.2 mm with two hours at or above half a millimetre, and its row said
 * `OVERCAST` with a peak of 0.27 mm/h — which is 1.6 divided by six, the rate of
 * a six-hour block. The week drew a plain cloud on a day whose own bar said rain
 * began at eight in the evening.
 *
 * Neither provider was wrong. The disagreement was created by joining them, and
 * can only be repaired after the join.
 */
class DailyFromHoursTest {

    private val zone: ZoneId = ZoneId.of("Europe/Riga")

    /** Local midnight, as an instant. */
    private val midnight: Instant = Instant.parse("2026-09-12T21:00:00Z")
    private val date: LocalDate = LocalDate.parse("2026-09-13")

    private fun hour(hourOfDay: Int, mm: Double?) = HourlyWeather(
        timestamp = midnight.plus(Duration.ofHours(hourOfDay.toLong())),
        temperature = 14.0,
        apparentTemperature = null,
        precipitationProbability = null,
        precipitation = mm,
        rain = null,
        snowfall = null,
        condition = if ((mm ?: 0.0) > 0.0) WeatherCondition.RAIN else WeatherCondition.OVERCAST,
        windSpeed = null,
        windGust = null,
        uvIndex = null,
        cloudCover = null,
        cloudLow = null,
        cloudMedium = null,
        cloudHigh = null,
        isDay = true,
    )

    private fun day(condition: WeatherCondition, peak: Double?) = DailyWeather(
        date = date,
        temperatureMin = 12.0,
        temperatureMax = 16.0,
        condition = condition,
        precipitationTotal = 3.2,
        precipitationPeakRate = peak,
        precipitationProbabilityMax = null,
        precipitationHours = null,
        sunrise = null,
        sunset = null,
        windSpeedMax = null,
    )

    private fun forecast(hours: List<HourlyWeather>, days: List<DailyWeather>) = WeatherForecast(
        location = WeatherLocation("Riga", 56.9496, 24.1052, zone),
        current = CurrentWeather(
            observedAt = midnight,
            temperature = 14.0,
            apparentTemperature = null,
            condition = WeatherCondition.OVERCAST,
            isDay = true,
            precipitation = null,
            windSpeed = null,
            windGust = null,
            windDirection = null,
            humidity = null,
            pressure = null,
        ),
        hourly = hours,
        daily = days,
        fetchedAt = midnight,
        provider = ProviderMetadata(
            id = "test",
            name = "Test",
            model = null,
            resolutionKm = null,
            forecastGeneratedAt = null,
            attribution = "Test",
        ),
    )

    /** Dry all day until rain arrives at eight in the evening. */
    private val eveningRain = (0..23).map { hour(it, if (it >= 20) 0.9 else 0.0) }

    @Test
    fun `the measured case, both halves of it`() {
        val before = forecast(eveningRain, listOf(day(WeatherCondition.OVERCAST, peak = 0.2667)))
        val after = before.withDailyReadFromHours().daily.single()

        // The peak is the hardest hour a reader could scroll to, not the rate of
        // a six-hour block they never see.
        assertEquals(0.9, after.precipitationPeakRate!!, 0.0001)

        // And the day no longer headlines itself as dry.
        assertTrue("the day still hides its rain", after.condition.isPrecipitating)
        assertEquals(WeatherCondition.RAIN, after.appearance)
    }

    @Test
    fun `a day whose provider already says rain keeps its own word`() {
        // A code is evidence in a way an absence is not: a provider summarising
        // a drizzle that never peaks has still seen something.
        val drizzleAllDay = (0..23).map { hour(it, 0.2) }
        val after = forecast(drizzleAllDay, listOf(day(WeatherCondition.DRIZZLE, peak = null)))
            .withDailyReadFromHours().daily.single()

        assertEquals(WeatherCondition.DRIZZLE, after.condition)
        // Intensity is still reconciled downwards, so it draws as drizzle.
        assertEquals(WeatherCondition.DRIZZLE, after.appearance)
    }

    @Test
    fun `a genuinely dry day is not given rain it does not have`() {
        val dry = (0..23).map { hour(it, 0.0) }
        val after = forecast(dry, listOf(day(WeatherCondition.OVERCAST, peak = 5.0)))
            .withDailyReadFromHours().daily.single()

        assertEquals(WeatherCondition.OVERCAST, after.condition)
        assertEquals(0.0, after.precipitationPeakRate!!, 0.0001)
    }

    @Test
    fun `a trace day is not promoted to rain`() {
        // Below the bar the app names rain at, an overcast day stays overcast.
        // Otherwise every grey fortnight in a maritime climate grows rain marks.
        val trace = (0..23).map { hour(it, 0.2) }
        val after = forecast(trace, listOf(day(WeatherCondition.OVERCAST, peak = null)))
            .withDailyReadFromHours().daily.single()

        assertEquals(WeatherCondition.OVERCAST, after.condition)
    }

    @Test
    fun `a day beyond the hourly horizon is left exactly as it arrived`() {
        // Nothing better to say, and inventing a summary from no hours would be
        // worse than repeating the provider's.
        val far = day(WeatherCondition.RAIN, peak = 1.5).copy(date = date.plusDays(9))
        val after = forecast(eveningRain, listOf(far)).withDailyReadFromHours().daily.single()

        assertEquals(WeatherCondition.RAIN, after.condition)
        assertEquals(1.5, after.precipitationPeakRate!!, 0.0001)
    }

    @Test
    fun `hours with no measurement leave the peak absent rather than zero`() {
        val unknown = (0..23).map { hour(it, null) }
        val after = forecast(unknown, listOf(day(WeatherCondition.OVERCAST, peak = null)))
            .withDailyReadFromHours().daily.single()

        assertNull("an absent measurement became a zero", after.precipitationPeakRate)
        assertEquals(WeatherCondition.OVERCAST, after.condition)
    }

    @Test
    fun `the day boundary is the location's own midnight`() {
        // An hour at 23:00 belongs to this day and one at 00:00 to the next, in
        // the place's own zone rather than UTC.
        val spanning = (0..47).map { hour(it, if (it == 23) 4.0 else 0.0) }
        val today = day(WeatherCondition.OVERCAST, peak = null)
        val tomorrow = today.copy(date = date.plusDays(1))

        val after = forecast(spanning, listOf(today, tomorrow)).withDailyReadFromHours()
        assertEquals(4.0, after.daily.first().precipitationPeakRate!!, 0.0001)
        assertEquals(0.0, after.daily.last().precipitationPeakRate!!, 0.0001)
    }

    @Test
    fun `an empty forecast is returned untouched`() {
        val empty = forecast(emptyList(), emptyList())
        assertEquals(empty, empty.withDailyReadFromHours())
    }
}
