package lv.bolwarra.wetter.data.provider

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import lv.bolwarra.wetter.data.provider.metnorway.MetNorwayProvider
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MET Norway's timeseries, checked against the domain model it should become.
 *
 * The fixture holds the shape that makes this provider harder than Open-Meteo:
 * three hourly steps followed by a six-hourly one, a step carrying `next_1_hours`
 * *and* `next_6_hours` at the same time, and no daily block at all.
 */
class MetNorwayProviderTest {

    private val fetchedAt = Instant.parse("2026-03-14T09:05:00Z")
    private val clock = Clock.fixed(fetchedAt, ZoneOffset.UTC)

    private fun provider(body: String = fixture("met-norway-riga.json")) =
        MetNorwayProvider(clientReturning(body), clock = clock)

    @Test
    fun `a response becomes a forecast`() = runTest {
        val forecast = provider().getForecast(riga).getOrThrow()

        assertEquals(MetNorwayProvider.ID, forecast.provider.id)
        assertEquals(MetNorwayProvider.ATTRIBUTION, forecast.provider.attribution)
        assertEquals(fetchedAt, forecast.fetchedAt)
    }

    @Test
    fun `the model run time is read from the response`() = runTest {
        val forecast = provider().getForecast(riga).getOrThrow()

        // The one provider that publishes when its run was generated, as opposed
        // to when Wetter happened to ask.
        assertEquals(
            Instant.parse("2026-03-14T08:31:52Z"),
            forecast.provider.forecastGeneratedAt,
        )
    }

    @Test
    fun `only steps with a one-hour window become hourly rows`() = runTest {
        val hourly = provider().getForecast(riga).getOrThrow().hourly

        // Three of the four steps carry next_1_hours. The fourth describes six
        // hours and would draw a bar six times too wide.
        assertEquals(3, hourly.size)
        assertEquals(Instant.parse("2026-03-14T09:00:00Z"), hourly.first().timestamp)
        assertEquals(Instant.parse("2026-03-14T11:00:00Z"), hourly.last().timestamp)
    }

    @Test
    fun `symbol codes become conditions`() = runTest {
        val hourly = provider().getForecast(riga).getOrThrow().hourly

        assertEquals(WeatherCondition.OVERCAST, hourly[0].condition)
        assertEquals(WeatherCondition.RAIN, hourly[1].condition)
        assertEquals(WeatherCondition.RAIN_SHOWERS, hourly[2].condition)
    }

    @Test
    fun `probabilities are rounded to whole percent`() = runTest {
        val hourly = provider().getForecast(riga).getOrThrow().hourly

        assertEquals(10, hourly[0].precipitationProbability)
        assertEquals(55, hourly[1].precipitationProbability)
        assertEquals(92, hourly[2].precipitationProbability)
    }

    @Test
    fun `precipitation is left undivided between rain and snow`() = runTest {
        val hour = provider().getForecast(riga).getOrThrow().hourly[1]

        assertEquals(1.8, hour.precipitation!!, 1e-9)
        // MET Norway publishes one liquid-equivalent figure. Splitting it would
        // mean guessing, and a guess in this field would be drawn as fact.
        assertNull(hour.rain)
        assertNull(hour.snowfall)
    }

    @Test
    fun `a day totals each step once, from its finest window`() = runTest {
        val today = provider().getForecast(riga).getOrThrow().daily
            .single { it.date == LocalDate.of(2026, 3, 14) }

        // The first step has both next_1_hours (0.0) and next_6_hours (8.7).
        // Only the one-hour figure counts, or the same rain is added twice.
        assertEquals(0.0 + 1.8 + 6.9, today.precipitationTotal!!, 1e-9)
    }

    @Test
    fun `a six-hourly step still contributes to its day`() = runTest {
        val tomorrow = provider().getForecast(riga).getOrThrow().daily
            .single { it.date == LocalDate.of(2026, 3, 15) }

        // Only next_6_hours is available for that step, so it is what counts —
        // not the wider next_12_hours window that overlaps the following day.
        assertEquals(1.2, tomorrow.precipitationTotal!!, 1e-9)
    }

    @Test
    fun `wet hours are counted only where the day is covered hour by hour`() = runTest {
        val daily = provider().getForecast(riga).getOrThrow().daily
        val today = daily.single { it.date == LocalDate.of(2026, 3, 14) }
        val tomorrow = daily.single { it.date == LocalDate.of(2026, 3, 15) }

        assertEquals(2.0, today.precipitationHours!!, 1e-9)
        assertNull(
            "six-hourly steps cannot say how many hours were wet",
            tomorrow.precipitationHours,
        )
    }

    @Test
    fun `the daily condition is the wettest part of the day`() = runTest {
        val today = provider().getForecast(riga).getOrThrow().daily
            .single { it.date == LocalDate.of(2026, 3, 14) }

        // A mostly cloudy day with one heavy shower in it is a day you take a
        // coat, and the daily row is the only place that can say so.
        assertEquals(WeatherCondition.RAIN_SHOWERS, today.condition)
    }

