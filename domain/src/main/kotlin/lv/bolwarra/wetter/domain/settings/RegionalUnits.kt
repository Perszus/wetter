package lv.bolwarra.wetter.domain.settings

/**
 * What units the place a reader first picks is likely to want.
 *
 * A first guess, made once, and overridable in two taps. Somebody in Ohio should
 * not have to go and find Settings before the app says anything they recognise,
 * and somebody in Rīga should never see Fahrenheit at all.
 *
 * ### From the coordinates, not from the address
 *
 * The obvious signal is the country the place is in, and this deliberately does
 * not use it. An address here is decoration: a point is identified by its
 * coordinates and merely *described* by whatever the geocoder says, that
 * geocoder is one volunteer-run instance with no promise of being up, and a pin
 * dropped in the middle of Montana may never get a country name at all. A
 * default that depended on a donated server answering would be metric for some
 * Americans and imperial for none of them, at random.
 *
 * Coordinates are always there, always offline, and answer the only question
 * being asked — which is not "which country is this" but "is this the one place
 * that measures weather in Fahrenheit".
 *
 * ### It is approximate on purpose
 *
 * These are boxes, with a polyline along each long border. Getting it exactly
 * right needs a country polygon set, which is megabytes to decide a default a
 * reader can change in two taps. The two places a straight edge is unacceptably
 * wrong are drawn properly and the rest is left approximate:
 *
 *  - **The Mexican border.** A rectangle over the contiguous states reaches down
 *    to the Florida Keys and takes Monterrey, Chihuahua and Tijuana with it —
 *    several million people given Fahrenheit by a country that has never used
 *    it. So the southern edge follows the border instead.
 *  - **The Canadian border** is the 49th parallel only as far as the Great
 *    Lakes. East of them Canada comes *south*: Toronto is below the latitude of
 *    Detroit and Montréal below that of Seattle, so a box cut at the 49th takes
 *    most of the country's population with it. That edge follows the lakes and
 *    the St Lawrence.
 *
 * The error worth having, where there is one, is metric. Guessing metric for an
 * American is a nuisance and one tap; guessing Fahrenheit for a Canadian is an
 * insult.
 *
 * ### Only the United States is guessed at
 *
 * Liberia and Myanmar are the other two countries that measure weather in
 * Fahrenheit, and neither is here. Both are shapes a box cannot hold without
 * taking a neighbour: a box around Myanmar reaches Bangkok, and one around
 * Liberia reaches western Côte d'Ivoire. Wrongly imperial for Thailand is a far
 * worse answer than metric for Yangon, and the honest rule is the one that can
 * actually be drawn — this guesses the United States and starts everywhere else
 * in metric.
 */
object RegionalUnits {

    /**
     * The units to start with for a place, before anybody has chosen.
     *
     * Metric everywhere except the United States and its territories. See the
     * note on the class for why that list is shorter than the list of countries
     * that use Fahrenheit.
     */
    fun forPoint(latitude: Double, longitude: Double): Preferences =
        if (usesFahrenheit(latitude, longitude)) {
            Preferences(
                temperature = TemperatureUnit.FAHRENHEIT,
                wind = WindUnit.MILES_PER_HOUR,
                precipitation = PrecipitationUnit.INCHES,
            )
        } else {
            // The domain's own units, which is also what both providers publish
            // and what every threshold in the app is written against.
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
     * How far north the United States reaches at a given longitude.
     *
     * The 49th parallel as far as the Great Lakes, and the lakes and the St
     * Lawrence after that. The second half is the part that matters: Toronto sits
     * below the latitude of Detroit and Montréal below that of Seattle, so a flat
     * cut at the 49th would hand most of Canada a Fahrenheit default.
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
     * A polyline read at one longitude, flat past either end.
     *
     * The points are west to east; [beforeWest] and [afterEast] are what the
     * edge becomes outside them, which in both cases here is open water.
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
     * The United States-Mexico border, west to east, as longitude to latitude.
     *
     * Nine points: the two coasts, the Colorado river, the straight run along
     * the Arizona and New Mexico line, El Paso, the bend at Big Bend, and
     * Laredo. Good to a few kilometres, which is far finer than a units default
     * needs to be.
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
     * The states and territories that are not attached, as boxes.
     *
     * Every one is an island or a peninsula with ocean around it, so a box costs
     * nothing here in the way it would have cost a great deal along the Mexican
     * border.
     */
    private val OUTLYING = listOf(
        // Alaska. The western edge stops short of the date line rather than
        // wrapping, which loses the far tip of the Aleutians.
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
