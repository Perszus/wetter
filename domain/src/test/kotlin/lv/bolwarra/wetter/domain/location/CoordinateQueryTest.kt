package lv.bolwarra.wetter.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateQueryTest {

    private fun parsed(text: String) = CoordinateQuery.parse(text)

    private fun assertPoint(text: String, latitude: Double, longitude: Double) {
        val c = parsed(text)
        assertNotNull("$text did not parse", c)
        assertEquals(text, latitude, c!!.latitude, 1e-4)
        assertEquals(text, longitude, c.longitude, 1e-4)
    }

    @Test
    fun `the forms a map hands you`() {
        assertPoint("56.9496, 24.1052", 56.9496, 24.1052)
        assertPoint("56.9496,24.1052", 56.9496, 24.1052)
        assertPoint("56.9496 24.1052", 56.9496, 24.1052)
        assertPoint("  56.9496 ,  24.1052  ", 56.9496, 24.1052)
    }

    @Test
    fun `signs and hemispheres both work, and mean the same thing`() {
        assertPoint("-33.87, 151.21", -33.87, 151.21)
        assertPoint("33.87S, 151.21E", -33.87, 151.21)
        assertPoint("33.87 S 151.21 E", -33.87, 151.21)
        assertPoint("S33.87 E151.21", -33.87, 151.21)
    }

    @Test
    fun `a hemisphere letter can be the seam`() {
        assertPoint("56.9496N24.1052E", 56.9496, 24.1052)
    }

    @Test
    fun `longitude first is accepted when the letters say so`() {
        // Because somebody will paste it that way, and the letters remove all
        // the ambiguity there is.
        assertPoint("24.1052E, 56.9496N", 56.9496, 24.1052)
    }

    @Test
    fun `degrees minutes seconds`() {
        assertPoint("56°56'58.6\"N 24°06'18.7\"E", 56.9496, 24.1052)
        assertPoint("56°56'58.6\"N, 24°06'18.7\"E", 56.9496, 24.1052)
    }

    @Test
    fun `whole degrees are a point, not a name`() {
        assertPoint("56 24", 56.0, 24.0)
    }

    @Test
    fun `a place name is not a coordinate`() {
        assertNull(parsed("Riga"))
        assertNull(parsed("New York"))
        assertNull(parsed("Springfield, Missouri"))
        // The trap: a name that is mostly hemisphere letters.
        assertNull(parsed("Wes"))
        assertNull(parsed("Sween"))
    }

    @Test
    fun `off the earth is not a place on it`() {
        assertNull(parsed("91.0, 24.0"))
        assertNull(parsed("56.0, 181.0"))
        assertNull(parsed("-90.1, 0"))
    }

    @Test
    fun `the poles and the meridians are on the earth`() {
        assertPoint("90, 180", 90.0, 180.0)
        assertPoint("-90, -180", -90.0, -180.0)
        assertPoint("0, 0", 0.0, 0.0)
    }

    @Test
    fun `saying south twice is not saying it clearly`() {
        // A minus and an S together is either a repetition or a contradiction,
        // and guessing which would be inventing an answer.
        assertNull(parsed("-33.87S, 151.21E"))
    }

    @Test
    fun `two of the same axis is not a pair`() {
        assertNull(parsed("56.9496N, 24.1052N"))
        assertNull(parsed("24.1052E, 56.9496E"))
    }

    @Test
    fun `a point named by its coordinates can be typed back in`() {
        // A pin dropped on a field is named with Coordinates.format, and that
        // label is the only handle the place has. If the search box could not
        // read its own naming back, a kept point would be unfindable the moment
        // it was removed from the list.
        listOf(
            Coordinates(56.9496, 24.1052),
            Coordinates(-33.8688, 151.2093),
            Coordinates(64.1466, -21.9426),
            Coordinates(0.0, 0.0),
        ).forEach { point ->
            val label = point.format()
            val parsed = parsed(label)
            assertNotNull("$label did not parse", parsed)
            assertEquals(label, point.latitude, parsed!!.latitude, 1e-4)
            assertEquals(label, point.longitude, parsed.longitude, 1e-4)
        }
    }

    @Test
    fun `nonsense is nonsense`() {
        assertNull(parsed(""))
        assertNull(parsed("   "))
        assertNull(parsed("56.9496"))
        assertNull(parsed("56.9496, 24.1052, 17"))
        assertNull(parsed("56..9 24.1"))
        assertNull(parsed("56°99'00\"N 24°00'00\"E"))
    }
}
