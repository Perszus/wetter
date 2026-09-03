package lv.bolwarra.wetter.domain.radar

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Where a radar grid sits on the Earth.
 *
 * Radar reaches us as square map tiles, so the grid inherits Web Mercator
 * whether or not that is the projection anyone would choose for meteorology. It
 * is not a neutral choice: Mercator stretches ground distance towards the poles,
 * so a pixel over Riga covers noticeably less ground than one over Rome. Motion
 * measured in pixels is therefore not motion over the ground, and treating the
 * two as the same is how a nowcast quietly acquires a latitude-dependent speed
 * error. [metresPerPixel] is the conversion, and it takes a latitude for exactly
 * that reason.
 *
 * The grid is a window onto the world pixel plane rather than a list of tiles.
 * Once the tiles are stitched their boundaries mean nothing, and carrying them
 * around would put a seam through the middle of every calculation spanning one.
 */
data class RadarGeometry(
    val zoom: Int,
    /** World-pixel coordinate of grid column 0 at this zoom. */
    val originX: Int,
    /** World-pixel coordinate of grid row 0. */
    val originY: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(zoom in 0..MAX_ZOOM) { "zoom out of range: $zoom" }
        require(width > 0 && height > 0) { "empty grid" }
    }

    private val worldSize: Double get() = TILE_SIZE.toDouble() * (1 shl zoom)

    /**
     * Where a place falls on the grid, in fractional pixels, or null when it
     * falls outside.
     *
     * Fractional on purpose: the caller interpolates. Rounding to whole pixels
     * here would quantise every sample to about a kilometre, the same order as
     * the features being tracked.
     */
    fun pointOf(latitude: Double, longitude: Double): GridPoint? {
        if (latitude < -MERCATOR_LIMIT || latitude > MERCATOR_LIMIT) return null
        val worldX = (longitude + 180.0) / 360.0 * worldSize
        val worldY = (1.0 - asinh(tan(Math.toRadians(latitude))) / PI) / 2.0 * worldSize
        val x = (worldX - originX).toFloat()
        val y = (worldY - originY).toFloat()
        return GridPoint(x, y).takeIf { x >= 0f && y >= 0f && x < width && y < height }
    }

    /** The latitude of a grid row, needed to turn pixel motion into ground speed. */
    fun latitudeAt(row: Int): Double {
        val worldY = (originY + row).toDouble()
        return Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY / worldSize))))
    }

    /** The latitude through the middle of the grid. */
    fun centreLatitude(): Double = latitudeAt(height / 2)

    /**
     * Ground metres one pixel spans at a latitude.
     *
     * Mercator is conformal, so this is the same in both axes at a given
     * latitude - the one convenience the projection offers here.
     */
    fun metresPerPixel(latitude: Double): Double =
        EQUATOR_METRES * cos(Math.toRadians(latitude)) / worldSize

    companion object {
        const val TILE_SIZE = 256
        const val MAX_ZOOM = 22

        /** Web Mercator cannot represent the poles; this is its usual cutoff. */
        const val MERCATOR_LIMIT = 85.05112878

        private const val EQUATOR_METRES = 40_075_016.686

        /** The tile column and row containing a place, for deciding what to fetch. */
        fun tileOf(latitude: Double, longitude: Double, zoom: Int): Pair<Int, Int> {
            val n = (1 shl zoom).toDouble()
            val clamped = latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)
            val x = floor((longitude + 180.0) / 360.0 * n).toInt()
            val y = floor((1.0 - asinh(tan(Math.toRadians(clamped))) / PI) / 2.0 * n).toInt()
            return x to y
        }

        /** The geometry of a block of tiles, once stitched into one grid. */
        fun ofTileBlock(zoom: Int, tileX: Int, tileY: Int, tilesAcross: Int, tilesDown: Int) =
            RadarGeometry(
                zoom = zoom,
                originX = tileX * TILE_SIZE,
                originY = tileY * TILE_SIZE,
                width = tilesAcross * TILE_SIZE,
                height = tilesDown * TILE_SIZE,
            )
    }
}

/** A position on the grid, in fractional pixels from its top-left corner. */
data class GridPoint(val x: Float, val y: Float)
