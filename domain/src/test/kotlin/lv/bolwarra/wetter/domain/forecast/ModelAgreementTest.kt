package lv.bolwarra.wetter.domain.forecast

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAgreementTest {

    private val at: Instant = Instant.parse("2026-09-03T12:00:00Z")

    @Test
    fun `the consensus is the median, so one wild model cannot drag it`() {
        // Six models near 15 and one that has gone badly wrong. The mean would be
        // pulled most of a degree; the median does not move at all.
        val values = listOf(15.0, 15.2, 14.8, 15.1, 14.9, 15.0, 40.0)
        assertEquals(15.0, ModelAgreement.consensusOf(values)!!, 0.001)
        assertTrue("the mean would have moved", values.average() > 17.0)
    }

    @Test
    fun `an even number of models averages the middle pair`() {
        assertEquals(3.5, ModelAgreement.consensusOf(listOf(1.0, 3.0, 4.0, 8.0))!!, 0.001)
    }

    @Test
    fun `nothing to summarise gives nothing`() {
        assertNull(ModelAgreement.consensusOf(emptyList()))
        assertNull(ModelAgreement.summarise(at, emptyList()))
        assertNull(ModelAgreement.probabilityOfPrecipitation(emptyList()))
    }

    @Test
    fun `models that agree score higher than models that argue`() {
        val agreeing = ModelAgreement.summarise(at, listOf(15.0, 15.1, 14.9, 15.0))!!
        val arguing = ModelAgreement.summarise(at, listOf(11.0, 15.0, 19.0, 13.0))!!

        assertTrue(agreeing.agreement > 0.9)
        assertTrue(
            "agreeing ${agreeing.agreement} vs arguing ${arguing.agreement}",
            arguing.agreement < agreeing.agreement,
        )
    }

    @Test
    fun `a lone model is not treated as unanimous`() {
        // One model agreeing with itself has a spread of zero, which would
        // otherwise read as perfect confidence.
        val alone = ModelAgreement.summarise(at, listOf(15.0))!!
        assertEquals(1, alone.models)
        assertEquals(ModelAgreement.LONE_MODEL_AGREEMENT, alone.agreement, 0.001)
    }

    @Test
    fun `disagreement is judged against what is being measured`() {
        // Half a degree apart on a temperature is close agreement. Half a
        // millimetre apart on a drizzle is the difference between wet and dry.
        val onTemperature = ModelAgreement.agreementOf(spread = 0.5, consensus = 18.0)
        val onDrizzle = ModelAgreement.agreementOf(spread = 0.5, consensus = 0.4)

        assertTrue(onTemperature > 0.95)
        assertTrue(onDrizzle < 0.6)
    }

    @Test
    fun `the probability of rain is the share of models forecasting it`() {
        // Not a confidence dressed up as a percentage: five of these seven say
        // it rains, and that is exactly what 0.71 means.
        val values = listOf(0.0, 0.3, 0.6, 0.0, 1.2, 0.4, 0.2)
        assertEquals(5.0 / 7.0, ModelAgreement.probabilityOfPrecipitation(values)!!, 0.001)

        assertEquals(0.0, ModelAgreement.probabilityOfPrecipitation(listOf(0.0, 0.0))!!, 0.001)
        assertEquals(1.0, ModelAgreement.probabilityOfPrecipitation(listOf(2.0, 3.0))!!, 0.001)
    }

    @Test
    fun `the summary keeps the range, not just the middle`() {
        val spread = ModelAgreement.summarise(at, listOf(11.0, 15.0, 19.0))!!
        assertEquals(11.0, spread.lowest, 0.001)
        assertEquals(19.0, spread.highest, 0.001)
        assertEquals(15.0, spread.consensus, 0.001)
        assertEquals(3, spread.models)
    }

    @Test
    fun `the measured Riga disagreement comes out as real uncertainty`() {
        // The seven models over Riga at twelve hours out, spanning 3.4 C. That
        // is a genuinely uncertain hour and must not score as a confident one.
        val riga = listOf(19.0, 19.4, 18.6, 21.2, 19.9, 20.8, 17.8)
        val summary = ModelAgreement.summarise(at, riga)!!

        assertTrue("spread was ${summary.spread}", summary.spread > 1.0)
        assertEquals(3.4, summary.highest - summary.lowest, 0.05)
        assertTrue("agreement was ${summary.agreement}", summary.agreement < 0.95)
    }
}
