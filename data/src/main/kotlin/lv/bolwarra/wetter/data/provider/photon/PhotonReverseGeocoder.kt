// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.provider.photon

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import lv.bolwarra.wetter.domain.location.Coordinates
import lv.bolwarra.wetter.domain.location.PlaceName

/**
 * What is at a point on the map, for naming a dropped pin.
 *
 * Photon, not Nominatim: Nominatim's usage policy forbids the interactive pattern
 * a pin being dragged about is, while Photon is built for it. It is one
 * volunteer-run instance, so nothing depends on an answer arriving, and it is
 * asked once when the map settles, never per frame.
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
            parameter("lang", language())
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

    /**
     * Names in the phone's language where Photon offers it, otherwise as the place
     * itself writes them. Photon refuses any other value outright.
     */
    private fun language(): String =
        Locale.getDefault().language.takeIf { it in TRANSLATED } ?: "default"

    companion object {
        const val DEFAULT_BASE_URL = "https://photon.komoot.io/reverse"

        private val TRANSLATED = setOf("de", "en", "fr")

        /** Photon's data is OpenStreetMap's, and the licence follows it. */
        const val ATTRIBUTION = "Place names from Photon, ©\u00A0OpenStreetMap contributors"
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
