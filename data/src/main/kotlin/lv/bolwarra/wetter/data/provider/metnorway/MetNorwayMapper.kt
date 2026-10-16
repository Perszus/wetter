// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * MET Norway's timeseries, turned into Wetter's models. MET Norway publishes one
 * stream of instants and leaves the rest to the client:
 *
 * 1. The hourly forecast is the prefix of the series that carries `next_1_hours`.
 *     Beyond about two and a half days the series drops to six-hourly steps, which
 *     are not hours.
 * 2. The daily forecast is aggregated, each step contributing its precipitation
 *     once, from the finest window it has.
 * 3. Daylight is computed by [SolarTime]: MET Norway publishes no sunrise, no
 *     sunset and only a partial day/night hint.
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
            // Published on the complete endpoint.
            apparentTemperature = details?.apparentTemperature,
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
     * Only the steps that carry a one-hour forecast become hourly rows: the timeline
     * draws one bar per row.
     */
    private fun toHourly(steps: List<ParsedStep>, location: WeatherLocation): List<HourlyWeather> =
        steps.mapNotNull { parsed ->
            val nextHour = parsed.step.data.next1Hours ?: return@mapNotNull null
            val details = parsed.step.data.instant.details

            HourlyWeather(
                timestamp = parsed.at,
                temperature = details.airTemperature,
                apparentTemperature = details.apparentTemperature,
                precipitationProbability = nextHour.details.probabilityOfPrecipitation
                    ?.roundToInt()
                    ?.coerceIn(0, 100),
                precipitation = nextHour.details.precipitationAmount,
                // MET Norway reports one liquid-equivalent figure. No rain/snow split is guessed;
                // the condition carries that information.
                rain = null,
                snowfall = null,
                condition = weatherConditionFromMetSymbol(nextHour.summary.symbolCode),
                windSpeed = details.windSpeed,
                windGust = details.windGust,
                uvIndex = details.uvIndex,
                cloudCover = details.cloudAreaFraction?.roundToInt()?.coerceIn(0, 100),
                cloudLow = details.cloudLow?.roundToInt()?.coerceIn(0, 100),
                cloudMedium = details.cloudMedium?.roundToInt()?.coerceIn(0, 100),
                cloudHigh = details.cloudHigh?.roundToInt()?.coerceIn(0, 100),
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
            // atZone().toLocalDate() rather than LocalDate.ofInstant(), a Java 9 API absent
            // at minSdk 26.
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
        // A day represented by a single six-hourly step has no meaningful range.
        if (temperatures.isEmpty()) return null

        // Each step contributes once, from the finest window it publishes, so the same
        // rain is never counted twice.
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
            // The hardest hour in the day, which names it: a six-hourly block contributes its
            // rate, not its total.
            precipitationPeakRate = steps.mapNotNull { it.precipitationRate() }.maxOrNull(),
            precipitationProbabilityMax = probabilities.maxOrNull()?.roundToInt()?.coerceIn(0, 100),
            // Counted only where the day is covered hour by hour; from six-hourly totals it
            // would be a guess.
            precipitationHours = if (hourlySteps.isEmpty()) {
                null
            } else {
                hourlySteps.count { step ->
                    val mm = step.step.data.next1Hours?.details?.precipitationAmount ?: 0.0
                    // Hours worth calling wet. A one-hour window's amount is numerically its rate,
                    // so this is the bar the rest of the app names rain at.
                    PrecipitationIntensity.ofRate(mm).isWorthNaming
                }.toDouble()
            },
            sunrise = solar.sunrise,
            sunset = solar.sunset,
            windSpeedMax = steps.mapNotNull { it.step.data.instant.details.windSpeed }.maxOrNull(),
        )
    }

    /**
     * The condition worth showing beside a date: the wettest step if anything falls
     * that day, otherwise whatever the sky is doing around midday.
     *
     * Steps are compared by rate, not amount, against a threshold in the same unit. A
     * day covered hour by hour is named only from those hours, so its word cannot
     * contradict hours the reader can open and check (as in ObservedSpell); the
     * six-hourly steps still count towards its total. Past the hourly reach, the
     * six-hourly steps name the day.
     */
    private fun dominantCondition(steps: List<ParsedStep>, zone: ZoneId): WeatherCondition {
        val candidates = steps
            .filter { it.step.data.next1Hours != null }
            .ifEmpty { steps }

        // Wet enough to name the day after. Below this the day is described by its sky,
        // which is what a cloudy afternoon with a tenth of a millimetre in it is.
        val wettest = candidates
            .filter { PrecipitationIntensity.ofRate(it.precipitationRate()).isWorthNaming }
            .maxByOrNull { it.precipitationRate() ?: 0.0 }

        val chosen = wettest ?: candidates.minByOrNull { step ->
            val hour = step.at.atZone(zone).hour
            kotlin.math.abs(hour - MIDDAY)
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

        /** How many hours the window chosen by [finestPeriod] covers. */
        fun finestPeriodHours(): Int = when {
            step.data.next1Hours != null -> 1
            step.data.next6Hours != null -> SIX
            else -> TWELVE
        }

        /** What this step adds to a day's total: an accumulation, in millimetres. */
        fun precipitationContribution(): Double? = finestPeriod()?.details?.precipitationAmount

        /**
         * The same rain as a rate, the only form comparable to a threshold in mm/h. Flat
         * across the window, so it is a floor on the worst moment, never an overstatement.
         */
        fun precipitationRate(): Double? = precipitationContribution()?.div(finestPeriodHours())
    }

    private const val SIX = 6
    private const val TWELVE = 12

    /** The hour a day is judged by when nothing in it stands out. */
    private const val MIDDAY = 12
}
