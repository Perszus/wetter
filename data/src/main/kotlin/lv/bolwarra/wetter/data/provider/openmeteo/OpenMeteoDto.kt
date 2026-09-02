package lv.bolwarra.wetter.data.provider.openmeteo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Open-Meteo's forecast response, exactly as it arrives.
 *
 * These types stop at the edge of this package. Nothing outside
 * `data/provider/openmeteo/` may name one — the mapper next door turns them into
 * Wetter's own models and that is the only way out (docs/providers.md).
 *
 * Every field is nullable or defaulted. Open-Meteo omits whole blocks when a
 * variable is unavailable for a location, and an app in someone's pocket must
 * degrade rather than fail on a missing array.
 *
 * Times arrive as local ISO-8601 without an offset ("2026-03-14T09:00"), paired
 * with the resolved [timezone]. That is Open-Meteo's intended pairing; the
 * unixtime alternative applies its offset differently to hourly and daily
 * values, which is one subtlety too many for a field nobody would notice being
 * an hour wrong.
 */
@Serializable
data class OpenMeteoResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val timezone: String? = null,
    val elevation: Double? = null,
    val current: OpenMeteoCurrent? = null,
    val hourly: OpenMeteoHourly? = null,
    val daily: OpenMeteoDaily? = null,
)

@Serializable
data class OpenMeteoCurrent(
    val time: String? = null,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Int? = null,
    @SerialName("is_day") val isDay: Int? = null,
    val precipitation: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("pressure_msl") val pressureMsl: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    @SerialName("wind_direction_10m") val windDirection: Int? = null,
)

@Serializable
data class OpenMeteoHourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val rain: List<Double?> = emptyList(),
    /** Centimetres of snow, not millimetres of melt. Open-Meteo's own unit. */
    val snowfall: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Int?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
    @SerialName("is_day") val isDay: List<Int?> = emptyList(),
)

@Serializable
data class OpenMeteoDaily(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val temperatureMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val temperatureMin: List<Double?> = emptyList(),
    val sunrise: List<String?> = emptyList(),
    val sunset: List<String?> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?> =
        emptyList(),
    @SerialName("precipitation_hours") val precipitationHours: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m_max") val windSpeedMax: List<Double?> = emptyList(),
)
