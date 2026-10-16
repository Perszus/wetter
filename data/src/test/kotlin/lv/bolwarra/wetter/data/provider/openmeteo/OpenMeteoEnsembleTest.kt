package lv.bolwarra.wetter.data.provider.openmeteo

import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import lv.bolwarra.wetter.data.provider.clientReturning
import lv.bolwarra.wetter.domain.model.WeatherLocation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape of the answer the ensemble is built from, and the part of it that
 * is not what it looks like.
 */
class OpenMeteoEnsembleTest {

    private val tokyo = WeatherLocation("Tokyo", 35.68, 139.69, ZoneOffset.UTC)

    /**
     * Three models by name, two of them one model: outside Europe the KNMI and
     * DMI products fall back to ECMWF, and the service returns the same numbers
     * under each name. Recorded shape, trimmed to two hours.
     */
    private val payload = """
        {
          "hourly": {
            "time": [1790000000, 1790003600],
            "temperature_2m_ecmwf_ifs": [20.1, 20.4],
            "temperature_2m_gem_seamless": [19.2, 19.0],
            "temperature_2m_knmi_seamless": [20.1, 20.4],
            "precipitation_ecmwf_ifs": [0.0, 1.2],
            "precipitation_gem_seamless": [0.4, 0.3],
            "precipitation_knmi_seamless": [0.0, 1.2]
          }
        }
    """.trimIndent()

    @Test
    fun `a model under two names votes once`() = runBlocking {
        val ensemble = OpenMeteoEnsemble(clientReturning(payload)).ensemble(tokyo).getOrThrow()
        ensemble.readings.forEach { reading ->
            assertEquals(2, reading.precipitationByModel.size)
        }
        // The two distinct models, in the order they were asked for.
        assertEquals(listOf(1.2, 0.3), ensemble.readings[1].precipitationByModel)
    }

    @Test
    fun `models that genuinely differ are all kept`() = runBlocking {
        val distinct = payload.replace(
            "\"precipitation_knmi_seamless\": [0.0, 1.2]",
            "\"precipitation_knmi_seamless\": [0.0, 1.1]",
        )
        val ensemble = OpenMeteoEnsemble(clientReturning(distinct)).ensemble(tokyo).getOrThrow()
        assertEquals(3, ensemble.readings[1].precipitationByModel.size)
    }
}
