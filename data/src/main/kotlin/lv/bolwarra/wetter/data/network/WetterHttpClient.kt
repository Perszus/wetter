// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * The one HTTP client, shared by every provider. Timeouts are short: a refresh that
 * has taken fifteen seconds has failed for the reader, and the router has a second
 * provider waiting.
 */
internal object WetterHttpClient {

    /**
     * MET Norway's terms require a User-Agent that identifies the application and
     * gives a contact; the same string is sent everywhere. The version is passed in by
     * the application.
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
     * An ordinary HTTP disk cache, governed by what each service says:
     *
     * - radar tiles are good for two days at a sweep-unique path;
     * - basemap tiles are good for days, as OpenStreetMap's tile policy asks;
     * - MET Norway sends `Expires` and `Last-Modified`, and its terms ask for these
     * conditional requests;
     * - the radar index says `no-cache` and is revalidated every time;
     * - Open-Meteo sends no caching headers and is never answered from disk.
     *
     * Thirty-two megabytes in the app's cache directory, which the system may clear.
     */
    private const val CACHE_BYTES = 32L * 1024 * 1024
}
