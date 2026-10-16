// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.data.provider.metnorway

import lv.bolwarra.wetter.domain.model.WeatherCondition

/**
 * MET Norway's symbol codes, translated into Wetter's conditions. Matched on
 * substrings, so the order of checks matters: "rainshowersandthunder" contains
 * "rain", "showers" and "thunder" and is a thunderstorm. The day/night suffix is
 * ignored; daylight comes from the sun's position.
 */
internal fun weatherConditionFromMetSymbol(symbolCode: String?): WeatherCondition {
    val symbol = symbolCode?.substringBefore('_')?.lowercase() ?: return WeatherCondition.UNKNOWN

    return when {
        symbol.isEmpty() -> WeatherCondition.UNKNOWN

        // Thunder outranks what is falling: it is the part that changes plans.
        symbol.contains("thunder") -> WeatherCondition.THUNDERSTORM

        symbol.contains("sleet") -> WeatherCondition.SLEET

        symbol.contains("snowshowers") -> WeatherCondition.SNOW_SHOWERS
        symbol.contains("snow") -> WeatherCondition.SNOW

        symbol.contains("rainshowers") -> WeatherCondition.RAIN_SHOWERS
        symbol.contains("rain") -> WeatherCondition.RAIN

        symbol == "fog" -> WeatherCondition.FOG
        symbol == "cloudy" -> WeatherCondition.OVERCAST
        symbol == "partlycloudy" -> WeatherCondition.PARTLY_CLOUDY
        // MET Norway's "fair" is a sky with some cloud but mostly clear.
        symbol == "fair" -> WeatherCondition.MAINLY_CLEAR
        symbol == "clearsky" -> WeatherCondition.CLEAR

        else -> WeatherCondition.UNKNOWN
    }
}
