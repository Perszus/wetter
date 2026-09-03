package lv.bolwarra.wetter.data.provider.metnorway

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt
import lv.bolwarra.wetter.domain.SolarTime
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata

/**
 * MET Norway's timeseries, turned into Wetter's models.
 *
 * More work than Open-Meteo's mapper, because MET Norway publishes one stream of
 * instants and leaves the rest to the client. Three things happen here:
 *
 *  1. The hourly forecast is the prefix of the series that carries `next_1_hours`.
 *     Beyond about two and a half days the series drops to six-hourly, and those
 *     steps are not hours — presenting them as hours would draw a rain bar six
 *     times too wide.
 *  2. The daily forecast is aggregated. Each step contributes its precipitation
 *     once, from the finest window it has, so the six-hourly tail still counts
 *     towards a day's total without being double counted.
 *  3. Daylight is computed, not read. MET Norway publishes no sunrise, no sunset
 *     and only a partial day/night hint, so [SolarTime] supplies all three.
 */
internal object MetNorwayMapper {

    fun toForecast(
        response: MetNorwayResponse,
        location: WeatherLocation,
        fetchedAt: Instant,
        metadata: ProviderMetadata,
    ): WeatherForecast {
        val steps = response.properties.timeseries.mapNotNull { step ->
            step.time.toInstantOrNull()?.let { ParsedStep(it, step) }
        }.sortedBy { it.at }

        val zone = location.zone
        val hourly = toHourly(steps, location)

        return WeatherForecast(
            location = location,
            current = toCurrent(steps.firstOrNull(), location, hourly, fetchedAt),
            hourly = hourly,
            daily = toDaily(steps, location, zone),
            fetchedAt = fetchedAt,
            provider = metadata,
        )
    }

    private fun toCurrent(
        first: ParsedStep?,
        location: WeatherLocation,
        hourly: List<HourlyWeather>,
        fetchedAt: Instant,
    ): CurrentWeather {
        val details = first?.step?.data?.instant?.details
        val nextHour = first?.step?.data?.next1Hours
        val at = first?.at ?: fetchedAt

        return CurrentWeather(
            observedAt = at,
            temperature = details?.airTemperature ?: hourly.firstOrNull()?.temperature,
            // MET Norway does not publish an apparent temperature. Inventing one
            // from wind and humidity would be a different quantity wearing the
            // same label, so the field stays empty and the row is not drawn.
            apparentTemperature = null,
            condition = weatherConditionFromMetSymbol(nextHour?.summary?.symbolCode)
                .ifUnknown { hourly.firstOrNull()?.condition ?: WeatherCondition.UNKNOWN },
            isDay = SolarTime.isDaylight(at, location.latitude, location.longitude),
            precipitation = nextHour?.details?.precipitationAmount,
            windSpeed = details?.windSpeed,
            windGust = details?.windGust,
            windDirection = details?.windFromDirection?.roundToInt(),
            humidity = details?.humidity?.roundToInt(),
            pressure = details?.pressure,
        )
    }

    /**
     * Only the steps that carry a one-hour forecast become hourly rows. A step
     * with just `next_6_hours` describes six hours, and the timeline draws one
     * bar per row.
     */
    private fun toHourly(steps: List<ParsedStep>, location: WeatherLocation): List<HourlyWeather> =
        steps.mapNotNull { parsed ->
            val nextHour = parsed.step.data.next1Hours ?: return@mapNotNull null
            val details = parsed.step.data.instant.details

            HourlyWeather(
                timestamp = parsed.at,
                temperature = details.airTemperature,
                precipitationProbability = nextHour.details.probabilityOfPrecipitation
                    ?.roundToInt()
                    ?.coerceIn(0, 100),
                precipitation = nextHour.details.precipitationAmount,
                // MET Norway reports one precipitation figure, as liquid
                // equivalent. Splitting it into rain and snow would mean deciding
                // from a symbol how much of a millimetre was frozen, so the split
                // is left absent and the condition carries that information.
                rain = null,
                snowfall = null,
                condition = weatherConditionFromMetSymbol(nextHour.summary.symbolCode),
                windSpeed = details.windSpeed,
                windGust = details.windGust,
                cloudCover = details.cloudAreaFraction?.roundToInt()?.coerceIn(0, 100),
                isDay = SolarTime.isDaylight(parsed.at, location.latitude, location.longitude),
            )
        }

