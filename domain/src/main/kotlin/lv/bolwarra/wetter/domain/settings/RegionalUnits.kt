// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.settings

/**
 * What units the place a reader first picks is likely to want: a first guess,
 * made once, and changeable in settings.
 *
 * Decided from the coordinates, not the geocoder's country: coordinates are
 * always present and offline, and the only question is whether this is the one
 * place that measures weather in Fahrenheit.
 *
 * Approximate: boxes, with polylines along the two borders where a
 * straight edge would be badly wrong. The Mexican border is followed so northern
 * Mexico stays metric, and the Canadian border follows the Great Lakes and the St
 * Lawrence, since Toronto and Montréal lie south of the 49th parallel. Where it
 * errs, it errs metric.
 *
 * Only the United States is guessed at. Liberia and Myanmar also use Fahrenheit,
 * but neither fits a box without taking a neighbour, and imperial for Thailand
 * would be worse than metric for Yangon.
 */
object RegionalUnits {

    /**
     * The units to start with for a place: metric everywhere except the United States
     * and its territories.
     */
    fun forPoint(latitude: Double, longitude: Double): Preferences =
        if (usesFahrenheit(latitude, longitude)) {
            Preferences(
                temperature = TemperatureUnit.FAHRENHEIT,
                wind = WindUnit.MILES_PER_HOUR,
                precipitation = PrecipitationUnit.INCHES,
            )
        } else {
            // The domain's own units, which both providers publish and every threshold is
            // written against.
            Preferences()
        }

    private fun usesFahrenheit(lat: Double, lon: Double): Boolean =
        inContiguousStates(lat, lon) || OUTLYING.any { it.holds(lat, lon) }

    /** The lower forty-eight, both long edges following the real border. */
    private fun inContiguousStates(lat: Double, lon: Double): Boolean {
        if (lon < PACIFIC || lon > ATLANTIC) return false
        return lat >= southernEdgeAt(lon) && lat <= northernEdgeAt(lon)
    }

    /**
     * How far north the United States reaches at a given longitude: the 49th parallel
     * to the Great Lakes, then the lakes and the St Lawrence.
     */
    private fun northernEdgeAt(lon: Double): Double = interpolate(NORTHERN, lon, CANADA, MAINE)

    /**
     * How far south the United States reaches at a given longitude.
     *
     * The border west of the Gulf, linearly between the points below; east of
     * the Gulf the limit is the Florida Keys, and everything between is water.
     */
    private fun southernEdgeAt(lon: Double): Double =
        interpolate(SOUTHERN, lon, SOUTHERN.first().second, KEYS)

    /**
     * A polyline read at one longitude. The points run west to east; [beforeWest] and
     * [afterEast] are the edge outside them, open water in both cases here.
     */
    private fun interpolate(
        line: List<Pair<Double, Double>>,
        lon: Double,
        beforeWest: Double,
        afterEast: Double,
    ): Double {
        if (lon <= line.first().first) return beforeWest
        if (lon >= line.last().first) return afterEast

        val next = line.indexOfFirst { it.first >= lon }
        val (westLon, westLat) = line[next - 1]
        val (eastLon, eastLat) = line[next]
        val across = (lon - westLon) / (eastLon - westLon)
        return westLat + (eastLat - westLat) * across
    }

    /** The 49th parallel, which is the border from the Pacific to the lakes. */
    private const val CANADA = 49.0

    /** The Atlantic end, off the Maine coast. */
    private const val MAINE = 45.2

    private const val PACIFIC = -125.0
    private const val ATLANTIC = -66.9

    /** Key West, the southernmost point of the contiguous states. */
    private const val KEYS = 24.5

    /**
     * The United States-Mexico border, west to east, as longitude to latitude: good
     * to a few kilometres.
     */
    private val SOUTHERN = listOf(
        -117.13 to 32.53,
        -114.72 to 32.72,
        -111.07 to 31.33,
        -108.21 to 31.33,
        -106.47 to 31.78,
        -103.00 to 29.00,
        -101.40 to 29.77,
        -99.10 to 26.40,
        -97.14 to 25.96,
    )

    /**
     * The northern border, west to east, as longitude to latitude.
     *
     * Flat at the 49th until the Lake of the Woods, then down through Superior,
     * Huron and Erie, along Ontario and the St Lawrence, the straight 45th across
     * Vermont and New Hampshire, and up around northern Maine.
     */
    private val NORTHERN = listOf(
        -95.15 to 49.38,
        -94.80 to 48.70,
        -89.50 to 48.00,
        -84.50 to 46.50,
        -82.40 to 43.00,
        -79.00 to 43.30,
        -76.50 to 44.20,
        -71.50 to 45.00,
        -69.20 to 47.40,
        -67.00 to 45.20,
    )

    /**
     * The states and territories that are not attached, as boxes, each with ocean
     * around it.
     */
    private val OUTLYING = listOf(
        // Alaska. The western edge stops short of the date line, losing the far tip of
        // the Aleutians.
        Box(51.0, 71.5, -170.0, -129.9),
        // Hawaii.
        Box(18.9, 22.3, -160.3, -154.7),
        // Puerto Rico and the US Virgin Islands.
        Box(17.6, 18.6, -67.4, -64.5),
        // Guam and the Northern Marianas.
        Box(13.2, 20.6, 144.5, 146.2),
        // American Samoa.
        Box(-14.6, -14.1, -171.1, -169.4),
    )

    private data class Box(
        val south: Double,
        val north: Double,
        val west: Double,
        val east: Double,
    ) {
        fun holds(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
    }
}
