package lv.bolwarra.wetter.domain.model

import java.time.ZoneId

/**
 * A place a forecast can be asked for.
 *
 * The zone is part of the location rather than something the UI derives, because
 * "18:00" on a forecast means 18:00 *there*. Rendering a saved location in the
 * phone's current zone would silently shift every hour on the timeline.
 */
data class WeatherLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val zone: ZoneId,
    /** Admin area — disambiguates the many places that share a name. */
    val region: String? = null,
    val country: String? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}
