// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.radar

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Where a radar grid sits on the Earth: a window onto the Web Mercator world pixel
 * plane, as the tiles arrive. Mercator stretches distance towards the poles, so
 * motion in pixels is not motion over the ground; [metresPerPixel] converts, given
 * a latitude.
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
     * Where a place falls on the grid, in fractional pixels for the caller to
     * interpolate, or null outside it.
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

        /**
         * A square block of [tiles] tiles whose middle is within half a tile of a place,
         * whether [tiles] is odd or even.
         */
        fun blockAround(latitude: Double, longitude: Double, zoom: Int, tiles: Int): RadarGeometry {
            val n = 1 shl zoom
            val clamped = latitude.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)
            val tileX = (longitude + 180.0) / 360.0 * n
            val tileY = (1.0 - asinh(tan(Math.toRadians(clamped))) / PI) / 2.0 * n
            val firstX = kotlin.math.round(tileX - tiles / 2.0).toInt()
            // Rows do not wrap: past the top or bottom of the map there is no
            // earth, so the block is kept on it.
            val firstY = kotlin.math.round(tileY - tiles / 2.0).toInt()
                .coerceIn(0, (n - tiles).coerceAtLeast(0))
            return ofTileBlock(zoom, firstX, firstY, tiles, tiles)
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
