package lv.bolwarra.wetter.data.db

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ForecastSupplement
import lv.bolwarra.wetter.domain.provider.ProviderMetadata

/**
 * A forecast as it is written to disk.
 *
 * A mirror of the domain types rather than the domain types themselves, so that
 * `:domain` stays free of serialization annotations and a stored forecast cannot
 * be silently invalidated by an unrelated refactor. Instants are epoch seconds
 * and dates are ISO strings, which are stable in a way that a library's default
 * encoding of `java.time` is not.
 *
 * The whole thing is stored as one JSON column rather than normalised into
 * tables of hours and days. Nothing queries an individual hour — a forecast is
 * read whole and written whole — so tables would buy nothing and cost a
 * migration every time a domain field appears. And because this is a *cache*,
 * a payload that no longer parses after an app update is simply a miss: it costs
 * one network request, not somebody's data. That is the trade, and it is only
 * acceptable because nothing here is irreplaceable.
 */
@Serializable
internal data class StoredForecast(
    val location: StoredLocation,
    val current: StoredCurrent,
    val hourly: List<StoredHour>,
    val daily: List<StoredDay>,
    val fetchedAtEpochSecond: Long,
    val provider: StoredProvider,
    val supplement: StoredSupplement? = null,
)

@Serializable
internal data class StoredLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
    val region: String? = null,
    val country: String? = null,
)

@Serializable
internal data class StoredCurrent(
    val observedAtEpochSecond: Long,
    val temperature: Double?,
    val apparentTemperature: Double?,
    val condition: String,
    val isDay: Boolean,
    val precipitation: Double?,
    val windSpeed: Double?,
    /** Defaulted, so forecasts cached before gusts existed still read back. */
    val windGust: Double? = null,
    val windDirection: Int?,
    val humidity: Int?,
    val pressure: Double?,
)

@Serializable
internal data class StoredHour(
    val timestampEpochSecond: Long,
    val temperature: Double?,
    /** Defaulted, so forecasts cached before it existed still read back. */
    val apparentTemperature: Double? = null,
    val precipitationProbability: Int?,
    val precipitation: Double?,
    val rain: Double?,
    val snowfall: Double?,
    val condition: String,
    val windSpeed: Double?,
    /** Defaulted, so forecasts cached before gusts existed still read back. */
    val windGust: Double? = null,
    /** Defaulted, so forecasts cached before these existed still read back. */
    /** Defaulted, so forecasts cached before it existed still read back. */
    val uvIndex: Double? = null,
    val cloudCover: Int?,
    /** Defaulted, so forecasts cached before the decks existed still read back. */
    val cloudLow: Int? = null,
    val cloudMedium: Int? = null,
    val cloudHigh: Int? = null,
    val isDay: Boolean,
)

@Serializable
internal data class StoredDay(
    val date: String,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val condition: String,
    val precipitationTotal: Double?,
    val precipitationProbabilityMax: Int?,
    val precipitationHours: Double?,
    val sunriseEpochSecond: Long?,
    val sunsetEpochSecond: Long?,
    val windSpeedMax: Double?,
)

@Serializable
internal data class StoredProvider(
    val id: String,
    val name: String,
    val model: String? = null,
    val resolutionKm: Double? = null,
    val forecastGeneratedAtEpochSecond: Long? = null,
    val attribution: String,
)

@Serializable
internal data class StoredSupplement(val provider: StoredProvider, val fromEpochSecond: Long)

// --- domain to stored ---------------------------------------------------------

internal fun WeatherForecast.toStored() = StoredForecast(
    location = location.toStored(),
    current = current.toStored(),
    hourly = hourly.map { it.toStored() },
    daily = daily.map { it.toStored() },
    fetchedAtEpochSecond = fetchedAt.epochSecond,
    provider = provider.toStored(),
    supplement = supplement?.let {
        StoredSupplement(it.provider.toStored(), it.from.epochSecond)
    },
)

private fun WeatherLocation.toStored() = StoredLocation(
    name = name,
    latitude = latitude,
    longitude = longitude,
    zoneId = zone.id,
    region = region,
    country = country,
)

private fun CurrentWeather.toStored() = StoredCurrent(
    observedAtEpochSecond = observedAt.epochSecond,
    temperature = temperature,
    apparentTemperature = apparentTemperature,
    condition = condition.name,
    isDay = isDay,
    precipitation = precipitation,
    windSpeed = windSpeed,
    windGust = windGust,
    windDirection = windDirection,
    humidity = humidity,
    pressure = pressure,
)

