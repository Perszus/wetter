package lv.bolwarra.wetter.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first guess at somebody's units, checked against real places.
 *
 * Written as cities rather than as corners of boxes on purpose. A test that
 * asserts the box is the box it was written as proves nothing; a test that says
 * "Monterrey is metric" is a claim about the world that stays true if the boxes
 * are ever replaced with something better.
 */
class RegionalUnitsTest {

    private fun at(lat: Double, lon: Double) = RegionalUnits.forPoint(lat, lon)

    private val imperial = Preferences(
        temperature = TemperatureUnit.FAHRENHEIT,
        wind = WindUnit.MILES_PER_HOUR,
        precipitation = PrecipitationUnit.INCHES,
    )

    private val metric = Preferences()

    @Test
    fun `american cities start in fahrenheit`() {
        assertEquals(imperial, at(40.71, -74.01)) // New York
        assertEquals(imperial, at(34.05, -118.24)) // Los Angeles
        assertEquals(imperial, at(41.88, -87.63)) // Chicago
        assertEquals(imperial, at(29.76, -95.37)) // Houston
        assertEquals(imperial, at(25.76, -80.19)) // Miami
        assertEquals(imperial, at(47.61, -122.33)) // Seattle
    }

    @Test
    fun `the outlying states and territories too`() {
        assertEquals(imperial, at(61.22, -149.90)) // Anchorage
        assertEquals(imperial, at(21.31, -157.86)) // Honolulu
        assertEquals(imperial, at(18.47, -66.11)) // San Juan
        assertEquals(imperial, at(13.48, 144.75)) // Hagåtña, Guam
    }

    @Test
    fun `mexico is metric, which a rectangle would have got wrong`() {
        // The whole reason the southern edge follows the border. Every one of
        // these sits inside a box drawn from the Florida Keys to the Canadian
        // line, and every one of them is a country that has never used
        // Fahrenheit.
        assertEquals(metric, at(25.69, -100.32)) // Monterrey
        assertEquals(metric, at(28.63, -106.08)) // Chihuahua
        assertEquals(metric, at(32.51, -117.04)) // Tijuana, just south of the line
        assertEquals(metric, at(31.74, -106.49)) // Ciudad Juárez, across from El Paso
        assertEquals(metric, at(25.87, -97.50)) // Matamoros, across from Brownsville
    }

    @Test
    fun `the cities either side of the border get different answers`() {
        // San Diego and Tijuana are seventeen miles apart, and the line between
        // them is the thing being tested.
        assertEquals(imperial, at(32.72, -117.16)) // San Diego
        assertEquals(metric, at(32.51, -117.04)) // Tijuana

        // El Paso and Ciudad Juárez share a river and their centres are three
        // kilometres apart - closer than a nine-point line can resolve, and
        // deliberately not claimed. These are the far sides of each city, which
        // is the resolution this is honestly good to.
        assertEquals(imperial, at(31.87, -106.44)) // north-east El Paso
        assertEquals(metric, at(31.65, -106.42)) // southern Ciudad Juárez
    }

    @Test
    fun `canada is metric`() {
        assertEquals(metric, at(49.28, -123.12)) // Vancouver
        assertEquals(metric, at(43.65, -79.38)) // Toronto
        assertEquals(metric, at(45.50, -73.57)) // Montréal
        assertEquals(metric, at(51.05, -114.07)) // Calgary
    }

    @Test
    fun `europe is metric`() {
        assertEquals(metric, at(56.95, 24.11)) // Rīga
        assertEquals(metric, at(51.51, -0.13)) // London
        assertEquals(metric, at(59.91, 10.75)) // Oslo
        assertEquals(metric, at(38.72, -9.14)) // Lisbon
    }

    @Test
    fun `the rest of the world is metric`() {
        assertEquals(metric, at(-33.87, 151.21)) // Sydney
        assertEquals(metric, at(35.68, 139.69)) // Tokyo
        assertEquals(metric, at(-23.55, -46.63)) // São Paulo
        assertEquals(metric, at(-1.29, 36.82)) // Nairobi
        assertEquals(metric, at(28.61, 77.21)) // Delhi
    }

    @Test
    fun `the other fahrenheit countries are given up rather than guessed at`() {
        // Liberia and Myanmar do use Fahrenheit, and both are shapes a box
        // cannot hold without taking a neighbour: around Myanmar it reaches
        // Bangkok, around Liberia western Côte d'Ivoire. Metric for Yangon is a
        // far better wrong answer than imperial for Thailand, so this is the
        // deliberate limit rather than an oversight.
        assertEquals(metric, at(16.87, 96.20)) // Yangon
        assertEquals(metric, at(6.30, -10.80)) // Monrovia

        assertEquals(metric, at(13.76, 100.50)) // Bangkok, which a box would take
        assertEquals(metric, at(5.36, -4.01)) // Abidjan, likewise
    }

    @Test
    fun `an ocean is metric rather than a crash`() {
        // Nothing stops somebody dropping a pin in the middle of the Atlantic,
        // and the honest answer for a place with no country is the units most of
        // the world reads.
        assertEquals(metric, at(0.0, 0.0))
        assertEquals(metric, at(-40.0, -30.0))
    }
}