    @Test
    fun `a day covered hour by hour is named by its hours`() = runTest {
        // The bug this exists for was visible on screen: a Saturday headed with a
        // rain mark whose twenty-four hours were, every one of them, dry. The
        // six-hourly step sitting over the top of that day said rain, and it was
        // reading a real 8.7 mm - but a reader who opens the day sees the hours,
        // and the hours are what the day has to be named from.
        val day = provider(DRY_HOURS_UNDER_A_WET_SIX).getForecast(riga).getOrThrow().daily
            .single { it.date == LocalDate.of(2026, 3, 14) }

        assertEquals(WeatherCondition.PARTLY_CLOUDY, day.condition)
    }

    @Test
    fun `past the hourly reach, a six-hourly total is judged as a rate`() = runTest {
        // No hourly steps here, so a six-hourly one may name the day - but only
        // on the same terms. 0.4 mm is over the trace threshold as a number and
        // is a fifteenth of it as a rate, and the threshold is a rate.
        val day = provider(sixHourlyDay(0.4)).getForecast(riga).getOrThrow().daily
            .single { it.date == LocalDate.of(2026, 3, 14) }

        assertEquals(WeatherCondition.PARTLY_CLOUDY, day.condition)
        // The rain is still real and still counted. It is only too thin to name
        // the day after.
        assertEquals(0.4, day.precipitationTotal!!, 1e-9)
    }

    @Test
    fun `past the hourly reach, six hours of real rain still names its day`() = runTest {
        // The other side of the same threshold, so the fix cannot be "ignore
        // six-hourly steps". 4.2 mm over six hours is 0.7 an hour, which is rain
        // by any reading and worth putting on the day.
        val day = provider(sixHourlyDay(4.2)).getForecast(riga).getOrThrow().daily
            .single { it.date == LocalDate.of(2026, 3, 14) }

        assertEquals(WeatherCondition.RAIN, day.condition)
    }

    @Test
    fun `sunrise and sunset are computed because the provider omits them`() = runTest {
        val today = provider().getForecast(riga).getOrThrow().daily
            .single { it.date == LocalDate.of(2026, 3, 14) }

        assertNotNull(today.sunrise)
        assertNotNull(today.sunset)
        assertTrue(today.sunrise!!.isBefore(today.sunset!!))
    }

    @Test
    fun `daylight is derived from the sun rather than from the symbol suffix`() = runTest {
        val hourly = provider().getForecast(riga).getOrThrow().hourly

        // 09:00 UTC is 11:00 in Riga in March: daylight, even though the symbol
        // for that hour is a bare "cloudy" with no day or night suffix.
        assertTrue(hourly.first().isDay)
    }

    @Test
    fun `an empty timeseries produces an empty forecast rather than a crash`() = runTest {
        val empty = """{"properties":{"meta":{},"timeseries":[]}}"""
        val forecast = provider(empty).getForecast(riga).getOrThrow()

        assertTrue(forecast.hourly.isEmpty())
        assertTrue(forecast.daily.isEmpty())
        assertFalse(forecast.current.condition.isPrecipitating)
    }
}

/**
 * A day whose hours are all dry, with a wet six-hourly step laid over the top.
 *
 * MET Norway really does publish both for the same moment, and this is the shape
 * that put a rain mark on a dry Saturday. Written out here rather than added to
 * the shared fixture: everything else reads that one, and this is a shape that
 * has to stay exactly as it is.
 */
private val DRY_HOURS_UNDER_A_WET_SIX = """
{
  "type": "Feature",
  "properties": {
    "meta": { "updated_at": "2026-03-14T08:31:52Z" },
    "timeseries": [
      {
        "time": "2026-03-14T10:00:00Z",
        "data": {
          "instant": { "details": { "air_temperature": 6.0 } },
          "next_1_hours": {
            "summary": { "symbol_code": "partlycloudy_day" },
            "details": { "precipitation_amount": 0.0 }
          },
          "next_6_hours": {
            "summary": { "symbol_code": "rain" },
            "details": { "precipitation_amount": 8.7 }
          }
        }
      },
      {
        "time": "2026-03-14T11:00:00Z",
        "data": {
          "instant": { "details": { "air_temperature": 6.4 } },
          "next_1_hours": {
            "summary": { "symbol_code": "cloudy" },
            "details": { "precipitation_amount": 0.0 }
          }
        }
      }
    ]
  }
}
"""

/**
 * A day past the hourly forecast's reach: two six-hourly steps and nothing finer.
 *
 * The clear one sits nearest midday, so it is what the day falls back to when the
 * wet one is too thin to count - which is the thing being measured.
 */
private fun sixHourlyDay(millimetres: Double) = """
{
  "type": "Feature",
  "properties": {
    "meta": { "updated_at": "2026-03-14T08:31:52Z" },
    "timeseries": [
      {
        "time": "2026-03-14T06:00:00Z",
        "data": {
          "instant": { "details": { "air_temperature": 4.0 } },
          "next_6_hours": {
            "summary": { "symbol_code": "rain" },
            "details": { "precipitation_amount": $millimetres }
          }
        }
      },
      {
        "time": "2026-03-14T12:00:00Z",
        "data": {
          "instant": { "details": { "air_temperature": 7.0 } },
          "next_6_hours": {
            "summary": { "symbol_code": "partlycloudy_day" },
            "details": { "precipitation_amount": 0.0 }
          }
        }
      }
    ]
  }
}
"""
