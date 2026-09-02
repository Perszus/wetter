package lv.bolwarra.wetter.domain.model

import java.time.Instant
import java.time.LocalDate
import lv.bolwarra.wetter.domain.provider.ForecastSupplement
import lv.bolwarra.wetter.domain.provider.ProviderMetadata

/**
 * Everything Wetter knows about one place at one moment.
 *
 * Units are fixed and canonical throughout the domain — degrees Celsius,
 * millimetres, metres per second, hectopascals, percent. Conversion to whatever
 * the user asked for happens once, at the point of rendering. Carrying a unit
 * around with every number, or storing user-facing units, means every
 * calculation has to ask what it is holding.
 */
data class WeatherForecast(
    val location: WeatherLocation,
    val current: CurrentWeather,
    /** Ascending by timestamp, contiguous, one entry per hour. */
    val hourly: List<HourlyWeather>,
    /** Ascending by date, starting with today in the location's zone. */
    val daily: List<DailyWeather>,
    /** When this forecast was retrieved — the basis for every "42 min old" line. */
    val fetchedAt: Instant,
    /**
     * Which source produced this. Travels with the forecast and is cached with
     * it, so an offline screen can still say where its numbers came from
     * (docs/providers.md).
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
     * °C, or null when the provider did not say.
     *
     * Nullable because the alternative was a zero, and a screen showing "0°"
     * for a temperature nobody knows is worse than one showing nothing. This is
     * the number set largest on the screen; it has to be true.
     */
    val temperature: Double?,
    /** °C, what it feels like once wind and humidity are accounted for. */
    val apparentTemperature: Double?,
    val condition: WeatherCondition,
    /** Whether the sun is up. Drives the day/night treatment, not a sunrise lookup. */
    val isDay: Boolean,
    /** mm in the last hour */
    val precipitation: Double?,
    /** m/s */
    val windSpeed: Double?,
    /** degrees clockwise from north, where the wind is coming from */
    val windDirection: Int?,
    /** percent */
    val humidity: Int?,
    /** hPa, reduced to mean sea level */
    val pressure: Double?,
)

/**
 * One hour of forecast. This is the row the whole precipitation timeline is built
 * from, so it carries the precipitation fields in full and everything else only
 * as far as the timeline and the detail rows actually need.
 */
data class HourlyWeather(
    /** The start of the hour this row describes. */
    val timestamp: Instant,
    /** °C, or null when the provider did not say. A gap in the curve, not a zero. */
    val temperature: Double?,
    /** percent, 0..100 */
    val precipitationProbability: Int?,
    /** mm expected in this hour — rain plus snow's liquid equivalent. */
    val precipitation: Double?,
    /** mm of rain in this hour */
    val rain: Double?,
    /** cm of snow in this hour. Note the unit: snowfall is reported as depth. */
    val snowfall: Double?,
    val condition: WeatherCondition,
    /** m/s */
    val windSpeed: Double?,
    /** percent, 0..100 */
    val cloudCover: Int?,
    /** Whether the sun is up during this hour, for the night wash on the timeline. */
    val isDay: Boolean,
) {
    /**
     * Since each row covers exactly one hour, millimetres in the hour and
     * millimetres per hour are the same number. Named so the call sites that feed
     * [PrecipitationIntensity.ofRate] read honestly.
     */
    val precipitationRate: Double? get() = precipitation

    val intensity: PrecipitationIntensity
        get() = PrecipitationIntensity.ofRate(precipitationRate)

    /**
     * What is falling, from the breakdown where there is one and from the
     * condition where there is not.
     *
     * The fallback matters more than it looks. MET Norway reports a single
     * liquid-equivalent figure with no rain/snow split, so a naive "wet means
     * rain" would paint a January blizzard in the rain colour for every user in
     * the Nordics — the exact region that provider is chosen for.
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
                else -> condition.precipitationKind
            }
        }
}

data class DailyWeather(
    /** The calendar date in the location's own zone. */
    val date: LocalDate,
    /** °C */
    val temperatureMin: Double,
    /** °C */
    val temperatureMax: Double,
    val condition: WeatherCondition,
    /** mm over the whole day */
    val precipitationTotal: Double?,
    /** percent, the highest hourly probability of the day */
    val precipitationProbabilityMax: Int?,
    /** How many hours of the day see precipitation at all. */
    val precipitationHours: Double?,
    /**
     * docs/design-principles.md puts sunrise and sunset on the forecast. They belong here:
     * they are properties of a day at a place, and the timeline needs them for
     * every day it draws, not only for today.
     */
    val sunrise: Instant?,
    val sunset: Instant?,
    /** m/s */
    val windSpeedMax: Double?,
)
