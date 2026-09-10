package lv.bolwarra.wetter.domain.observation

import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Height, which is the difference between an observation and a reading taken
 * somewhere else.
 *
 * Aerodromes are on flat ground near sea level far more often than towns are,
 * so the station verifying a place is frequently several hundred metres below
 * it. Uncorrected, that gap is a constant temperature offset - and a constant
 * offset is precisely the shape `BiasCorrection` is built to find and subtract,
 * which would make the app confidently wrong at every hill town it learns
 * about.
 */
class ElevationReliabilityTest {

    private val at: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private fun station(
        id: String,
        latitude: Double,
        longitude: Double,
        elevation: Double,
        temperature: Double,
    ) = WeatherObservation(
        station = ObservationStation(
            id = id,
            name = id,
            latitude = latitude,
            longitude = longitude,
            elevationMetres = elevation,
        ),
        at = at,
        temperature = temperature,
        dewPoint = null,
        windSpeed = null,
        windDirection = null,
        pressure = null,
        visibilityMetres = null,
        precipitating = false,
        intensity = null,
    )

    @Test
    fun `the lapse correction runs the right way and at the right rate`() {
        // Up is colder. 6.5 C per km is the standard environmental lapse rate.
        assertEquals(-6.5, LocalEstimate.lapseCorrection(0.0, 1000.0), 0.0001)
        assertEquals(6.5, LocalEstimate.lapseCorrection(1000.0, 0.0), 0.0001)
        assertEquals(0.0, LocalEstimate.lapseCorrection(500.0, 500.0), 0.0001)

        // The sign is the whole thing: getting it backwards would make a
        // mountain warmer than the valley and still look like a correction.
        assertTrue(
            "climbing must cool the reading",
            LocalEstimate.lapseCorrection(100.0, 900.0) < 0.0,
        )
    }

    @Test
    fun `a town above its aerodrome is not simply given the aerodrome's reading`() {
        // The measured case: a hill town four hundred metres above the field
        // that reports for it. Uncorrected this is 2.6 C of pure altitude, which
        // is under the 5 C the bias correction refuses and would therefore be
        // learned as though the model ran warm.
        val airfield = station("LOW", 56.95, 24.11, elevation = 100.0, temperature = 18.0)

        val uncorrected = LocalEstimate.at(
            latitude = 56.96,
            longitude = 24.12,
            elevationMetres = null,
            observations = listOf(airfield),
            at = at,
        )
        val corrected = LocalEstimate.at(
            latitude = 56.96,
            longitude = 24.12,
            elevationMetres = 500.0,
            observations = listOf(airfield),
            at = at,
        )

        assertNotNull(uncorrected)
        assertNotNull(corrected)
        assertEquals(18.0, uncorrected!!.temperature!!, 0.001)
        assertEquals(18.0 - 2.6, corrected!!.temperature!!, 0.001)

        val gap = abs(uncorrected.temperature!! - corrected.temperature!!)
        assertTrue("the correction must be worth making, got $gap", gap > 2.0)
    }

    @Test
    fun `at equal distance, the station at this height is the one believed`() {
        // Distance and height are separate terms and distance is the stronger
        // of the two, which is right - a station a hundred metres away and five
        // hundred metres below is a cliff, not a choice. So the height term is
        // tested where it is actually the deciding one: two stations the same
        // distance away, one of them at this altitude.
        //
        // Both read 20 at their own height. Brought here, the low one becomes
        // 16.8 and the high one stays 20, so an estimate that ignored height
        // would sit halfway between and one that respects it leans high.
        val lowland = station("LOW", 57.05, 24.11, elevation = 10.0, temperature = 20.0)
        val upland = station("HIGH", 56.85, 24.11, elevation = 500.0, temperature = 20.0)

        val estimate = LocalEstimate.at(
            latitude = 56.95,
            longitude = 24.11,
            elevationMetres = 500.0,
            observations = listOf(lowland, upland),
            at = at,
        )
        assertNotNull(estimate)

        val lowBroughtUp = 20.0 + LocalEstimate.lapseCorrection(10.0, 500.0)
        val halfway = (lowBroughtUp + 20.0) / 2.0
        val here = estimate!!.temperature!!

        assertTrue(
            "estimate $here should lean towards the station at this height, past $halfway",
            here > halfway,
        )
        assertTrue("estimate $here should still be below 20", here < 20.0)
    }

    @Test
    fun `a place with no recorded height behaves exactly as before`() {
        // A pin dropped on a map has no elevation, and must not be silently
        // treated as sea level - that would invent a correction rather than skip
        // one.
        val airfield = station("LOW", 56.95, 24.11, elevation = 300.0, temperature = 12.0)
        val estimate = LocalEstimate.at(
            latitude = 56.95,
            longitude = 24.11,
            elevationMetres = null,
            observations = listOf(airfield),
            at = at,
        )
        assertEquals(12.0, estimate!!.temperature!!, 0.001)
    }
}
