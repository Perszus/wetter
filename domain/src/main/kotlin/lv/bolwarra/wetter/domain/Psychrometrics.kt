package lv.bolwarra.wetter.domain

import kotlin.math.ln

/**
 * Readings derived from the ones a provider actually sends.
 *
 * Dew point is the useful one and almost nobody returns it directly, yet it
 * falls out of temperature and humidity exactly. Deriving it here rather than
 * asking for another field keeps it available from every provider, including the
 * ones that will never offer it, and it stays correct offline because it is not
 * fetched at all.
 */
object Psychrometrics {

    /**
     * The temperature at which the air would be saturated, in degrees Celsius.
     *
     * The Magnus-Tetens approximation, which is good to about a tenth of a
     * degree between roughly -45 and +60 C - far tighter than the humidity
     * reading feeding it, so the approximation is not the limiting error here.
     *
     * Null when humidity is missing or nonsensical. Zero humidity has no dew
     * point at all: the logarithm runs to negative infinity, and air that dry
     * does not exist outdoors anyway.
     */
    fun dewPoint(temperatureC: Double?, relativeHumidityPercent: Int?): Double? {
        if (temperatureC == null || relativeHumidityPercent == null) return null
        if (relativeHumidityPercent <= 0 || relativeHumidityPercent > 100) return null

        val gamma = ln(relativeHumidityPercent / 100.0) +
            (MAGNUS_A * temperatureC) / (MAGNUS_B + temperatureC)
        return MAGNUS_B * gamma / (MAGNUS_A - gamma)
    }

    private const val MAGNUS_A = 17.625
    private const val MAGNUS_B = 243.04
}

/**
 * The compass point a bearing falls in, as one of the eight named directions.
 *
 * Eight rather than sixteen: nobody reads "west-north-west" off a weather screen
 * and does anything different because of it.
 */
enum class CompassPoint {
    NORTH,
    NORTH_EAST,
    EAST,
    SOUTH_EAST,
    SOUTH,
    SOUTH_WEST,
    WEST,
    NORTH_WEST,
    ;

    companion object {
        /**
         * @param degrees clockwise from north, the direction the wind blows
         *   *from*, which is the meteorological convention.
         */
        fun of(degrees: Int): CompassPoint {
            val normalised = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
            // Offset by half a sector so each name is centred on its bearing
            // rather than starting at it: north runs from 337.5, not from 0.
            val sector = ((normalised + HALF_SECTOR) / SECTOR) % entries.size
            return entries[sector]
        }

        private const val FULL_TURN = 360
        private const val SECTOR = 45
        private const val HALF_SECTOR = SECTOR / 2
    }
}
