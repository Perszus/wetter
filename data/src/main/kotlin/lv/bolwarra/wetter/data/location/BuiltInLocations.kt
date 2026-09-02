package lv.bolwarra.wetter.data.location

import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * A short list of places, so the forecast pipeline can be used before the
 * location phase exists.
 *
 * This is a stand-in, not a feature. Real location handling — search, saved
 * places, and optional use of the device's own position — is its own phase with
 * its own storage. What this buys now is that the provider layer can be exercised
 * by hand instead of only by its tests, which is how mapping mistakes that no
 * test predicted get found.
 *
 * The spread is deliberate: inside the Nordic fine grid, outside it but still in
 * Europe, far from any regional provider, above the arctic circle, and south of
 * the equator. Between them they exercise every branch the selector and the
 * solar calculation have.
 */
object BuiltInLocations {

    val all: List<WeatherLocation> = listOf(
        WeatherLocation(
            name = "Rīga",
            latitude = 56.9496,
            longitude = 24.1052,
            zone = ZoneId.of("Europe/Riga"),
            country = "Latvia",
        ),
        WeatherLocation(
            name = "Oslo",
            latitude = 59.9139,
            longitude = 10.7522,
            zone = ZoneId.of("Europe/Oslo"),
            country = "Norway",
        ),
        WeatherLocation(
            name = "Longyearbyen",
            latitude = 78.2232,
            longitude = 15.6267,
            zone = ZoneId.of("Arctic/Longyearbyen"),
            region = "Svalbard",
            country = "Norway",
        ),
        WeatherLocation(
            name = "London",
            latitude = 51.5072,
            longitude = -0.1276,
            zone = ZoneId.of("Europe/London"),
            country = "United Kingdom",
        ),
        WeatherLocation(
            name = "Lisbon",
            latitude = 38.7223,
            longitude = -9.1393,
            zone = ZoneId.of("Europe/Lisbon"),
            country = "Portugal",
        ),
        WeatherLocation(
            name = "Buenos Aires",
            latitude = -34.6037,
            longitude = -58.3816,
            zone = ZoneId.of("America/Argentina/Buenos_Aires"),
            country = "Argentina",
        ),
    )

    val default: WeatherLocation = all.first()
}
