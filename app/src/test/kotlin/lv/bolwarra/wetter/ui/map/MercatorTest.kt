package lv.bolwarra.wetter.ui.map

import androidx.compose.ui.unit.IntSize
import lv.bolwarra.wetter.domain.location.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic a dropped pin depends on.
 *
 * The map exists to turn a place on screen into a pair of numbers, so the round
 * trip is the feature: whatever the viewport is centred on has to come back as
 * the coordinate that would put it there again.
 */
class MercatorTest {

    private val places = mapOf(
        "Riga" to Coordinates(56.9496, 24.1052),
        "Sydney" to Coordinates(-33.8688, 151.2093),
        "Quito" to Coordinates(-0.1807, -78.4678),
        "Reykjavik" to Coordinates(64.1466, -21.9426),
        "null island" to Coordinates(0.0, 0.0),
        "the antimeridian" to Coordinates(0.0, 179.9),
    )

    @Test
    fun `a point survives the round trip at every zoom worth using`() {
        for (zoom in 3..17) {
            places.forEach { (name, at) ->
                val back = Mercator.coordinatesOf(Mercator.worldOf(at, zoom), zoom)
                // One world pixel is the floor on precision, and it shrinks as
                // the zoom climbs; a tenth of a degree is loose enough for zoom
                // three and still far tighter than any forecast grid.
                assertEquals("$name latitude at z$zoom", at.latitude, back.latitude, 0.1)
                assertEquals("$name longitude at z$zoom", at.longitude, back.longitude, 0.1)
            }
        }
    }

    @Test
    fun `precision at the zoom the picker opens at is better than the radar's own pixel`() {
        val at = Coordinates(56.9496, 24.1052)
        val back = Mercator.coordinatesOf(Mercator.worldOf(at, 13), 13)
        // Radar is sampled at roughly 670 m; a pin that could not be placed
        // more precisely than that would not be worth the map.
        assertEquals(at.latitude, back.latitude, 0.001)
        assertEquals(at.longitude, back.longitude, 0.001)
    }

    @Test
    fun `north is up`() {
        val north = Mercator.worldOf(Coordinates(60.0, 0.0), 10)
        val south = Mercator.worldOf(Coordinates(50.0, 0.0), 10)
        assertTrue("further north is further up the plane", north.y < south.y)
    }

    @Test
    fun `only the tiles on screen are ever asked for`() {
        // The rule the tile policy actually imposes: no pre-emptive fetching.
        // A viewport exactly one tile across must want exactly the tiles it
        // covers, never a ring around them to make panning smoother.
        val zoom = 10
        val centre = Mercator.worldOf(Coordinates(56.9496, 24.1052), zoom)
        val keys = visibleTiles(centre, zoom, IntSize(TILE, TILE))

        assertTrue("a one-tile viewport spans at most four tiles, was ${keys.size}", keys.size <= 4)
        keys.forEach { assertEquals(zoom, it.zoom) }
    }

    @Test
    fun `a wider viewport asks for more, and in proportion`() {
        val zoom = 10
        val centre = Mercator.worldOf(Coordinates(56.9496, 24.1052), zoom)
        val small = visibleTiles(centre, zoom, IntSize(TILE, TILE)).size
        val large = visibleTiles(centre, zoom, IntSize(TILE * 3, TILE * 3)).size

        assertTrue("three tiles across should want more than one", large > small)
        assertTrue("and no more than sixteen, was $large", large <= 16)
    }

    @Test
    fun `columns wrap round the world and rows do not`() {
        // Longitude is a cylinder and latitude is not: a column off one edge is
        // a real column on the other, while a row off the top is not earth.
        val zoom = 4
        val span = 1 shl zoom
        val edge = Mercator.worldOf(Coordinates(0.0, 179.99), zoom)
        val keys = visibleTiles(edge, zoom, IntSize(TILE * 2, TILE))

        keys.forEach {
            assertTrue("column ${it.x} is on the map", it.x in 0 until span)
            assertTrue("row ${it.y} is on the map", it.y in 0 until span)
        }
    }
}
