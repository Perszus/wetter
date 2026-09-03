package lv.bolwarra.wetter.domain.verification

import java.time.Duration
import kotlin.math.abs

/** A learned offset for one place, with how much it is worth trusting. */
data class LearnedBias(
    val variable: VerifiedVariable,
    /** Subtract this from a raw forecast. Positive means the forecast runs high. */
    val offset: Double,
    val samples: Int,
    /** 0..1, rising with evidence. Applied to damp the correction. */
    val strength: Double,
) {
    /** The correction actually applied, which is the offset damped by its strength. */
    val effectiveOffset: Double get() = offset * strength
}

/**
 * Learning what a forecast gets systematically wrong here.
 *
 * Models have local biases. A grid square 11 km across, averaged over a coast, a
 * city and a forest, cannot represent all three, and the difference between the
 * square and the place inside it is often a stable offset rather than noise.
 * That offset is learnable from nothing more than a record of past forecasts and
 * what actually happened.
 *
 * There is measured reason to expect it here. Checked against the aerodrome
 * reports around Riga, all seven models ran warm on a clear evening - by 0.8 C
 * at six, rising to 2.1 C by eight - which is the classic underestimate of
 * radiative cooling on a still night. That is exactly the shape of error worth
 * correcting: consistent in sign, growing predictably, and invisible to anyone
 * looking at a single forecast.
 *
 * ### The median, and why the correction is damped
 *
 * The offset is the median error, not the mean, because a handful of badly wrong
 * hours - a front arriving early - would otherwise drag a correction that then
 * gets applied to every ordinary hour.
 *
 * It is also damped by how much evidence there is. Three matching records are
 * not grounds for shifting every temperature on the screen, and a correction
 * that arrives at full strength on its fourth sample would swing the display
 * around for days before settling. [strength] ramps in with the sample count, so
 * a young correction nudges and a well-evidenced one is applied nearly whole.
 */
object BiasCorrection {

    /** Below this, there is nothing worth calling a pattern. */
    const val MINIMUM_SAMPLES = 12

    /** At this many samples the correction is applied at full strength. */
    const val CONFIDENT_SAMPLES = 60

    /**
     * No correction beyond this is believed, in the variable's own units.
     *
     * A learned offset larger than this is far more likely to be a broken
     * station or a location mix-up than a real local effect, and applying it
     * would make the app confidently wrong in a new way.
     */
    const val MAX_TEMPERATURE_OFFSET = 5.0

    /** Records older than this describe a season that has moved on. */
    val MAX_AGE: Duration = Duration.ofDays(30)

    /**
     * Learn the offset for a variable from verified records.
     *
     * @param records already filtered to one place. Returns null when there is
     *   too little to go on, which is the normal state for a new location.
     */
    fun learn(records: List<ForecastRecord>, variable: VerifiedVariable): LearnedBias? {
        val errors = records
            .filter { it.variable == variable }
            .mapNotNull { it.error }
        if (errors.size < MINIMUM_SAMPLES) return null

        val offset = median(errors) ?: return null
        if (variable == VerifiedVariable.TEMPERATURE && abs(offset) > MAX_TEMPERATURE_OFFSET) {
            return null
        }

        val strength = strengthFor(errors.size)
        return LearnedBias(
            variable = variable,
            offset = offset,
            samples = errors.size,
            strength = strength,
        )
    }

    /**
     * Apply a correction to a raw forecast.
     *
     * Null bias means the forecast passes through untouched, which is the right
     * default: an uncorrected number is honest, and a number corrected on no
     * evidence is not.
     */
    fun correct(value: Double, bias: LearnedBias?): Double =
        if (bias == null) value else value - bias.effectiveOffset

    /** Ramps from nothing at the minimum sample count to full at the confident one. */
    fun strengthFor(samples: Int): Double {
        if (samples < MINIMUM_SAMPLES) return 0.0
        val span = (CONFIDENT_SAMPLES - MINIMUM_SAMPLES).toDouble()
        return ((samples - MINIMUM_SAMPLES) / span).coerceIn(0.0, 1.0)
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
