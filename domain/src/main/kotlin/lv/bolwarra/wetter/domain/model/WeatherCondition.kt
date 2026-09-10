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

    /**
     * The same condition, named for how hard it is actually falling.
     *
     * ### One source of truth for intensity
     *
     * A provider's symbol carries two different claims in one word: *what* is
     * falling, and *how hard*. The first is theirs to make. The second this app
     * measures for itself, against a published scale, and the two were being
     * displayed side by side without ever being reconciled.
     *
     * Measured on a real MET Norway forecast for Rīga: the 20th had a peak of
     * 1.0 mm/h and came back as `DRIZZLE`, while the app's own scale calls
     * anything from 0.5 mm/h light rain. So the week showed a drizzle mark on a
     * day the bar underneath called rain, and on another day the reverse. Both
     * surfaces were reading the same forecast and neither was wrong on its own
     * terms - there simply was no single answer to appeal to.
     *
     * This is the counterpart to [appropriateFor], which does exactly the same
     * job for the other half of the word: the temperature decides rain or snow,
     * and the rate decides drizzle or rain. Between them the symbol keeps what
     * only it knows - the sky, the character of the fall, the hazard - and
     * everything the app can measure, the app measures.
     *
     * ### What is deliberately left alone
     *
     * Only the two pairs that are genuinely the same thing at two strengths get
     * renamed: drizzle against rain, and snow grains against snow.
     *
     * Showers stay showers, because that is a claim about the fall being
     * intermittent rather than about how hard it is, and a rate cannot see it.
     * Thunderstorms stay thunderstorms. Freezing drizzle and freezing rain stay
     * as reported even though they are an intensity pair, because the difference
     * between them is a hazard rather than a word - `Hazards` treats freezing
     * rain as a danger and freezing drizzle as a warning - and a provider that
     * has said which one it is knows more about it than a millimetre count does.
     *
     * @param millimetresPerHour the measured rate, or null when nothing was
     *   measured. Null leaves the symbol exactly as it came: an absent
     *   measurement is not evidence of a light one.
     */
    fun atRate(millimetresPerHour: Double?): WeatherCondition {
        if (millimetresPerHour == null || !isPrecipitating) return this
        val worthNaming = PrecipitationIntensity.ofRate(millimetresPerHour).isWorthNaming
        return when (this) {
            DRIZZLE, RAIN -> if (worthNaming) RAIN else DRIZZLE
            SNOW_GRAINS, SNOW -> if (worthNaming) SNOW else SNOW_GRAINS
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
