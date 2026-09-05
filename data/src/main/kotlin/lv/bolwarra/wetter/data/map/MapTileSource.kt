package lv.bolwarra.wetter.data.map

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException

/**
 * The basemap a dropped pin is dropped onto.
 *
 * Map tiles rather than weather, which is why this sits apart from the
 * providers: nothing here is fused, verified or drawn on a chart. It exists so
 * somebody can see where they are pointing.
 *
 * ### Why OpenStreetMap's own tiles
 *
 * `docs/decisions.md` recorded that OSM's tile server forbids app use and that
 * a pin therefore meant MapTiler, Stadia or Protomaps - a key, possibly a bill,
 * and a native rendering library that would dominate the download. Read again,
 * the policy says something narrower. It prohibits **bulk downloading**, which
 * it defines as "pre-emptive fetching of tiles other than those a user is
 * actively viewing", and singles out offline-download features. It requires a
 * "distinct, stable User-Agent naming your app and optionally a contact URL".
 * Ordinary interactive viewing is not forbidden; it is what the service is for.
 *
 * So the constraint is on behaviour, and this is written to satisfy it:
 *
 * - only tiles currently on screen are ever requested, and only when they come
 *   on screen. There is no prefetch, no ring of neighbours, no seeding.
 * - there is no offline map and no "download this area", which the policy names
 *   explicitly as prohibited.
 * - the User-Agent is the one every other request in this app already carries,
 *   which names the app and links to its source.
 *
 * The remaining exposure is scale rather than conduct: the policy also reserves
 * the right to block traffic that degrades the service, and a popular app is
 * heavy however politely it behaves. If Wetter ever gets there the honest move
 * is to pay somebody for tiles, and this class is the one place that changes.
 *
 * CARTO was the alternative worth measuring and is now out for a different
 * reason: their basemaps grew an API key requirement, which is the thing being
 * avoided.
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

        /**
         * Shown on the map itself, not buried in About.
         *
         * The ODbL requires the credit to appear with the map it belongs to,
         * and this is the one place in Wetter where a source is named on screen
         * rather than kept to ourselves - because here it is a licence term
         * rather than an explanation of our machinery.
         */
        const val ATTRIBUTION = "© OpenStreetMap"
    }
}
