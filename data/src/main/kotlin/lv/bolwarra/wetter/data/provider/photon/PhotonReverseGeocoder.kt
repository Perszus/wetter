package lv.bolwarra.wetter.data.provider.photon

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.domain.location.PlaceName

/**
 * What is at a point on the map.
 *
 * The place search is a gazetteer of settlements and cannot answer this: asked
 * for a street it returns nothing, and it has no reverse direction at all. So a
 * dropped pin had no name and was labelled with its own coordinates, which is
 * exact, useless to read, and the reason this exists.
 *
 * ### Why Photon
 *
 * `docs/decisions.md` measured the two candidates that need no key. Both
 * resolve: a pin in Mezaparks comes back from either as a house number on
 * Stendera iela. Nominatim is the faster of the two - a third of Photon's
 * latency in testing - and slightly richer, naming the square in central Riga
 * where Photon named only the old town.
 *
 * Photon is used anyway, because speed is not the constraint here. Nominatim's
 * usage policy is written around a strict rate cap and forbids the interactive,
 * type-and-see pattern outright; Photon is built by komoot precisely for
 * interactive geocoding, which is what a pin being dragged about is. The cost is
 * that it is one volunteer-run instance with no promise of being up, which is
 * why nothing in the app depends on an answer arriving.
 *
 * ### It is asked rarely on purpose
 *
 * One request when the map settles, never one per frame of a drag. A pin moved
 * across a city would otherwise fire a hundred lookups at somebody's donated
 * server, which is the behaviour that gets an app blocked rather than a
 * technical limit.
 */
class PhotonReverseGeocoder internal constructor(
    private val client: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /**
     * The name of a point, or null when it has none.
     *
     * Null is an ordinary answer. Most of the earth is water or unnamed ground,
     * and a caller must be able to carry on without this - the coordinates were
     * always the real identity of the place and remain so.
     */
    suspend fun nameOf(at: Coordinates): PlaceName? = try {
        val response: PhotonResponse = client.get(baseUrl) {
            parameter("lat", at.latitude)
            parameter("lon", at.longitude)
            parameter("limit", 1)
        }.body()
        response.features.firstOrNull()?.properties?.toPlaceName()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    /**
     * The most specific thing worth calling this place, then where it sits.
     *
     * A house number alone means nothing, so it only appears attached to its
     * street. Below that the district, then the settlement - and if the label
     * has already used the settlement's name, it is not repeated underneath it.
     */
    private fun PhotonProperties.toPlaceName(): PlaceName? {
        val settlement = city ?: county ?: state
        val label = when {
            street != null && housenumber != null -> "$street $housenumber"
            street != null -> street
            name != null -> name
            district != null -> district
            settlement != null -> settlement
            else -> null
        } ?: return null

        return PlaceName(
            label = label,
            region = settlement?.takeUnless { it == label },
            country = country,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://photon.komoot.io/reverse"

        /** Photon's data is OpenStreetMap's, and the licence follows it. */
        const val ATTRIBUTION = "Place names from Photon, © OpenStreetMap contributors"
    }
}

@Serializable
internal data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
internal data class PhotonFeature(val properties: PhotonProperties = PhotonProperties())

@Serializable
internal data class PhotonProperties(
    val name: String? = null,
    val street: String? = null,
    val housenumber: String? = null,
    val district: String? = null,
    val city: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
    @SerialName("countrycode") val countryCode: String? = null,
)
