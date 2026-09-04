package lv.bolwarra.wetter.data.provider.metnorway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MET Norway's locationforecast 2.0 response, exactly as it arrives.
 *
 * These types stop at the edge of this package (docs/providers.md).
 *
 * The shape is worth knowing before reading the mapper. There is no daily block
 * and no hourly block — only one `timeseries` of instants, each carrying the
 * conditions at that moment plus optional forecasts for the following 1, 6 and
 * 12 hours. The series is hourly for roughly the first two and a half days and
 * six-hourly after that, so "the hourly forecast" is a subset of it rather than
 * a field, and anything daily has to be aggregated by us.
 */
@Serializable
data class MetNorwayResponse(val properties: MetNorwayProperties = MetNorwayProperties())

@Serializable
data class MetNorwayProperties(
    val meta: MetNorwayMeta = MetNorwayMeta(),
    val timeseries: List<MetNorwayTimeStep> = emptyList(),
)

@Serializable
data class MetNorwayMeta(
    /** When this model run was published — the one provider that tells us. */
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class MetNorwayTimeStep(
    /** ISO-8601 with a Z suffix. Always UTC, unlike Open-Meteo's local times. */
    val time: String,
    val data: MetNorwayData = MetNorwayData(),
)

@Serializable
data class MetNorwayData(
    val instant: MetNorwayInstant = MetNorwayInstant(),
    @SerialName("next_1_hours") val next1Hours: MetNorwayPeriod? = null,
    @SerialName("next_6_hours") val next6Hours: MetNorwayPeriod? = null,
    @SerialName("next_12_hours") val next12Hours: MetNorwayPeriod? = null,
)

@Serializable
data class MetNorwayInstant(val details: MetNorwayInstantDetails = MetNorwayInstantDetails())

@Serializable
data class MetNorwayInstantDetails(
    @SerialName("air_temperature") val airTemperature: Double? = null,
    @SerialName("air_pressure_at_sea_level") val pressure: Double? = null,
    @SerialName("relative_humidity") val humidity: Double? = null,
    @SerialName("cloud_area_fraction") val cloudAreaFraction: Double? = null,
    @SerialName("wind_speed") val windSpeed: Double? = null,
    @SerialName("wind_speed_of_gust") val windGust: Double? = null,
    @SerialName("ultraviolet_index_clear_sky") val uvIndex: Double? = null,
    @SerialName("dew_point_temperature") val dewPoint: Double? = null,
    @SerialName("apparent_air_temperature") val apparentTemperature: Double? = null,
    @SerialName("wind_from_direction") val windFromDirection: Double? = null,
)

@Serializable
data class MetNorwayPeriod(
    val summary: MetNorwaySummary = MetNorwaySummary(),
    val details: MetNorwayPeriodDetails = MetNorwayPeriodDetails(),
)

@Serializable
data class MetNorwaySummary(
    /** For example "lightrainshowers_day". See MetNorwaySymbol. */
    @SerialName("symbol_code") val symbolCode: String? = null,
)

@Serializable
data class MetNorwayPeriodDetails(
    /** Millimetres of liquid equivalent, snow included. */
    @SerialName("precipitation_amount") val precipitationAmount: Double? = null,
    @SerialName("probability_of_precipitation") val probabilityOfPrecipitation: Double? = null,
    @SerialName("air_temperature_max") val temperatureMax: Double? = null,
    @SerialName("air_temperature_min") val temperatureMin: Double? = null,
)
