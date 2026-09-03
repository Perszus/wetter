package lv.bolwarra.wetter.domain.observation

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stations are the real ones around Riga, with their real positions and heights,
 * because the whole difficulty here is that they disagree for structural reasons
 * - height especially - rather than at random.
 */
class LocalEstimateTest {

    private val now: Instant = Instant.parse("2026-09-03T07:00:00Z")

    // Riga itself: near the airport, essentially at sea level.
    private val rigaLat = 56.95
    private val rigaLon = 24.11
    private val rigaElevation = 10.0

    private fun station(id: String, lat: Double, lon: Double, elevation: Double) =
        ObservationStation(id, id, lat, lon, elevation)

    private val evra = station("EVRA", 56.924, 23.968, 7.0)
    private val evga = station("EVGA", 56.78, 24.85, 61.0)
    private val eysa = station("EYSA", 55.89, 23.39, 135.0)
    private val eetu = station("EETU", 58.31, 26.69, 67.0)

    private fun report(
        station: ObservationStation,
        temperature: Double? = 16.0,
        ageMinutes: Long = 5,
        precipitating: Boolean? = false,
        dewPoint: Double? = 12.0,
        wind: Double? = 4.0,
        pressure: Double? = 1012.0,
    ) = WeatherObservation(
        station = station,
        at = now.minus(Duration.ofMinutes(ageMinutes)),
        temperature = temperature,
        dewPoint = dewPoint,
        windSpeed = wind,
        windDirection = 220,
        pressure = pressure,
        visibilityMetres = 9999.0,
        precipitating = precipitating,
        intensity = null,
    )

    @Test
    fun `no usable stations means no estimate rather than a guess`() {
        assertNull(LocalEstimate.at(rigaLat, rigaLon, rigaElevation, emptyList(), now))

        // Far too far away to be describing this weather.
        val distant = report(station("XXXX", 40.0, 0.0, 5.0))
        assertNull(LocalEstimate.at(rigaLat, rigaLon, rigaElevation, listOf(distant), now))
    }

