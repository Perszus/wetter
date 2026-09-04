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

    /**
     * The same condition, named for what would actually reach the ground.
     *
     * Providers do publish rain below freezing. Usually it is a coarse grid
     * averaging a valley floor with the ridge above it, sometimes it is a code
     * chosen from precipitation alone - and either way "Rain" over a
     * temperature of minus four is the kind of contradiction that costs a
     * reader their trust in everything else on the screen.
     *
     * It only ever moves one way, from liquid towards frozen. The reverse would
     * be worse and would be wrong more often: a provider saying snow at +3 has
     * usually looked at the depth of the warm layer the flake falls through,
     * which is what actually decides the question and is something this app
     * cannot see. Freezing rain and freezing drizzle are left alone for the
     * same reason - they are liquid below zero on purpose, and that is the
     * whole warning they carry.
     */
    fun appropriateFor(temperatureCelsius: Double?): WeatherCondition {
        if (precipitationKind != PrecipitationKind.RAIN) return this
        return when (PrecipitationKind.likelyAt(temperatureCelsius)) {
            PrecipitationKind.SNOW -> when (this) {
                RAIN -> SNOW
                DRIZZLE -> SNOW_GRAINS
                RAIN_SHOWERS -> SNOW_SHOWERS
                else -> this
            }
            PrecipitationKind.MIXED -> when (this) {
                RAIN, DRIZZLE, RAIN_SHOWERS -> SLEET
                else -> this
            }
            else -> this
        }
    }

    val isPrecipitating: Boolean
        get() = when (this) {
            DRIZZLE, FREEZING_DRIZZLE, RAIN, FREEZING_RAIN, SLEET, SNOW, SNOW_GRAINS,
            RAIN_SHOWERS, SNOW_SHOWERS, THUNDERSTORM, THUNDERSTORM_WITH_HAIL,
            -> true
            CLEAR, MAINLY_CLEAR, PARTLY_CLOUDY, OVERCAST, FOG, UNKNOWN -> false
        }
}
