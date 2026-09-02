package lv.bolwarra.wetter.domain.provider

/**
 * The things a forecast can contain, as a closed set.
 *
 * A set of these rather than a row of booleans, because the question the
 * selector actually asks is a set operation — "which of the variables Wetter
 * needs can this provider supply?" — and with booleans that question becomes ten
 * hand-written comparisons that have to be edited every time a variable is
 * added.
 */
enum class WeatherVariable {
    HOURLY,
    DAILY,
    PRECIPITATION,
    PRECIPITATION_PROBABILITY,
    SNOWFALL,
    WIND,
    CLOUD_COVER,
    HUMIDITY,
    PRESSURE,
    SUNRISE_SUNSET,
}

/**
 * What Wetter asks of a provider.
 *
 * The split matters. A provider missing something [required] is not a candidate
 * at all; a provider missing something [desired] merely scores lower. Wetter's
 * subject is precipitation timing, so hourly precipitation is required and
 * everything else — probability included — is a preference. Requiring
 * probability would silently exclude MET Norway across most of its coverage,
 * which is the opposite of what a multi-provider system is for.
 */
data class ForecastRequirements(
    val required: Set<WeatherVariable> = setOf(
        WeatherVariable.HOURLY,
        WeatherVariable.PRECIPITATION,
    ),
    val desired: Set<WeatherVariable> = setOf(
        WeatherVariable.DAILY,
        WeatherVariable.PRECIPITATION_PROBABILITY,
        WeatherVariable.SNOWFALL,
        WeatherVariable.WIND,
        WeatherVariable.CLOUD_COVER,
        WeatherVariable.SUNRISE_SUNSET,
    ),
    val minimumForecastDays: Int = 3,
) {
    companion object {
        /** What the weather screen needs to draw itself. */
        val Default = ForecastRequirements()
    }
}
