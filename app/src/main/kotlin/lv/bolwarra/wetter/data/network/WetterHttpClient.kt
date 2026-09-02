package lv.bolwarra.wetter.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import lv.bolwarra.wetter.BuildConfig

/**
 * The one HTTP client, shared by every provider.
 *
 * One client means one connection pool and one set of timeouts. The timeouts are
 * short on purpose: a refresh that has already taken fifteen seconds has failed
 * as far as the user is concerned, and the router has a second provider waiting.
 */
object WetterHttpClient {

    /**
     * MET Norway's terms require a User-Agent that identifies the application and
     * gives a way to make contact; sending a generic one is grounds for being
     * blocked. Open-Meteo does not require it, but sending the same honest string
     * everywhere is simpler than remembering which provider cares.
     *
     * The URL must point at somewhere a provider can actually reach a human.
     */
    const val USER_AGENT = "Wetter/${BuildConfig.VERSION_NAME} (+https://github.com/Perszus/wetter)"

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val SOCKET_TIMEOUT_MS = 10_000L
    private const val REQUEST_TIMEOUT_MS = 15_000L

    /**
     * Lenient about unknown keys by design: providers add fields to their
     * responses regularly, and an app in someone's pocket must not start
     * reporting "malformed response" because a new variable appeared.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    fun create(engine: HttpClientEngine? = null): HttpClient {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            expectSuccess = true

            install(ContentNegotiation) {
                json(json)
            }
            install(UserAgent) {
                agent = USER_AGENT
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(OkHttp, configure)
    }
}