    private fun toDaily(
        steps: List<ParsedStep>,
        location: WeatherLocation,
        zone: ZoneId,
    ): List<DailyWeather> {
        if (steps.isEmpty()) return emptyList()

        return steps
            // atZone().toLocalDate() rather than LocalDate.ofInstant(): the latter
            // is a Java 9 API, and Android's java.time at minSdk 26 is Java 8.
            .groupBy { it.at.atZone(zone).toLocalDate() }
            .toSortedMap()
            .mapNotNull { (date, daySteps) -> toDay(date, daySteps, location, zone) }
    }

    private fun toDay(
        date: LocalDate,
        steps: List<ParsedStep>,
        location: WeatherLocation,
        zone: ZoneId,
    ): DailyWeather? {
        val temperatures = steps.mapNotNull { it.step.data.instant.details.airTemperature }
        // A day represented by a single six-hourly step has no meaningful range,
        // and a row showing the same number twice is worse than no row.
        if (temperatures.isEmpty()) return null

        // Each step contributes precipitation once, from the finest window it
        // publishes. Hourly steps are an hour apart and six-hourly steps six, so
        // taking the finest available never double counts the same rain.
        val contributions = steps.map { it.precipitationContribution() }
        val total = contributions.mapNotNull { it }.takeIf { it.isNotEmpty() }?.sum()

        val hourlySteps = steps.filter { it.step.data.next1Hours != null }
        val probabilities = steps.mapNotNull {
            it.finestPeriod()?.details?.probabilityOfPrecipitation
        }

        val solar = SolarTime.sunriseSunset(date, location.latitude, location.longitude, zone)

        return DailyWeather(
            date = date,
            temperatureMin = temperatures.min(),
            temperatureMax = temperatures.max(),
            condition = dominantCondition(steps, zone),
            precipitationTotal = total,
            precipitationProbabilityMax = probabilities.maxOrNull()?.roundToInt()?.coerceIn(0, 100),
            // Counted only where the day is covered hour by hour. Estimating it
            // from six-hourly totals would be a guess presented as a measurement.
            precipitationHours = if (hourlySteps.isEmpty()) {
                null
            } else {
                hourlySteps.count { step ->
                    val mm = step.step.data.next1Hours?.details?.precipitationAmount ?: 0.0
                    mm >= PrecipitationIntensity.TRACE_MM_PER_HOUR
                }.toDouble()
            },
            sunrise = solar.sunrise,
            sunset = solar.sunset,
            windSpeedMax = steps.mapNotNull { it.step.data.instant.details.windSpeed }.maxOrNull(),
        )
    }

    /**
     * The condition worth showing beside a date: the wettest step if anything
     * falls that day, otherwise whatever the sky is doing around midday.
     *
     * A day of sun with one heavy shower in it is a day you take a coat, and the
     * daily row is the only place that can say so.
     */
    private fun dominantCondition(steps: List<ParsedStep>, zone: ZoneId): WeatherCondition {
        val wettest = steps
            .filter {
                (it.precipitationContribution() ?: 0.0) >=
                    PrecipitationIntensity.TRACE_MM_PER_HOUR
            }
            .maxByOrNull { it.precipitationContribution() ?: 0.0 }

        val chosen = wettest ?: steps.minByOrNull { step ->
            val hour = step.at.atZone(zone).hour
            kotlin.math.abs(hour - 12)
        }

        return weatherConditionFromMetSymbol(chosen?.finestPeriod()?.summary?.symbolCode)
    }

    private fun String.toInstantOrNull(): Instant? = try {
        Instant.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun WeatherCondition.ifUnknown(fallback: () -> WeatherCondition): WeatherCondition =
        if (this == WeatherCondition.UNKNOWN) fallback() else this

    private class ParsedStep(val at: Instant, val step: MetNorwayTimeStep) {

        /** The shortest forecast window this step publishes, or null if it has none. */
        fun finestPeriod(): MetNorwayPeriod? =
            step.data.next1Hours ?: step.data.next6Hours ?: step.data.next12Hours

        fun precipitationContribution(): Double? = finestPeriod()?.details?.precipitationAmount
    }
}
