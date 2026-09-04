package lv.bolwarra.wetter.domain.air

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirQualityTest {

    private fun reading(pm25: Double?, average: Double? = null) = AirQuality(
        observedAt = Instant.parse("2026-09-04T03:00:00Z"),
        pm25 = pm25,
        pm25Average = average,
        pm10 = null,
        ozone = null,
        nitrogenDioxide = null,
    )

    @Test
    fun `the WHO guideline is the top of good, not the bottom of fair`() {
        assertEquals(AirQualityBand.GOOD, AirQualityBand.of(AirQualityBand.GUIDELINE_PM25))
        assertEquals(AirQualityBand.FAIR, AirQualityBand.of(AirQualityBand.GUIDELINE_PM25 + 0.1))
    }

    @Test
    fun `each interim target opens the band below it`() {
        assertEquals(AirQualityBand.FAIR, AirQualityBand.of(AirQualityBand.FAIR_PM25))
        assertEquals(AirQualityBand.MODERATE, AirQualityBand.of(AirQualityBand.MODERATE_PM25))
        assertEquals(AirQualityBand.POOR, AirQualityBand.of(AirQualityBand.POOR_PM25))
        assertEquals(AirQualityBand.VERY_POOR, AirQualityBand.of(AirQualityBand.VERY_POOR_PM25))
        assertEquals(
            AirQualityBand.EXTREMELY_POOR,
            AirQualityBand.of(AirQualityBand.VERY_POOR_PM25 + 0.1),
        )
    }

    @Test
    fun `clean air is good`() {
        // Riga, measured: 3.9 micrograms.
        assertEquals(AirQualityBand.GOOD, reading(3.9, 3.9).band)
    }

    @Test
    fun `the daily mean decides, not the hour`() {
        // A bonfire for one hour over an otherwise clean day is not a bad day,
        // and this is the whole reason the trailing mean is fetched.
        assertEquals(AirQualityBand.GOOD, reading(pm25 = 90.0, average = 8.0).band)
        // And a steady haze that never spikes is still a bad day.
        assertEquals(AirQualityBand.POOR, reading(pm25 = 41.0, average = 41.0).band)
    }

    @Test
    fun `the hour stands in when no mean was computed`() {
        assertEquals(AirQualityBand.MODERATE, reading(pm25 = 30.0).band)
    }

    @Test
    fun `nothing reported is nothing said`() {
        assertNull(reading(pm25 = null).band)
    }

    @Test
    fun `notable starts where the guideline stops being merely missed`() {
        assertTrue(AirQualityBand.MODERATE.isNotable)
        assertTrue(AirQualityBand.EXTREMELY_POOR.isNotable)
        assertFalse(AirQualityBand.FAIR.isNotable)
        assertFalse(AirQualityBand.GOOD.isNotable)
    }
}
