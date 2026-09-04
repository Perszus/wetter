package lv.bolwarra.wetter.ui.preview

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata

/**
 * A fixed forecast for @Preview and for tests that need something shaped like
 * real data.
 *
 * It is deliberately awkward: a shower that builds, peaks and stops, a null
 * probability in the middle, an overnight stretch, and a day whose minimum is
 * below zero. Sample data that is too tidy hides exactly the layout problems
 * previews exist to catch.
 *
 * Every instant is derived from one fixed epoch, so previews are reproducible and
 * two runs never differ.
 */
object SampleWeather {

    val zone: ZoneId = ZoneId.of("Europe/Riga")

    /** 2026-03-14T09:00Z — a Saturday morning, chosen once and never moved. */
    val now: Instant = Instant.parse("2026-03-14T09:00:00Z")

    val location = WeatherLocation(
        name = "Rīga",
        latitude = 56.9496,
        longitude = 24.1052,
        zone = zone,
        country = "Latvia",
    )

    val current = CurrentWeather(
        observedAt = now,
        temperature = 4.2,
        apparentTemperature = 0.8,
        condition = WeatherCondition.OVERCAST,
        isDay = true,
        precipitation = 0.0,
        windSpeed = 5.4,
        windGust = 9.8,
        windDirection = 245,
        humidity = 81,
        pressure = 1004.2,
    )

    /** mm per hour for the next 24 hours: dry, a shower, dry again, then drizzle. */
    private val precipitationSeries = listOf(
        0.0, 0.0, 0.1, 0.6, 1.8, 4.1, 6.9, 3.2,
        0.9, 0.2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.3, 0.4, 0.2, 0.0, 0.0, 0.0, 0.0,
    )

    private val temperatureSeries = listOf(
        4.2, 4.6, 5.1, 5.4, 5.2, 4.8, 4.3, 4.0,
        3.6, 3.1, 2.7, 2.2, 1.8, 1.5, 1.2, 0.9,
        0.6, 0.4, 0.3, 0.5, 0.9, 1.6, 2.4, 3.3,
    )

    val hourly: List<HourlyWeather> = precipitationSeries.indices.map { index ->
        val mm = precipitationSeries[index]
        val hourStart = now.plus(Duration.ofHours(index.toLong()))
        // Local hours 07:00-19:00 count as daylight in mid-March at this latitude.
        val localHour = hourStart.atZone(zone).hour
        HourlyWeather(
            timestamp = hourStart,
            temperature = temperatureSeries[index],
            // A gap at index 12: providers do return nulls, and the timeline has
            // to survive one without leaving a hole.
            precipitationProbability = if (index == 12) null else (mm * 22).toInt().coerceIn(0, 96),
            // A degree or so under the air temperature, as damp March wind is.
            apparentTemperature = temperatureSeries[index] - 1.2,
            precipitation = mm,
            rain = mm,
            snowfall = 0.0,
            condition = when {
                mm >= 2.5 -> WeatherCondition.RAIN
                mm >= 0.1 -> WeatherCondition.DRIZZLE
                else -> WeatherCondition.OVERCAST
            },
            windSpeed = 4.0 + (index % 5),
            windGust = 7.5 + (index % 5) * 1.4,
            // A March arc: nothing before dawn, a low peak at noon.
            uvIndex = if (localHour in 8..17) {
                (3.0 - kotlin.math.abs(localHour - 13) * 0.5).coerceAtLeast(0.0)
            } else {
                0.0
            },
            cloudCover = 70 + (index % 4) * 8,
            isDay = localHour in 7..18,
        )
    }

    val daily: List<DailyWeather> = List(7) { offset ->
        val date = LocalDate.of(2026, 3, 14).plusDays(offset.toLong())
        DailyWeather(
            date = date,
            temperatureMin = -2.4 + offset * 0.8,
            temperatureMax = 5.6 + offset * 1.1,
            condition = if (offset < 2) WeatherCondition.RAIN else WeatherCondition.PARTLY_CLOUDY,
            precipitationTotal = if (offset < 2) 12.4 - offset * 5 else 0.0,
            precipitationProbabilityMax = if (offset < 2) 92 - offset * 30 else 8,
            precipitationHours = if (offset < 2) 8.0 - offset * 3 else 0.0,
            sunrise = date.atTime(6, 41).atZone(zone).toInstant(),
            sunset = date.atTime(18, 52).atZone(zone).toInstant(),
            windSpeedMax = 7.5 + offset,
        )
    }

    val provider = ProviderMetadata(
        id = "met-norway",
        name = "MET Norway",
        model = "Nordic",
        resolutionKm = 2.5,
        forecastGeneratedAt = now.minus(Duration.ofMinutes(38)),
        attribution = "Data from MET Norway, licensed CC BY 4.0",
    )

    val forecast = WeatherForecast(
        location = location,
        current = current,
        hourly = hourly,
        daily = daily,
        fetchedAt = now,
        provider = provider,
    )
}
