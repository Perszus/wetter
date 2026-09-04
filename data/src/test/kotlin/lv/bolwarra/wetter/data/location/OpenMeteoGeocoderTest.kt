package lv.bolwarra.wetter.data.location

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import lv.bolwarra.wetter.data.network.WetterHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payloads here are trimmed from real responses, including the awkward ones:
 * a place with no time zone, and a query that matched nothing.
 */
class OpenMeteoGeocoderTest {

    private fun geocoder(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): OpenMeteoGeocoder {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return OpenMeteoGeocoder(WetterHttpClient.create("test", engine))
    }

    @Test
    fun `a place comes back with everything the app needs`() = runBlocking {
        val found = geocoder(
            """
            {"results":[{"id":456172,"name":"Riga","latitude":56.94600,"longitude":24.10589,
            "elevation":6.0,"timezone":"Europe/Riga","country":"Latvia","admin1":"Riga",
            "population":742572}]}
            """.trimIndent(),
        ).search("Riga").getOrThrow()

        assertEquals(1, found.size)
        val riga = found.first()
        assertEquals("Riga", riga.name)
        assertEquals(56.946, riga.latitude, 0.001)
        assertEquals(ZoneId.of("Europe/Riga"), riga.zone)
        assertEquals("Latvia", riga.country)
        // Elevation is the reason this geocoder was chosen over a plain one: the
        // observation layer corrects a station's temperature to this height.
        assertEquals(6.0, riga.elevationMetres!!, 0.001)
    }

    @Test
    fun `a place with no time zone is dropped, not defaulted`() = runBlocking {
        // Shown in the phone's zone instead, every hour on the timeline would be
        // silently shifted and every reading would still look plausible. There
        // is no safe fallback, so there is no fallback.
        val found = geocoder(
            """
            {"results":[
              {"name":"Nowhere","latitude":10.0,"longitude":10.0},
              {"name":"Somewhere","latitude":11.0,"longitude":11.0,"timezone":"Europe/Riga"}
            ]}
            """.trimIndent(),
        ).search("wherever").getOrThrow()

        assertEquals(1, found.size)
        assertEquals("Somewhere", found.first().name)
    }

    @Test
    fun `a nonsense zone is treated as no zone`() = runBlocking {
        val found = geocoder(
            """{"results":[{"name":"Odd","latitude":1.0,"longitude":1.0,"timezone":"Mars/Olympus"}]}""",
        ).search("odd").getOrThrow()
        assertTrue(found.isEmpty())
    }

    @Test
    fun `no matches is an empty list, not a failure`() = runBlocking {
        // The service omits the array entirely rather than sending an empty one.
        val outcome = geocoder("""{"generationtime_ms":0.3}""").search("zzzznotaplace")
        assertTrue(outcome.isSuccess)
        assertTrue(outcome.getOrThrow().isEmpty())
    }

    @Test
    fun `a query too short to mean anything is not sent at all`() = runBlocking {
        // Below two characters the service matches half the world. The engine
        // would throw if it were called, since it has no response queued.
        val engine = MockEngine { error("should not have been asked") }
        val geocoder = OpenMeteoGeocoder(WetterHttpClient.create("test", engine))

        assertTrue(geocoder.search("").getOrThrow().isEmpty())
        assertTrue(geocoder.search(" ").getOrThrow().isEmpty())
        assertTrue(geocoder.search("a").getOrThrow().isEmpty())
        assertEquals(0, engine.requestHistory.size)
    }

    @Test
    fun `a service that fails is a failure, not an empty result`() = runBlocking {
        // The screen says these differently: one keeps the saved places visible
        // with an explanation, the other says nothing matched.
        val outcome = geocoder("""{"error":true}""", HttpStatusCode.ServiceUnavailable)
            .search("Riga")
        assertTrue(outcome.isFailure)
    }

    @Test
    fun `an admin area that merely repeats the name is not shown twice`() = runBlocking {
        val found = geocoder(
            """
            {"results":[{"name":"Riga","latitude":56.9,"longitude":24.1,
            "timezone":"Europe/Riga","country":"Latvia","admin1":"Riga"}]}
            """.trimIndent(),
        ).search("Riga").getOrThrow()

        // "Riga, Riga, Latvia" reads as a mistake.
        assertEquals(null, found.first().region)
        assertEquals("Latvia", found.first().country)
    }

    @Test
    fun `coordinates outside the world are refused`() = runBlocking {
        val found = geocoder(
            """{"results":[{"name":"Bad","latitude":991.0,"longitude":0.0,"timezone":"UTC"}]}""",
        ).search("bad").getOrThrow()
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a typed coordinate is answered from the point, not the gazetteer`() = runBlocking {
        // Trimmed from a real response to 56.9496,24.1052. The gazetteer is not
        // asked at all - it matches names and returns nothing for a point.
        val found = geocoder(
            """
            {"latitude":56.95,"longitude":24.125,"generationtime_ms":0.02,
            "utc_offset_seconds":10800,"timezone":"Europe/Riga",
            "timezone_abbreviation":"GMT+3","elevation":17.0}
            """.trimIndent(),
        ).search("56.9496, 24.1052").getOrThrow()

        assertEquals(1, found.size)
        val point = found.single()
        assertEquals(56.9496, point.latitude, 1e-6)
        assertEquals(24.1052, point.longitude, 1e-6)
        assertEquals(ZoneId.of("Europe/Riga"), point.zone)
        assertEquals(17.0, point.elevationMetres!!, 1e-6)
        // Named by the point. Borrowing the nearest settlement's name would
        // answer a question nobody asked, with a different place's forecast.
        assertEquals("56.9496, 24.1052", point.name)
    }

    @Test
    fun `a point with no zone is no answer at all`() = runBlocking {
        // Same rule as a named place without one: a forecast in the wrong zone
        // shifts every hour while every reading still looks plausible.
        val found = geocoder("""{"latitude":56.95,"longitude":24.125,"elevation":17.0}""")
            .search("56.9496, 24.1052").getOrThrow()
        assertTrue(found.isEmpty())
    }
}
