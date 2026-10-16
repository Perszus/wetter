package lv.bolwarra.wetter.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.Cache

/**
 * The one HTTP client, shared by every provider.
 *
 * One client means one connection pool and one set of timeouts. The timeouts are
 * short on purpose: a refresh that has already taken fifteen seconds has failed
 * as far as the user is concerned, and the router has a second provider waiting.
 */
internal object WetterHttpClient {

    /**
     * MET Norway's terms require a User-Agent that identifies the application and
     * gives a way to make contact; sending a generic one is grounds for being
     * blocked. Open-Meteo does not require it, but sending the same honest string
     * everywhere is simpler than remembering which provider cares.
     *
     * The URL must point at somewhere a provider can actually reach a human.
     *
     * The version is passed in rather than read here: it belongs to the
     * application, and :data has no business knowing what it is bundled into.
     */
    fun userAgent(applicationVersion: String): String = "Wetter/$applicationVersion (+$CONTACT_URL)"

    const val CONTACT_URL = "https://github.com/Perszus/wetter"

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

    /**
     * @param cacheDirectory where responses may be kept on disk, honouring what
     *   each service says about them. See [CACHE_BYTES].
     */
    fun create(
        userAgent: String,
        engine: HttpClientEngine? = null,
        cacheDirectory: File? = null,
    ): HttpClient {
        val configure: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            expectSuccess = true

            install(ContentNegotiation) {
                json(json)
            }
            install(UserAgent) {
                agent = userAgent
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
        return when {
            engine != null -> HttpClient(engine, configure)
            else -> HttpClient(OkHttp) {
                engine {
                    config {
                        if (cacheDirectory != null) cache(Cache(cacheDirectory, CACHE_BYTES))
                    }
                }
                configure()
            }
        }
    }

    /**
     * An ordinary HTTP disk cache, governed entirely by what each service says.
     *
     * There was none, so a cold start fetched again what a warm one already
     * had. Every service here states its own terms, and the cache obeys them
     * rather than any policy of ours:
     *
     * - radar tiles live at a path unique to their sweep and are marked good
     *   for two days, so the sweeps behind a fresh process come off disk
     *   instead of being downloaded again;
     * - basemap tiles are marked good for days, which OpenStreetMap's tile
     *   policy asks clients to honour - the map reopens without asking again;
     * - MET Norway sends `Expires` and `Last-Modified`, and its terms ask for
     *   exactly the conditional requests this makes automatic;
     * - the radar index says `no-cache`, so it is revalidated every time and
     *   can never be served stale;
     * - Open-Meteo sends no caching headers at all, so a forecast from it is
     *   never answered from disk.
     *
     * Thirty-two megabytes: a day of radar sweeps and a few map openings, in
     * the app's cache directory where the system may clear it under pressure.
     */
    private const val CACHE_BYTES = 32L * 1024 * 1024
}
