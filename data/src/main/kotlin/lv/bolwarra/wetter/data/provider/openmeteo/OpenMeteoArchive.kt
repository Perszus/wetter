// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.provider.openmeteo

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.domain.climate.ArchivedDay
import lv.bolwarra.wetter.domain.model.WeatherLocation

@Serializable
internal data class OpenMeteoArchiveResponse(val daily: OpenMeteoArchiveDaily? = null)

@Serializable
internal data class OpenMeteoArchiveDaily(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m_max") val high: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val low: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipitation: List<Double?> = emptyList(),
    @SerialName("apparent_temperature_max") val apparentHigh: List<Double?> = emptyList(),
    @SerialName("apparent_temperature_min") val apparentLow: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m_max") val gust: List<Double?> = emptyList(),
)

/**
 * A decade of what actually happened here, for the far end of the Month page:
 * ERA5 reanalysis through Open-Meteo's archive, under the same terms and
 * attribution as the forecast.
 *
 * Not a provider: it answers what has happened, has nothing to compete with, and
 * its absence costs a few squares rather than the forecast.
 *
 * Ten years rather than the WMO's thirty: with a five-day window that is over a
 * hundred samples per date, and the recent decade is the better guide to a
 * climate that has moved.
 */
internal class OpenMeteoArchive(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /**
     * Every archived day in the [YEARS] before [endingBefore]. Failure returns an
     * empty list: a few blank squares are not worth an error.
     */
    suspend fun history(location: WeatherLocation, endingBefore: LocalDate): List<ArchivedDay> =
        try {
            val end = endingBefore.minusDays(SETTLING_DAYS)
            val response: OpenMeteoArchiveResponse = client.get(baseUrl) {
                parameter("latitude", location.latitude)
                parameter("longitude", location.longitude)
                parameter("start_date", end.minusYears(YEARS).toString())
                parameter("end_date", end.toString())
                parameter("daily", DAILY_VARIABLES)
                parameter("timezone", "auto")
                parameter("temperature_unit", "celsius")
                parameter("precipitation_unit", "mm")
                // Metres per second, because the domain computes in them and a
                // gust silently arriving in km/h would put the local wind
                // threshold out by a factor of 3.6 - which is not a wrong
                // number anybody would notice, only a warning that never fires.
                parameter("wind_speed_unit", "ms")
            }.body()

            response.daily?.toDays().orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emptyList()
        }

    private fun OpenMeteoArchiveDaily.toDays(): List<ArchivedDay> =
        time.mapIndexedNotNull { index, day ->
            val date = try {
                LocalDate.parse(day)
            } catch (_: DateTimeParseException) {
                return@mapIndexedNotNull null
            }
            ArchivedDay(
                date = date,
                high = high.getOrNull(index),
                low = low.getOrNull(index),
                precipitation = precipitation.getOrNull(index),
                apparentHigh = apparentHigh.getOrNull(index),
                apparentLow = apparentLow.getOrNull(index),
                gust = gust.getOrNull(index),
            )
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://archive-api.open-meteo.com/v1/archive"

        /** See the note on the class. */
        const val YEARS = 10L

        /**
         * The reanalysis runs a few days behind the present, so the last week or
         * so of the range would come back as nulls. Asking for it anyway is
         * harmless and asking for less is tidier.
         */
        const val SETTLING_DAYS = 7L

        /**
         * The medians the Month page draws, and the tails the hazard thresholds come from,
         * in apparent temperature too, so local thresholds match the reading they judge.
         */
        private const val DAILY_VARIABLES =
            "temperature_2m_max,temperature_2m_min,precipitation_sum," +
                "apparent_temperature_max,apparent_temperature_min,wind_gusts_10m_max"
    }
}
