// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checked against values measured from the live tile service, not against the
 * projection formula restated in another form.
 */
class RadarGeometryTest {

    private val rigaLatitude = 56.95
    private val rigaLongitude = 24.11

    @Test
    fun `a place lands in the tile the service actually serves it from`() {
        // Measured against the RainViewer tile endpoint: Riga at zoom 7 is tile
        // 72,39, and those tiles come back with data over the Baltic.
        assertEquals(72 to 39, RadarGeometry.tileOf(rigaLatitude, rigaLongitude, 7))

        // Two more, so a sign error cannot hide behind one northern-hemisphere
        // eastern-longitude case.
        assertEquals(63 to 42, RadarGeometry.tileOf(51.5, -0.12, 7))
        assertEquals(0 to 0, RadarGeometry.tileOf(85.0, -180.0, 7))
    }

    @Test
    fun `a place inside the grid resolves, one outside does not`() {
        val geometry = RadarGeometry.ofTileBlock(7, 72, 39, 1, 1)
        val inside = geometry.pointOf(rigaLatitude, rigaLongitude)
        assertNotNull(inside)
        assertTrue(inside!!.x in 0f..256f && inside.y in 0f..256f)

        // London is nowhere near this tile.
        assertNull(geometry.pointOf(51.5, -0.12))
    }

    @Test
    fun `a pixel is about two thirds of a kilometre over the Baltic`() {
        // Zoom 7 gives a 32768-pixel world. At 57 north, cos shrinks the ground a
        // pixel covers to roughly 670 m. If this drifts, every speed the nowcast
        // reports drifts with it.
        val geometry = RadarGeometry.ofTileBlock(7, 72, 39, 1, 1)
        val metres = geometry.metresPerPixel(geometry.centreLatitude())
        assertEquals(673.0, metres, 15.0)
    }

    @Test
    fun `Mercator stretch is real and latitude dependent`() {
        // The reason motion in pixels is not motion over the ground. A pixel at
        // the equator covers far more than one near the pole, at the same zoom.
        val geometry = RadarGeometry.ofTileBlock(7, 0, 0, 1, 1)
        val equator = geometry.metresPerPixel(0.0)
        val northern = geometry.metresPerPixel(60.0)

        assertEquals(1223.0, equator, 5.0)
        // cos(60) is exactly a half.
        assertEquals(equator / 2, northern, 5.0)
    }

    @Test
    fun `a row's latitude round trips back to the row`() {
        val geometry = RadarGeometry.ofTileBlock(7, 72, 39, 2, 2)
        for (row in listOf(0, 37, 128, 400, 511)) {
            val latitude = geometry.latitudeAt(row)
            val back = geometry.pointOf(latitude, rigaLongitude)
            assertNotNull("row $row fell off the grid", back)
            assertEquals(row.toFloat(), back!!.y, 0.01f)
        }
    }

    @Test
    fun `a tile block is stitched into one continuous grid`() {
        val single = RadarGeometry.ofTileBlock(7, 72, 39, 1, 1)
        val block = RadarGeometry.ofTileBlock(7, 71, 38, 3, 3)

        assertEquals(256, single.width)
        assertEquals(768, block.width)
        // The middle tile of the block holds the same ground as the single tile,
        // offset by exactly one tile. No seam, no gap.
        val point = block.pointOf(rigaLatitude, rigaLongitude)!!
        val alone = single.pointOf(rigaLatitude, rigaLongitude)!!
        assertEquals(alone.x + 256f, point.x, 0.01f)
        assertEquals(alone.y + 256f, point.y, 0.01f)
    }

    @Test
    fun `the poles are refused rather than wrapped`() {
        val geometry = RadarGeometry.ofTileBlock(7, 0, 0, 1, 1)
        assertNull(geometry.pointOf(89.0, 0.0))
        assertNull(geometry.pointOf(-89.0, 0.0))
    }

    @Test
    fun `an even block is centred on the place, not on its tile`() {
        // Four tiles have no middle tile, only a middle corner. Whichever half
        // of its tile the place falls in, it must end within half a tile of the
        // block's centre - which keeps it well inside the middle three tiles
        // the map shows.
        val places = listOf(
            56.95 to 24.11,
            56.95 to 24.9,
            57.4 to 23.5,
            51.5 to -0.12,
            -33.9 to 151.2,
        )
        for ((latitude, longitude) in places) {
            val block = RadarGeometry.blockAround(latitude, longitude, 7, 4)
            val point = requireNotNull(block.pointOf(latitude, longitude))
            val half = RadarGeometry.TILE_SIZE / 2f
            assertTrue(
                "$latitude,$longitude x ${point.x}",
                kotlin.math.abs(point.x - 512f) <= half,
            )
            assertTrue(
                "$latitude,$longitude y ${point.y}",
                kotlin.math.abs(point.y - 512f) <= half,
            )
        }
    }

    @Test
    fun `a block near the pole stays on the map`() {
        val block = RadarGeometry.blockAround(84.9, 10.0, 7, 4)
        assertEquals(0, block.originY)
    }
}
