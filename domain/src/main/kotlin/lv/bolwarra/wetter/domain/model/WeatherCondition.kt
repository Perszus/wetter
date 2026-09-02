package lv.bolwarra.wetter.domain.model

/**
 * The sky, as a closed set the UI can exhaustively handle.
 *
 * Providers report the sky as an integer code — WMO table 4677 for most of
 * them. That integer is a wire format, and letting it reach a composable would
 * mean every screen carrying its own mapping table and a silent `else` branch.
 * Translating codes is a provider's job (see WeatherProvider).
 */
enum class WeatherCondition {
    CLEAR,
    MAINLY_CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    FOG,
    DRIZZLE,
    FREEZING_DRIZZLE,
    RAIN,
    FREEZING_RAIN,

    /**
     * Rain and snow falling together. WMO code table 4677 has no entry for it, so
     * Open-Meteo cannot report it — but MET Norway can, and in a Baltic winter it
     * is most of what actually falls. A condition no provider could ever produce
     * would be dead weight; this one is the opposite.
     */
    SLEET,
    SNOW,
    SNOW_GRAINS,
    RAIN_SHOWERS,
    SNOW_SHOWERS,
    THUNDERSTORM,
    THUNDERSTORM_WITH_HAIL,

    /** A code the provider sent that this version does not recognise. */
    UNKNOWN,

    ;

    /**
     * What this condition implies is falling, for providers that report an
     * amount without saying what it was made of.
     */
    val precipitationKind: PrecipitationKind
        get() = when (this) {
            SNOW, SNOW_GRAINS, SNOW_SHOWERS -> PrecipitationKind.SNOW
            SLEET -> PrecipitationKind.MIXED
            DRIZZLE, FREEZING_DRIZZLE, RAIN, FREEZING_RAIN, RAIN_SHOWERS,
            THUNDERSTORM, THUNDERSTORM_WITH_HAIL,
            -> PrecipitationKind.RAIN
            CLEAR, MAINLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, UNKNOWN ->
                PrecipitationKind.NONE
        }

    val isPrecipitating: Boolean
        get() = when (this) {
            DRIZZLE, FREEZING_DRIZZLE, RAIN, FREEZING_RAIN, SLEET, SNOW, SNOW_GRAINS,
            RAIN_SHOWERS, SNOW_SHOWERS, THUNDERSTORM, THUNDERSTORM_WITH_HAIL,
            -> true
            CLEAR, MAINLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, UNKNOWN -> false
        }
}
