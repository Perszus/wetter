package lv.bolwarra.wetter.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.ZoneId
import lv.bolwarra.wetter.data.network.WetterHttpClient
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * Identifies these requests as tests rather than as the app, in the unlikely
 * event one ever escapes a MockEngine.
 */
internal const val TEST_USER_AGENT = "Wetter-tests/0 (+https://github.com/Perszus/wetter)"

/** Loads a recorded response from `src/test/resources`. */
internal fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResource("/$name")) { "missing fixture: $name" }.readText()

/**
 * A client that answers every request with the given body.
 *
 * Built through [WetterHttpClient.create] rather than a bare `HttpClient`, so the
 * tests exercise the same JSON configuration, timeouts and User-Agent the app
 * ships with. A test that configured its own parser would pass while the real
 * client failed on the same response.
 */
internal fun clientReturning(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpClient = WetterHttpClient.create(
    TEST_USER_AGENT,
    MockEngine {
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    },
)

internal fun clientFailing(status: HttpStatusCode, retryAfterSeconds: Long? = null): HttpClient =
    WetterHttpClient.create(
        TEST_USER_AGENT,
        MockEngine {
            if (retryAfterSeconds != null) {
                respond(
                    content = "rate limited",
                    status = status,
                    headers = headersOf(HttpHeaders.RetryAfter, retryAfterSeconds.toString()),
                )
            } else {
                respondError(status)
            }
        },
    )

internal fun clientThrowing(failure: Throwable): HttpClient = WetterHttpClient.create(
    TEST_USER_AGENT,
    MockEngine { throw failure },
)

internal val riga = WeatherLocation(
    name = "Rīga",
    latitude = 56.9496,
    longitude = 24.1052,
    zone = ZoneId.of("Europe/Riga"),
    country = "Latvia",
)
