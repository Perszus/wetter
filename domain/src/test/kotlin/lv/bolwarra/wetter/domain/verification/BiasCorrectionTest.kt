package lv.bolwarra.wetter.domain.verification

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiasCorrectionTest {

    private val issued: Instant = Instant.parse("2026-09-03T00:00:00Z")

    private fun records(
        count: Int,
        error: Double,
        variable: VerifiedVariable = VerifiedVariable.TEMPERATURE,
    ) = List(count) { index ->
        ForecastRecord(
            latitude = 56.95,
            longitude = 24.11,
            validAt = issued.plus(Duration.ofHours(index.toLong())),
            issuedAt = issued,
            source = "open-meteo",
            variable = variable,
            predicted = 15.0 + error,
            observed = 15.0,
        )
    }

    @Test
    fun `too little evidence learns nothing`() {
        // The normal state of a new location, and it must not produce a
        // correction - a number adjusted on three samples is worse than one left
        // alone.
        assertNull(BiasCorrection.learn(records(3, error = 2.0), VerifiedVariable.TEMPERATURE))
        assertNull(BiasCorrection.learn(emptyList(), VerifiedVariable.TEMPERATURE))
    }

    @Test
    fun `a consistent warm bias is learned with the right sign`() {
        val bias = BiasCorrection.learn(
            records(40, error = 1.5),
            VerifiedVariable.TEMPERATURE,
        )!!
        assertEquals(1.5, bias.offset, 0.001)
        assertEquals(40, bias.samples)

        // Correcting subtracts it, bringing an over-forecast down.
        assertTrue(BiasCorrection.correct(18.0, bias) < 18.0)
    }

    @Test
    fun `the correction ramps in rather than arriving whole`() {
        // A correction that hit full strength on its thirteenth sample would
        // swing the display about for days before settling.
        val young = BiasCorrection.learn(records(13, error = 2.0), VerifiedVariable.TEMPERATURE)!!
        val grown = BiasCorrection.learn(records(40, error = 2.0), VerifiedVariable.TEMPERATURE)!!
        val settled = BiasCorrection.learn(records(80, error = 2.0), VerifiedVariable.TEMPERATURE)!!

        assertTrue(young.strength < grown.strength)
        assertTrue(grown.strength < settled.strength)
        assertEquals(1.0, settled.strength, 0.001)
        assertTrue(young.effectiveOffset < 0.2)
        assertEquals(2.0, settled.effectiveOffset, 0.001)
    }

    @Test
    fun `a few wild hours do not drag the correction`() {
        // A front arriving early is a large error that says nothing about the
        // ordinary hours. The median steps over it; the mean would not.
        val ordinary = records(30, error = 1.0)
        val fronts = records(4, error = 12.0)
        val bias = BiasCorrection.learn(ordinary + fronts, VerifiedVariable.TEMPERATURE)!!

        assertEquals(1.0, bias.offset, 0.001)
        assertTrue(
            "the mean would have been dragged",
            (ordinary + fronts).mapNotNull { it.error }.average() > 2.0,
        )
    }

    @Test
    fun `an implausibly large correction is refused rather than applied`() {
        // Eight degrees is not a local effect, it is a broken station or the
        // wrong place - and applying it would make the app confidently wrong in
        // a new way.
        assertNull(BiasCorrection.learn(records(40, error = 8.0), VerifiedVariable.TEMPERATURE))
        assertNull(BiasCorrection.learn(records(40, error = -8.0), VerifiedVariable.TEMPERATURE))
    }

    @Test
    fun `no bias means the forecast passes through untouched`() {
        assertEquals(17.3, BiasCorrection.correct(17.3, null), 0.001)
    }

    @Test
    fun `correcting the measured Riga evening moves it the right way`() {
        // Every model ran warm by roughly a degree and a half that evening. With
        // enough such evenings recorded, the correction should pull a 15.1
        // forecast down towards the 14 that was measured.
        val bias = BiasCorrection.learn(records(60, error = 1.4), VerifiedVariable.TEMPERATURE)!!
        val corrected = BiasCorrection.correct(15.1, bias)

        assertTrue("corrected to $corrected", corrected in 13.5..14.0)
    }

    @Test
    fun `records for another variable are not mixed in`() {
        val mixed = records(40, error = 1.0, variable = VerifiedVariable.TEMPERATURE) +
            records(40, error = 4.0, variable = VerifiedVariable.PRECIPITATION)
        val bias = BiasCorrection.learn(mixed, VerifiedVariable.TEMPERATURE)!!
        assertEquals(1.0, bias.offset, 0.001)
    }

    @Test
    fun `strength is zero below the minimum and one at the confident count`() {
        assertEquals(0.0, BiasCorrection.strengthFor(0), 0.001)
        assertEquals(0.0, BiasCorrection.strengthFor(BiasCorrection.MINIMUM_SAMPLES - 1), 0.001)
        assertEquals(0.0, BiasCorrection.strengthFor(BiasCorrection.MINIMUM_SAMPLES), 0.001)
        assertEquals(1.0, BiasCorrection.strengthFor(BiasCorrection.CONFIDENT_SAMPLES), 0.001)
        assertEquals(1.0, BiasCorrection.strengthFor(BiasCorrection.CONFIDENT_SAMPLES * 5), 0.001)
    }
}
