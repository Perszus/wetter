package lv.bolwarra.wetter.data.provider.metnorway

import lv.bolwarra.wetter.domain.model.WeatherCondition

/**
 * MET Norway's symbol codes, translated into Wetter's conditions.
 *
 * The codes are compound words — "heavysleetshowersandthunder" — with an
 * optional `_day`, `_night` or `_polartwilight` suffix. Matching on substrings
 * rather than enumerating all ninety-odd spellings is what keeps this readable,
 * but the order of the checks is then load-bearing: "sleetshowers" contains
 * neither "rain" nor "snow", while "rainshowersandthunder" contains "rain",
 * "showers" and "thunder" at once and is a thunderstorm.
 *
 * The day/night suffix is ignored. Wetter derives daylight from the sun's actual
 * position, which is available for every hour rather than only for the hours MET
 * Norway happens to mark, and agrees with itself across providers.
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
