package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The umbrella, which is a decision somebody makes once on the way out.
 *
 * The rule it has to keep is that the answer belongs to the day rather than to
 * the moment it was asked. Somebody who checks at seven and takes a coat must
 * not find at eleven that the app has changed its mind about a morning that
 * already happened.
 */
class UmbrellaDayTest {

    private val zone: ZoneId = ZoneId.of("Europe/Riga")

    /** Local midnight on a Thursday, as an instant. */
    private val midnight: Instant = Instant.parse("2026-09-09T21:00:00Z")

    private fun at(hourOfDay: Int): Instant = midnight.plus(Duration.ofHours(hourOfDay.toLong()))

    private fun hour(hourOfDay: Int, mm: Double) = HourlyWeather(
        timestamp = at(hourOfDay),
        temperature = 12.0,
        apparentTemperature = null,
        precipitationProbability = null,
        precipitation = mm,
        rain = null,
        snowfall = null,
        condition = WeatherCondition.OVERCAST,
        windSpeed = null,
        windGust = null,
        uvIndex = null,
        cloudCover = null,
        cloudLow = null,
        cloudMedium = null,
        cloudHigh = null,
        isDay = true,
    )

    private fun forecast(hours: List<HourlyWeather>) = WeatherForecast(
        location = WeatherLocation("Riga", 56.9496, 24.1052, zone),
        current = CurrentWeather(
            observedAt = midnight,
            temperature = 12.0,
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
        daily = emptyList(),
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

    /** A day with a proper downpour in the middle of the afternoon. */
    private val wetAfternoon = forecast(
        (0..23).map { hour(it, if (it == 15) 4.0 else 0.0) },
    )

    /** And one where the rain was over before breakfast. */
    private val wetMorning = forecast(
        (0..23).map { hour(it, if (it == 5) 4.0 else 0.0) },
    )

    @Test
    fun `a storm this afternoon is flagged first thing in the morning`() {
        // The case that matters: somebody checks at seven, and the day holds a
        // storm at three. Getting this wrong sends them out without a coat.
        assertTrue(UmbrellaDay.isUmbrellaDay(wetAfternoon, emptyList(), at(7)))
    }

    @Test
    fun `the answer does not change as the day goes on`() {
        // The same forecast, asked at every hour. A mark that comes and goes is
        // telling somebody the day changed its mind, which is not something the
        // day did - and they may be across town and unable to act on it anyway.
        (0..23).forEach { hourOfDay ->
            assertTrue(
                "the mark went out at $hourOfDay:00",
                UmbrellaDay.isUmbrellaDay(wetAfternoon, emptyList(), at(hourOfDay)),
            )
        }
    }

    @Test
    fun `rain that finished this morning still counts for the rest of the day`() {
        // The behaviour this replaced: the mark used to run from now, so by noon
        // a wet morning had vanished from it. Whoever took a coat at six was
        // right, and the app should not later imply they were not.
        assertTrue(UmbrellaDay.isUmbrellaDay(wetMorning, emptyList(), at(14)))
        assertTrue(UmbrellaDay.isUmbrellaDay(wetMorning, emptyList(), at(23)))
    }

    @Test
    fun `a dry day never raises it`() {
        val dry = forecast((0..23).map { hour(it, 0.0) })
        (0..23).forEach {
            assertFalse(UmbrellaDay.isUmbrellaDay(dry, emptyList(), at(it)))
        }
    }

    @Test
    fun `drizzle all day is not worth carrying anything for`() {
        // The reason the bar is moderate rather than wet. A mark that is up on
        // every grey day in a maritime climate is furniture, and this app has
        // been there once already.
        val grey = forecast((0..23).map { hour(it, 0.3) })
        assertFalse(UmbrellaDay.isUmbrellaDay(grey, emptyList(), at(9)))
    }

    @Test
    fun `tomorrow's storm is tomorrow's problem`() {
        // The day boundary is the location's own midnight, so a downpour at one
        // in the morning belongs to the day it falls on and not to this one.
        val tonight = forecast(
            (0..30).map { hour(it, if (it == 26) 5.0 else 0.0) },
        )
        assertFalse(
            "tomorrow's rain raised today's mark",
            UmbrellaDay.isUmbrellaDay(tonight, emptyList(), at(9)),
        )
        assertTrue(
            "and it must raise tomorrow's",
            UmbrellaDay.isUmbrellaDay(tonight, emptyList(), at(26)),
        )
    }

    @Test
    fun `one burst of radar is not enough, two in a row is`() {
        // The projection steps every ten minutes and the model averages a whole
        // hour, so a single moderate step is a burst the model would have
        // smoothed away. Twenty minutes of it is a shower either way.
        val dry = forecast((0..23).map { hour(it, 0.0) })

        fun steps(count: Int) = (0 until count).map {
            FusedPrecipitation(
                at = at(9).plus(Duration.ofMinutes(10L * it)),
                millimetresPerHour = 4.0,
                confidence = 0.9,
                sources = 1,
                radarShare = 1.0,
            )
        }

        assertFalse("one burst raised the mark", UmbrellaDay.isUmbrellaDay(dry, steps(1), at(9)))
        assertTrue("a sustained shower did not", UmbrellaDay.isUmbrellaDay(dry, steps(2), at(9)))
    }
}
