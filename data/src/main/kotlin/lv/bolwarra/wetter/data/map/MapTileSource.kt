// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.map

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException

/**
 * The basemap a dropped pin is dropped onto: map tiles, not weather.
 *
 * OpenStreetMap's own tiles. Its tile usage policy forbids bulk and pre-emptive
 * fetching and offline downloads, and asks for an identifying User-Agent; ordinary
 * interactive viewing is what it serves. So only tiles on screen are requested, as
 * they come on screen, with no prefetch and no offline area, under the app's own
 * User-Agent. Should usage outgrow a donated service, this class is the one place
 * that changes.
 */
class MapTileSource internal constructor(private val client: HttpClient) {

    /**
     * One 256-pixel tile as encoded bytes, or null if it could not be had.
     *
     * Null rather than an exception because a missing tile is a hole in a
     * picture, not a failure of anything: the map draws what it has and the
     * gesture that caused the fetch has usually moved on anyway.
     */
    suspend fun tile(zoom: Int, x: Int, y: Int): ByteArray? = try {
        val response: HttpResponse = client.get("$HOST/$zoom/$x/$y.png")
        response.readRawBytes()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val HOST = "https://tile.openstreetmap.org"

        /** Shown on the map itself, as the ODbL requires the credit to appear with the map. */
        const val ATTRIBUTION = "© OpenStreetMap contributors"
    }
}