private fun HourlyWeather.toStored() = StoredHour(
    timestampEpochSecond = timestamp.epochSecond,
    temperature = temperature,
    apparentTemperature = apparentTemperature,
    precipitationProbability = precipitationProbability,
    precipitation = precipitation,
    rain = rain,
    snowfall = snowfall,
    condition = condition.name,
    windSpeed = windSpeed,
    windGust = windGust,
    uvIndex = uvIndex,
    cloudCover = cloudCover,
    cloudLow = cloudLow,
    cloudMedium = cloudMedium,
    cloudHigh = cloudHigh,
    isDay = isDay,
)

private fun DailyWeather.toStored() = StoredDay(
    date = date.toString(),
    temperatureMin = temperatureMin,
    temperatureMax = temperatureMax,
    condition = condition.name,
    precipitationTotal = precipitationTotal,
    precipitationProbabilityMax = precipitationProbabilityMax,
    precipitationHours = precipitationHours,
    sunriseEpochSecond = sunrise?.epochSecond,
    sunsetEpochSecond = sunset?.epochSecond,
    windSpeedMax = windSpeedMax,
)

private fun ProviderMetadata.toStored() = StoredProvider(
    id = id,
    name = name,
    model = model,
    resolutionKm = resolutionKm,
    forecastGeneratedAtEpochSecond = forecastGeneratedAt?.epochSecond,
    attribution = attribution,
)

// --- stored to domain ---------------------------------------------------------

internal fun StoredForecast.toDomain() = WeatherForecast(
    location = location.toDomain(),
    current = current.toDomain(),
    hourly = hourly.map { it.toDomain() },
    daily = daily.map { it.toDomain() },
    fetchedAt = Instant.ofEpochSecond(fetchedAtEpochSecond),
    provider = provider.toDomain(),
    supplement = supplement?.let {
        ForecastSupplement(it.provider.toDomain(), Instant.ofEpochSecond(it.fromEpochSecond))
    },
)

private fun StoredLocation.toDomain() = WeatherLocation(
    name = name,
    latitude = latitude,
    longitude = longitude,
    zone = ZoneId.of(zoneId),
    region = region,
    country = country,
)

private fun StoredCurrent.toDomain() = CurrentWeather(
    observedAt = Instant.ofEpochSecond(observedAtEpochSecond),
    temperature = temperature,
    apparentTemperature = apparentTemperature,
    condition = condition.toCondition(),
    isDay = isDay,
    precipitation = precipitation,
    windSpeed = windSpeed,
    windGust = windGust,
    windDirection = windDirection,
    humidity = humidity,
    pressure = pressure,
)

private fun StoredHour.toDomain() = HourlyWeather(
    timestamp = Instant.ofEpochSecond(timestampEpochSecond),
    temperature = temperature,
    apparentTemperature = apparentTemperature,
    precipitationProbability = precipitationProbability,
    precipitation = precipitation,
    rain = rain,
    snowfall = snowfall,
    condition = condition.toCondition(),
    windSpeed = windSpeed,
    windGust = windGust,
    uvIndex = uvIndex,
    cloudCover = cloudCover,
    cloudLow = cloudLow,
    cloudMedium = cloudMedium,
    cloudHigh = cloudHigh,
    isDay = isDay,
)

private fun StoredDay.toDomain() = DailyWeather(
    date = LocalDate.parse(date),
    temperatureMin = temperatureMin,
    temperatureMax = temperatureMax,
    condition = condition.toCondition(),
    precipitationTotal = precipitationTotal,
    precipitationProbabilityMax = precipitationProbabilityMax,
    precipitationHours = precipitationHours,
    sunrise = sunriseEpochSecond?.let(Instant::ofEpochSecond),
    sunset = sunsetEpochSecond?.let(Instant::ofEpochSecond),
    windSpeedMax = windSpeedMax,
)

private fun StoredProvider.toDomain() = ProviderMetadata(
    id = id,
    name = name,
    model = model,
    resolutionKm = resolutionKm,
    forecastGeneratedAt = forecastGeneratedAtEpochSecond?.let(Instant::ofEpochSecond),
    attribution = attribution,
)

/**
 * A condition name written by a newer version of the app, read by an older one,
 * is not a reason to throw away the whole forecast.
 */
private fun String.toCondition(): WeatherCondition =
    runCatching { WeatherCondition.valueOf(this) }.getOrDefault(WeatherCondition.UNKNOWN)
