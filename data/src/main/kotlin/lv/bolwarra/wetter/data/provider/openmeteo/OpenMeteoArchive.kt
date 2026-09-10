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
)

/**
 * A decade of what actually happened here, for the far end of the Month page.
 *
 * ERA5 reanalysis through Open-Meteo's archive endpoint — the same service, the
 * same terms and the same attribution as the forecast, so this adds a request
 * rather than a dependency or an account.
 *
 * ### It is not a provider
 *
 * Deliberately outside `WeatherProvider` and outside the router's ranking. A
 * provider answers "what will it do", competes with the others to answer it, and
 * can fail over to one of them. This answers "what has it done", has nothing to
 * compete with, and its absence costs the far squares of one page rather than
 * the forecast. Making it a provider would have put it in a ranking where every
 * comparison is meaningless.
 *
 * ### Ten years, and why not thirty
 *
 * The WMO standard normal is thirty years and would be three times the payload
 * for a page that is showing them as a faded backdrop to a forecast. Ten with a
 * five-day window already puts a hundred and ten samples behind each date, which
 * is enough for the median to stop moving; the argument for thirty is about
 * detecting climate trends, which is not what this page is for.
 *
 * A decade is also the more honest decade: a normal measured over 1961–1990 is
 * not what this September does any more, and the recent past is the better guide
 * to the near future precisely because the climate has moved.
 */
internal class OpenMeteoArchive(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /**
     * Every archived day in the [YEARS] before [endingBefore].
     *
     * Failure returns an empty list rather than throwing. Nothing here is worth
     * an error on screen: the page it feeds is already drawing squares it has no
     * forecast for, and a few of them staying blank is the same page slightly
     * emptier.
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

        private const val DAILY_VARIABLES =
            "temperature_2m_max,temperature_2m_min,precipitation_sum"
    }
}
