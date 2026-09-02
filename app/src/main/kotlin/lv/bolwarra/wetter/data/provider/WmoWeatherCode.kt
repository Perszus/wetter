package lv.bolwarra.wetter.data.provider

import lv.bolwarra.wetter.domain.model.WeatherCondition

/**
 * WMO code table 4677, as used by Open-Meteo and most models derived from it.
 *
 * This lives in the data layer on purpose: the codes are a wire format, and
 * translating them is a provider's job. Nothing above `data/` should ever see an
 * integer where a condition belongs (docs/design-principles.md).
 *
 * Unmapped codes become [WeatherCondition.UNKNOWN] rather than being guessed at.
 * A wrong icon is worse than an honest blank.
 */
fun weatherConditionFromWmoCode(code: Int?): WeatherCondition = when (code) {
    null -> WeatherCondition.UNKNOWN
    0 -> WeatherCondition.CLEAR
    1 -> WeatherCondition.MAINLY_CLEAR
    2 -> WeatherCondition.PARTLY_CLOUDY
    3 -> WeatherCondition.OVERCAST
    45, 48 -> WeatherCondition.FOG
    51, 53, 55 -> WeatherCondition.DRIZZLE
    56, 57 -> WeatherCondition.FREEZING_DRIZZLE
    61, 63, 65 -> WeatherCondition.RAIN
    66, 67 -> WeatherCondition.FREEZING_RAIN
    71, 73, 75 -> WeatherCondition.SNOW
    77 -> WeatherCondition.SNOW_GRAINS
    80, 81, 82 -> WeatherCondition.RAIN_SHOWERS
    85, 86 -> WeatherCondition.SNOW_SHOWERS
    95 -> WeatherCondition.THUNDERSTORM
    96, 99 -> WeatherCondition.THUNDERSTORM_WITH_HAIL
    else -> WeatherCondition.UNKNOWN
}
