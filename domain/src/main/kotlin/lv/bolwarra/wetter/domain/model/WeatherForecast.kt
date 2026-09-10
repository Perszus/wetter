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
    /**
     * m/s, the strongest gust expected. Null when the provider does not say.
     *
     * Carried separately from [windSpeed] because the difference between them is
     * the information: the mean is what an anemometer averages over an hour, the
     * gust is what actually takes your hat off, and a place where they are far
     * apart feels nothing like one where they are close.
     */
    val windGust: Double?,
    /** degrees clockwise from north, where the wind is coming from */
    val windDirection: Int?,
    /** percent */
    val humidity: Int?,
    /** hPa, reduced to mean sea level */
    val pressure: Double?,
) {

    /**
     * The condition as it should be shown, corrected the same way every other
     * surface's is: the temperature decides what is falling, the measured rate
     * decides how hard.
     *
     * This is the most prominent word in the app and it was the last one still
     * reading a provider's symbol raw. `conditionsAt` only reaches for an hourly
     * row once the observation has been overtaken, so in the ordinary case - a
     * fresh observation, which is most of the time - the dial showed the
     * unreconciled word while the chart beneath it was drawn from the rate.
     */
    val appearance: WeatherCondition
        get() = condition.appropriateFor(temperature).atRate(precipitation)
}

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
    /**
     * What the air feels like, wind and humidity included.
     *
     * On the hourly row and not only on the current snapshot, because "now"
     * is resolved against this series: the snapshot goes stale inside the hour
     * and every other reading on the screen moves on without it. Both
     * providers publish this hour by hour, so it is not a regional luxury.
     */
    val apparentTemperature: Double?,
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
    /** m/s, the strongest gust expected in this hour. */
    val windGust: Double?,
    /** percent, 0..100 */
    val cloudCover: Int?,
    /**
     * The sky broken into its three decks, each a percentage.
     *
     * The total alone cannot tell a bright day from a grey one: 99% of thin
     * cirrus is a hazy sun, 99% of low stratus is a lid. They do not sum to
     * [cloudCover] and are not meant to - a deck seen through a gap in the one
     * below it is counted by both, which is why the total is not their sum.
     *
     * Roughly: low is below 2 km, medium 2-6 km, high above.
     */
    val cloudLow: Int?,
    val cloudMedium: Int?,
    val cloudHigh: Int?,
    /**
     * Clear-sky UV index for this hour.
     *
     * Both sources quote it for a clear sky, which makes it a ceiling rather
     * than a reading: under overcast the real figure is lower. The ceiling is
     * the part that decides whether anybody needs to think about it.
     */
    val uvIndex: Double?,
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
     *
     * A measured split is taken as given: a provider that troubled to separate
     * rain from snow has said something this hour's temperature cannot improve
     * on. Only the fallback is corrected, because that is where a condition
     * code can say "rain" over a temperature of minus four.
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
     * The condition as it should be shown.
     *
     * The provider's word, named for what the temperature says would actually
     * reach the ground and for how hard this hour is measured to be falling.
     * Every surface that draws a condition reads this rather than the raw
     * symbol, so there is one answer to appeal to.
     */
    val appearance: WeatherCondition
        get() = condition.appropriateFor(temperature).atRate(precipitation)
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
    /**
     * The strongest hourly rate anywhere in the day, in mm/h, or null when the
     * hours are not known.
     *
     * Kept beside the total because they answer different questions and have
     * been confused before: a total says how much fell, a rate says how hard.
     * Naming a day needs the rate - four millimetres spread evenly over
     * twenty-four hours is a drizzly day, and the same four in one hour is a
     * downpour - and the total cannot tell those apart.
     */
    val precipitationPeakRate: Double? = null,
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
) {

    /**
     * The condition as it should be shown for the whole day.
     *
     * Measured against the day's *maximum*, which is the conservative reading
     * and the only one that is safe over a span this long. An hour can be named
     * from the temperature at that hour; a day cannot, because it contains both
     * ends of its own range and nothing here says which end the precipitation
     * fell at. Taking the maximum means the rename only fires when the entire
     * day is below freezing — where "rain" is wrong at every hour of it — and
     * never on the strength of a cold night that the shower missed.
     */
    val appearance: WeatherCondition
        get() = condition.appropriateFor(temperatureMax).atRate(precipitationPeakRate)
}
