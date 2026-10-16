// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.location

import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * A short list of places offered before anybody has kept one, and the place the
 * app opens on. Spread across the Nordic fine grid, the rest of Europe, far from
 * any regional provider, above the Arctic Circle and south of the equator.
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
