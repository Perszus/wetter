package lv.bolwarra.wetter.domain.verification

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationTest {

    private val issued: Instant = Instant.parse("2026-09-03T00:00:00Z")

    private fun record(
        predicted: Double,
        observed: Double?,
        source: String = "open-meteo",
        variable: VerifiedVariable = VerifiedVariable.TEMPERATURE,
        leadHours: Long = 6,
    ) = ForecastRecord(
        latitude = 56.95,
        longitude = 24.11,
        validAt = issued.plus(Duration.ofHours(leadHours)),
        issuedAt = issued,
        source = source,
        variable = variable,
        predicted = predicted,
        observed = observed,
    )

    @Test
    fun `an unchecked forecast is not a correct one`() {
        // The distinction the whole store exists for.
        val unverified = record(predicted = 15.0, observed = null)
        assertNull(unverified.error)
        assertNull(
            Verification.score(listOf(unverified), "open-meteo", VerifiedVariable.TEMPERATURE),
        )
    }

    @Test
    fun `error is signed so a systematic offset is visible`() {
        // Forecast 17, observed 15: it ran two degrees warm. The sign is the
        // whole point - it is what makes the offset correctable.
        assertEquals(2.0, record(17.0, 15.0).error!!, 0.001)
        assertEquals(-2.0, record(13.0, 15.0).error!!, 0.001)
    }

    @Test
    fun `the measured Riga warm bias shows up as a bias, not as scatter`() {
        // The evening actually measured against the aerodrome reports: every
        // model warm, and increasingly so as the night drew in.
        val evening = listOf(
            record(16.8, 16.0),
            record(15.9, 15.0),
            record(15.1, 14.0),
            record(15.1, 13.0),
        )
        val score = Verification.score(evening, "open-meteo", VerifiedVariable.TEMPERATURE)!!

        assertEquals(4, score.samples)
        assertTrue("bias was ${score.bias}", score.bias > 0.9)
        // All the error is in the offset, so the absolute error is the same size
        // as the bias - a signature that this is correctable rather than noise.
        assertEquals(score.bias, score.meanAbsoluteError, 0.001)
    }

    @Test
    fun `scatter and bias are distinguishable`() {
        // Equal and opposite misses average to no bias at all, but they are not
        // a good forecast - the absolute error is what shows that.
        val scattered = listOf(record(18.0, 15.0), record(12.0, 15.0))
        val score = Verification.score(scattered, "open-meteo", VerifiedVariable.TEMPERATURE)!!

        assertEquals(0.0, score.bias, 0.001)
        assertEquals(3.0, score.meanAbsoluteError, 0.001)
    }

    @Test
    fun `occasional large misses show up in the squared error`() {
        val steady = List(4) { record(16.0, 15.0) }
        val erratic =
            listOf(record(15.0, 15.0), record(15.0, 15.0), record(15.0, 15.0), record(19.0, 15.0))

        val steadyScore = Verification.score(steady, "open-meteo", VerifiedVariable.TEMPERATURE)!!
        val erraticScore = Verification.score(erratic, "open-meteo", VerifiedVariable.TEMPERATURE)!!

        // Same mean absolute error, very different character.
        assertEquals(1.0, steadyScore.meanAbsoluteError, 0.001)
        assertEquals(1.0, erraticScore.meanAbsoluteError, 0.001)
        assertTrue(erraticScore.rootMeanSquareError > steadyScore.rootMeanSquareError)
    }

    @Test
    fun `the leaderboard puts the closest source first`() {
        val records = listOf(
            record(15.2, 15.0, source = "good"),
            record(14.9, 15.0, source = "good"),
            record(18.0, 15.0, source = "poor"),
            record(12.0, 15.0, source = "poor"),
        )
        val board = Verification.leaderboard(records, VerifiedVariable.TEMPERATURE)
        assertEquals(2, board.size)
        assertEquals("good", board.first().source)
    }

    @Test
    fun `a forecast that never predicts rain scores nothing, however accurate`() {
        // Accuracy is worthless in a dry climate: always saying "dry" is right
        // most of the time and useless. The critical success index ignores the
        // correct negatives entirely, so it cannot be gamed this way.
        val alwaysDry = List(9) {
            record(0.0, 0.0, variable = VerifiedVariable.PRECIPITATION)
        } + record(0.0, 4.0, variable = VerifiedVariable.PRECIPITATION)

        val table = Verification.contingency(alwaysDry)
        assertEquals(0, table.hits)
        assertEquals(1, table.misses)
        assertEquals(9, table.correctNegatives)
        assertEquals(0.0, table.criticalSuccessIndex!!, 0.001)
        assertEquals(0.0, table.probabilityOfDetection!!, 0.001)
    }

    @Test
    fun `both kinds of precipitation mistake are counted`() {
        val records = listOf(
            record(2.0, 3.0, variable = VerifiedVariable.PRECIPITATION), // hit
            record(0.0, 3.0, variable = VerifiedVariable.PRECIPITATION), // miss
            record(2.0, 0.0, variable = VerifiedVariable.PRECIPITATION), // false alarm
            record(0.0, 0.0, variable = VerifiedVariable.PRECIPITATION), // correct negative
        )
        val table = Verification.contingency(records)
        assertEquals(1, table.hits)
        assertEquals(1, table.misses)
        assertEquals(1, table.falseAlarms)
        assertEquals(1, table.correctNegatives)
        assertEquals(0.5, table.probabilityOfDetection!!, 0.001)
        assertEquals(0.5, table.falseAlarmRatio!!, 0.001)
        assertEquals(1.0 / 3.0, table.criticalSuccessIndex!!, 0.001)
    }

    @Test
    fun `scores that cannot be computed are absent rather than zero`() {
        // Nothing was forecast and nothing happened. Reporting a perfect or a
        // zero score would both be claims the data does not support.
        val table = Verification.contingency(
            List(3) { record(0.0, 0.0, variable = VerifiedVariable.PRECIPITATION) },
        )
        assertNull(table.probabilityOfDetection)
        assertNull(table.falseAlarmRatio)
        assertNull(table.criticalSuccessIndex)
    }

    @Test
    fun `records are matched to the observation of the hour they describe`() {
        val forecasts = listOf(record(15.0, null, leadHours = 6))
        val observations = listOf(
            issued.plus(Duration.ofHours(3)) to 11.0,
            issued.plus(Duration.ofHours(6)) to 14.0,
            issued.plus(Duration.ofHours(9)) to 17.0,
        )
        val matched = Verification.matchByTime(forecasts, observations)
        assertEquals(1, matched.size)
        assertEquals(14.0, matched.first().second, 0.001)
    }

    @Test
    fun `an observation from the wrong hour is not used`() {
        val forecasts = listOf(record(15.0, null, leadHours = 6))
        val faraway = listOf(issued.plus(Duration.ofHours(20)) to 14.0)
        assertTrue(Verification.matchByTime(forecasts, faraway).isEmpty())
    }
}
