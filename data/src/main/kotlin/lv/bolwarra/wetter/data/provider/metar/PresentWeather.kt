package lv.bolwarra.wetter.data.provider.metar

import lv.bolwarra.wetter.domain.observation.ObservedIntensity

/**
 * Reads a routine report's present-weather group.
 *
 * A report says `-RA BR` rather than giving a number: light rain, with mist.
 * That is less than a rain gauge would give and it is the right kind of truth
 * for verification - whether it was raining is exactly the question a
 * precipitation forecast is judged on, and it is answered by a human or an
 * instrument at the airport rather than by another model.
 *
 * ### Why absent is not dry
 *
 * An empty present-weather group means nothing significant is happening, which
 * for a staffed station is a genuine report of no precipitation. But the field
 * is also simply missing from some reports, and treating that as "dry" would
 * quietly manufacture clear-weather observations that nobody made - which, fed
 * into verification, would credit forecasts for correctly predicting dry weather
 * that was never confirmed. [precipitationFrom] returns null for an absent
 * group and false only for one that is present and says nothing is falling.
 */
internal object PresentWeather {

    /**
     * Codes for something reaching the ground.
     *
     * `UP` is unidentified precipitation, which an automated station reports when
     * it can tell something is falling but not what. It counts: the question is
     * whether you get wet.
     */
    private val PRECIPITATION = setOf(
        "DZ", // drizzle
        "RA", // rain
        "SN", // snow
        "SG", // snow grains
        "IC", // ice crystals
        "PL", // ice pellets
        "GR", // hail
        "GS", // small hail
        "UP", // unidentified
    )

    /**
     * Qualifiers and descriptors that precede the type, which have to be
     * stripped before the two-letter code can be recognised. `SHRA` is a shower
     * of rain and `TSRA` a thunderstorm with rain; both are rain.
     */
    private val DESCRIPTORS = listOf("MI", "PR", "BC", "DR", "BL", "SH", "TS", "FZ", "PA")

    /** Present but reporting nothing falling - mist, fog, haze, smoke and such. */
    fun precipitationFrom(group: String?): Boolean? {
        if (group == null) return null
        val trimmed = group.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.split(WHITESPACE).any { isPrecipitation(it) }
    }

    /** How hard, from the intensity prefix. Null when nothing is falling. */
    fun intensityFrom(group: String?): ObservedIntensity? {
        val trimmed = group?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val token = trimmed.split(WHITESPACE).firstOrNull { isPrecipitation(it) } ?: return null
        return when {
            token.startsWith("-") -> ObservedIntensity.LIGHT
            token.startsWith("+") -> ObservedIntensity.HEAVY
            // No prefix is the moderate case. It is the absence of a mark, not
            // an omission, which is why this is not null.
            else -> ObservedIntensity.MODERATE
        }
    }

    private fun isPrecipitation(token: String): Boolean {
        var body = token.trim().uppercase()
        // "VC" is "in the vicinity" - visible from the station but not falling
        // on it, which is not an observation of precipitation here.
        if (body.startsWith("VC")) return false
        body = body.removePrefix("-").removePrefix("+")
        DESCRIPTORS.forEach { descriptor ->
            if (body.startsWith(descriptor) && body.length > descriptor.length) {
                body = body.removePrefix(descriptor)
            }
        }
        // A group may pair two types, as in RASN for rain and snow together.
        return body.chunked(CODE_LENGTH).any { it in PRECIPITATION }
    }

    private const val CODE_LENGTH = 2
    private val WHITESPACE = Regex("\\s+")
}