    @Test
    fun `the nearest station dominates but does not decide alone`() {
        // EVRA is 16 km away, EYSA 142 km. Both count; the near one carries the
        // answer without the far one being ignored.
        val estimate = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            // The dew point has to come down with the temperature: leaving it at
            // the default would make the reading impossible and quality control
            // would rightly discard the station.
            listOf(
                report(evra, temperature = 16.0),
                report(eysa, temperature = 10.0, dewPoint = 8.0),
            ),
            now,
        )!!
        assertEquals(2, estimate.stations)
        assertTrue("was ${estimate.temperature}", estimate.temperature!! in 15.5..16.0)
    }

    @Test
    fun `a station beyond the useful radius is not consulted at all`() {
        // EETU is over 300 km away, across the Gulf. Whatever it is measuring,
        // it is not this weather.
        assertNull(
            LocalEstimate.at(rigaLat, rigaLon, rigaElevation, listOf(report(eetu)), now),
        )
    }

    @Test
    fun `a station high above the target is corrected down to it`() {
        // EYSA sits at 135 m. Reading 16 there means it is warmer at sea level,
        // so an uncorrected average would run the city cold.
        val corrected = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(report(eysa, temperature = 16.0)),
            now,
        )!!
        assertTrue("was ${corrected.temperature}", corrected.temperature!! > 16.0)

        // 125 m at 6.5 C/km is about 0.8 C.
        assertEquals(16.81, corrected.temperature!!, 0.05)
    }

    @Test
    fun `with no known height nothing is corrected`() {
        // Better than correcting to a height that was guessed.
        val estimate = LocalEstimate.at(
            rigaLat,
            rigaLon,
            null,
            listOf(report(eysa, temperature = 16.0)),
            now,
        )!!
        assertEquals(16.0, estimate.temperature!!, 0.001)
    }

    @Test
    fun `the lapse correction runs the right way and scales with height`() {
        // Bringing a reading down from height makes it warmer.
        assertEquals(0.65, LocalEstimate.lapseCorrection(fromMetres = 100.0, toMetres = 0.0), 0.001)
        // And up from sea level makes it cooler.
        assertEquals(
            -0.65,
            LocalEstimate.lapseCorrection(fromMetres = 0.0, toMetres = 100.0),
            0.001,
        )
        assertEquals(0.0, LocalEstimate.lapseCorrection(fromMetres = 50.0, toMetres = 50.0), 0.001)
    }

    @Test
    fun `a station at a similar height outweighs a closer one at a different height`() {
        // The reason the nearest station is the wrong answer. A hilltop next
        // door is a worse guide to a coastal city than a coast further off.
        val hilltopNearby = station("HILL", 56.96, 24.13, 400.0)
        val coastFurther = station("COAST", 56.5, 23.5, 8.0)

        val estimate = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(
                // Both report the same raw number; the hilltop's means something
                // very different once brought to sea level.
                report(hilltopNearby, temperature = 12.0),
                report(coastFurther, temperature = 16.0),
            ),
            now,
        )!!
        // The hilltop corrects to about 14.5 at sea level, so it still pulls the
        // answer down - but it must not run away with it.
        assertTrue("was ${estimate.temperature}", estimate.temperature!! > 14.0)
    }

    @Test
    fun `a broken sensor is thrown out, not averaged in`() {
        val broken = report(evga, temperature = -70.0)
        val good = report(evra, temperature = 16.0)

        val estimate = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(good, broken),
            now,
        )!!
        assertEquals(1, estimate.stations)
        assertEquals(16.0, estimate.temperature!!, 0.2)
    }

    @Test
    fun `a dew point above the air temperature is impossible and rejected`() {
        val impossible = report(evra, temperature = 10.0, dewPoint = 18.0)
        assertTrue(!impossible.isPlausible())

        // Saturated air rounds to equal, which is fine.
        assertTrue(report(evra, temperature = 15.0, dewPoint = 15.0).isPlausible())
    }

    @Test
    fun `a stale report is not used to describe now`() {
        val stale = report(evra, ageMinutes = 200)
        assertNull(LocalEstimate.at(rigaLat, rigaLon, rigaElevation, listOf(stale), now))
    }

    @Test
    fun `confidence rises with more, nearer and fresher stations`() {
        val lone = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(report(eysa, ageMinutes = 100)),
            now,
        )!!
        val several = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(report(evra), report(evga), report(eysa)),
            now,
        )!!
        assertTrue(
            "lone ${lone.confidence} vs several ${several.confidence}",
            several.confidence > lone.confidence * 2,
        )
    }

    @Test
    fun `the wet share is what the stations report, not a forecast`() {
        val estimate = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(
                report(evra, precipitating = true),
                report(evga, precipitating = false),
            ),
            now,
        )!!
        // EVRA is far closer, so it carries most of the weight - the share is
        // weighted like everything else, not a raw head count.
        assertTrue("was ${estimate.precipitatingShare}", estimate.precipitatingShare!! > 0.9)
    }

    @Test
    fun `stations saying nothing about rain leave the share unknown`() {
        val estimate = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(report(evra, precipitating = null)),
            now,
        )!!
        assertNull(estimate.precipitatingShare)
    }

    @Test
    fun `a sensor stuck on one value is detectable`() {
        val stuck = List(10) { report(evra, temperature = 16.0, ageMinutes = it * 30L) }
        assertTrue(stuck.hasStuckTemperature())

        val moving = List(10) { report(evra, temperature = 16.0 + it * 0.3, ageMinutes = it * 30L) }
        assertTrue(!moving.hasStuckTemperature())
    }

    @Test
    fun `pressure is not corrected for height`() {
        // Reports already reduce it to sea level; correcting again would apply
        // the reduction twice.
        val estimate = LocalEstimate.at(
            rigaLat,
            rigaLon,
            rigaElevation,
            listOf(report(eysa, pressure = 1014.0)),
            now,
        )!!
        assertEquals(1014.0, estimate.pressure!!, 0.001)
    }
}
