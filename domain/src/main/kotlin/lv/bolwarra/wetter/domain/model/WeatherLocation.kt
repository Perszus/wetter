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
    /**
     * Metres above sea level, when the place came from somewhere that knows.
     *
     * Not decoration: the observation layer corrects a station's temperature to
     * the target's height, and without this it cannot, so a city in a valley
     * gets read off a hilltop aerodrome uncorrected. Nullable because a place
     * chosen from the built-in list has no elevation attached and inventing one
     * would be worse than going without.
     */
    val elevationMetres: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}
