package lv.bolwarra.wetter.domain.provider

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * A latitude/longitude rectangle.
 *
 * docs/providers.md rules out both country lookups and a GIS engine, so this is
 * the whole geographic vocabulary: rectangles, and how far outside one a point
 * falls. It is crude — a box around the Nordics also contains a slice of the
 * Atlantic — but provider coverage genuinely is coarse, and the alternative is
 * shipping a polygon dataset to answer a question that only ever ranks two or
 * three candidates.
 */
data class GeoBox(val south: Double, val north: Double, val west: Double, val east: Double) {
    init {
        require(south <= north) { "south ($south) must not be north of north ($north)" }
        require(south >= -90.0 && north <= 90.0) { "latitudes out of range" }
        require(west >= -180.0 && west <= 180.0 && east >= -180.0 && east <= 180.0) {
            "longitudes out of range"
        }
    }

    /** A box whose west edge is east of its east edge wraps across the 180th meridian. */
    val crossesAntimeridian: Boolean get() = west > east

    fun contains(latitude: Double, longitude: Double): Boolean {
        if (latitude < south || latitude > north) return false
        val lon = normaliseLongitude(longitude)
        return if (crossesAntimeridian) lon >= west || lon <= east else lon in west..east
    }

    /**
     * How far outside the box a point lies, in degrees, or 0.0 when inside.
     *
     * Degrees, not kilometres: this feeds a soft boundary in the scoring, where
     * only the ordering matters, and converting to kilometres would add a
     * latitude-dependent term that changes nothing about which provider wins.
     */
    fun degreesOutside(latitude: Double, longitude: Double): Double {
        if (contains(latitude, longitude)) return 0.0
        val latGap = when {
            latitude < south -> south - latitude
            latitude > north -> latitude - north
            else -> 0.0
        }
        val lon = normaliseLongitude(longitude)
        val lonGap = if (crossesAntimeridian) {
            min(angularGap(lon, west), angularGap(lon, east))
        } else {
            when {
                lon < west -> min(west - lon, angularGap(lon, east))
                lon > east -> min(lon - east, angularGap(lon, west))
                else -> 0.0
            }
        }
        return hypot(latGap, lonGap)
    }

    private fun angularGap(a: Double, b: Double): Double {
        val raw = abs(a - b)
        return min(raw, 360.0 - raw)
    }

    private fun normaliseLongitude(longitude: Double): Double {
        var lon = longitude % 360.0
        if (lon > 180.0) lon -= 360.0
        if (lon < -180.0) lon += 360.0
        return lon
    }
}

/**
 * A named area where a provider is particularly worth using.
 *
 * [strength] is the provider's own claim about that area, between 0 and 1 —
 * 1.0 for the model's home ground, lower for the fringes where it still has an
 * edge but a smaller one.
 */
data class ProviderRegion(
    val name: String,
    val box: GeoBox,
    val strength: Double = 1.0,
    /**
     * Grid spacing of the model that actually runs here, when it is finer than
     * the provider's baseline.
     *
     * This is why regional providers exist. MET Norway runs a 2.5 km Nordic
     * model over Scandinavia and falls back to a global model everywhere else —
     * so a single resolution figure on the provider would either flatter it
     * abroad or understate it at home, and the selector would rank on a number
     * that is true in neither place.
     */
    val resolutionKm: Double? = null,
) {
    init {
        require(strength in 0.0..1.0) { "strength must be within 0..1, was $strength" }
    }
}

/**
 * Where a provider answers, and where it answers best.
 *
 * A global provider answers everywhere; its preferred regions, if any, are where
 * it should be picked over another global provider. A non-global provider serves
 * only inside its regions and is not a candidate elsewhere.
 */
data class ProviderCoverage(
    val isGlobal: Boolean,
    val preferredRegions: List<ProviderRegion> = emptyList(),
) {
    fun serves(latitude: Double, longitude: Double): Boolean =
        isGlobal || preferredRegions.any { it.box.contains(latitude, longitude) }

    /**
     * The strongest regional claim at this point, decayed to zero over
     * [GRACE_DEGREES] beyond a region's edge.
     *
     * The decay is what stops a provider changing the instant a border is
     * crossed. Weather does not respect the edge of a rectangle, and neither
     * should a forecast someone is watching while they walk down a street
     * (docs/providers.md, boundary conditions).
     */
    fun regionalStrength(latitude: Double, longitude: Double): Double =
        preferredRegions.maxOfOrNull { region ->
            val outside = region.box.degreesOutside(latitude, longitude)
            if (outside >= GRACE_DEGREES) {
                0.0
            } else {
                region.strength * (1.0 - outside / GRACE_DEGREES)
            }
        } ?: 0.0

    /** The name of the region making that claim, for the score's explanation. */
    fun strongestRegionName(latitude: Double, longitude: Double): String? = preferredRegions
        .filter { it.box.degreesOutside(latitude, longitude) < GRACE_DEGREES }
        .maxByOrNull { region ->
            val outside = region.box.degreesOutside(latitude, longitude)
            region.strength * (1.0 - outside / GRACE_DEGREES)
        }
        ?.name

    /**
     * The finest resolution available at this point: a region's own figure where
     * one applies, otherwise the provider's baseline.
     *
     * Only regions that genuinely contain the point count. A decayed regional
     * preference reaching across a border does not mean the fine-grid model
     * reaches with it.
     */
    fun resolutionKmAt(latitude: Double, longitude: Double, baseline: Double?): Double? =
        preferredRegions
            .filter { it.box.contains(latitude, longitude) }
            .mapNotNull { it.resolutionKm }
            .minOrNull()
            ?: baseline

    companion object {
        /**
         * How far a regional preference reaches past its own boundary before it
         * has faded to nothing. Two degrees is roughly 220 km north-south.
         */
        const val GRACE_DEGREES = 2.0
    }
}
