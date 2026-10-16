// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.model

import java.time.Instant
import java.time.LocalDate
import lv.bolwarra.wetter.domain.provider.ForecastSupplement
import lv.bolwarra.wetter.domain.provider.ProviderMetadata

/**
 * Everything Wetter knows about one place at one moment.
 *
 * Units are canonical throughout the domain: degrees Celsius, millimetres, metres
 * per second, hectopascals, percent. Conversion to the reader's units happens
 * once, when drawn.
 */
data class WeatherForecast(
    val location: WeatherLocation,
    val current: CurrentWeather,
    /** Ascending by timestamp, contiguous, one entry per hour. */
    val hourly: List<HourlyWeather>,
    /** Ascending by date, starting with today in the location's zone. */
    val daily: List<DailyWeather>,
    /** When this forecast was retrieved: the basis for every "42 min old" line. */
    val fetchedAt: Instant,
    /**
     * Which source produced this, cached with it so an offline screen can still say
     * where its numbers came from (docs/providers.md).
     */
    val provider: ProviderMetadata,
    /**
     * The second source, when the hourly timeline had to be extended past where
     * [provider] stops being hourly. Null on an ordinary forecast.
     */
    val supplement: ForecastSupplement? = null,
)

data class CurrentWeather(
    val observedAt: Instant,
    /**
     * °C, or null when the provider did not say: a "0°" for an unknown temperature
     * would be worse than nothing.
     */
    val temperature: Double?,
    /** °C, what it feels like once wind and humidity are accounted for. */
    val apparentTemperature: Double?,
    val condition: WeatherCondition,
    /** Whether the sun is up. Drives the day/night treatment, not a sunrise lookup. */
    val isDay: Boolean,
    /** mm in the last hour. */
    val precipitation: Double?,
    /** m/s. */
    val windSpeed: Double?,
    /**
     * m/s, the strongest gust expected, or null when the provider does not say. The
     * gap between gust and mean is itself the information.
     */
    val windGust: Double?,
    /** Degrees clockwise from north, where the wind is coming from. */
    val windDirection: Int?,
    /** Percent. */
    val humidity: Int?,
    /** hPa, reduced to mean sea level. */
    val pressure: Double?,
) {

    /**
     * The condition as it should be shown, corrected as on every other surface: the
     * temperature decides what is falling, the measured rate how hard.
     */
    val appearance: WeatherCondition
        get() = condition.appropriateFor(temperature).atRate(precipitation)
}

/**
 * One hour of forecast: the row the precipitation timeline is built from, so it
 * carries the precipitation fields in full.
 */
data class HourlyWeather(
    /** The start of the hour this row describes. */
    val timestamp: Instant,
    /** °C, or null when the provider did not say. A gap in the curve, not a zero. */
    val temperature: Double?,
    /**
     * What the air feels like, wind and humidity included. Hourly as well as on the
     * snapshot, because "now" is resolved against this series.
     */
    val apparentTemperature: Double?,
    /** Percent, 0..100. */
    val precipitationProbability: Int?,
    /** mm expected in this hour: rain plus snow's liquid equivalent. */
    val precipitation: Double?,
    /** mm of rain in this hour. */
    val rain: Double?,
    /** cm of snow in this hour: snowfall is reported as depth. */
    val snowfall: Double?,
    val condition: WeatherCondition,
    /** m/s. */
    val windSpeed: Double?,
    /** m/s, the strongest gust expected in this hour. */
    val windGust: Double?,
    /** Percent, 0..100. */
    val cloudCover: Int?,
    /**
     * The sky's three decks, each a percentage: 99% thin cirrus is a hazy sun, 99%
     * low stratus is a lid. They do not sum to [cloudCover], since a deck seen through
     * a gap in the one below is counted by both. Low is below about 2 km, medium
     * 2-6 km, high above.
     */
    val cloudLow: Int?,
    val cloudMedium: Int?,
    val cloudHigh: Int?,
    /**
     * Clear-sky UV index for this hour: both sources quote a ceiling rather than a
     * reading, lower under overcast.
     */
    val uvIndex: Double?,
    /** Whether the sun is up during this hour, for the night wash on the timeline. */
    val isDay: Boolean,
) {
    /**
     * Each row covers one hour, so millimetres in the hour and millimetres per hour
     * are the same number.
     */
    val precipitationRate: Double? get() = precipitation

    val intensity: PrecipitationIntensity
        get() = PrecipitationIntensity.ofRate(precipitationRate)

    /**
     * What is falling, from the rain/snow breakdown where there is one and from the
     * condition where there is not. MET Norway gives no split, so only the fallback
     * is corrected by temperature; a measured split is taken as given.
     */
    val kind: PrecipitationKind
        get() {
            val wetRain = (rain ?: 0.0) >= PrecipitationIntensity.TRACE_MM_PER_HOUR
            val wetSnow = (snowfall ?: 0.0) > 0.0
            return when {
                wetRain && wetSnow -> PrecipitationKind.MIXED
                wetSnow -> PrecipitationKind.SNOW
                wetRain -> PrecipitationKind.RAIN
                !intensity.isWet -> PrecipitationKind.NONE
                else -> appearance.precipitationKind
            }
        }

    /**
     * The condition as it should be shown: the provider's word, renamed for what the
     * temperature says reaches the ground and for how hard this hour falls. Every
     * surface reads this rather than the raw symbol.
     */
    val appearance: WeatherCondition
        get() = condition.appropriateFor(temperature).atRate(precipitation)
}

data class DailyWeather(
    /** The calendar date in the location's own zone. */
    val date: LocalDate,
    /** °C. */
    val temperatureMin: Double,
    /** °C. */
    val temperatureMax: Double,
    val condition: WeatherCondition,
    /** mm over the whole day. */
    val precipitationTotal: Double?,
    /**
     * The strongest hourly rate in the day, mm/h, or null when the hours are not
     * known. A total says how much fell, a rate how hard; naming a day needs the
     * rate.
     */
    val precipitationPeakRate: Double? = null,
    /** Percent, the highest hourly probability of the day. */
    val precipitationProbabilityMax: Int?,
    /** How many hours of the day see precipitation at all. */
    val precipitationHours: Double?,
    /**
     * Sunrise and sunset belong to a day at a place; the timeline needs them for
     * every day it draws.
     */
    val sunrise: Instant?,
    val sunset: Instant?,
    /** m/s. */
    val windSpeedMax: Double?,
) {

    /**
     * The condition as it should be shown for the whole day, judged against the
     * day's maximum: the rename fires only when the whole day is below freezing.
     */
    val appearance: WeatherCondition
        get() = condition.appropriateFor(temperatureMax).atRate(precipitationPeakRate)
}
