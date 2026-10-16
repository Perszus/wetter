// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.verification

import java.time.Duration
import kotlin.math.abs

/** A learned offset for one place, with how much it is worth trusting. */
data class LearnedBias(
    val variable: VerifiedVariable,
    /** Subtract this from a raw forecast. Positive means the forecast runs high. */
    val offset: Double,
    /** Distinct hours verified, not predictions written down. */
    val samples: Int,
    /** 0..1, rising with evidence. Applied to damp the correction. */
    val strength: Double,
) {
    /** The correction actually applied: the offset damped by its strength. */
    val effectiveOffset: Double get() = offset * strength
}

/**
 * Learning what a forecast gets systematically wrong here.
 *
 * A model grid square averages coast, city and forest, and the difference between
 * the square and the place inside it is often a stable offset, such as models
 * running warm on clear, still evenings. That is learnable from a record of past
 * forecasts and what happened.
 *
 * One hour is one sample, however many forecasts were made of it: an hour is
 * recorded on every refresh, so errors are collapsed per hour (their median) and
 * the sample count is distinct hours verified. The offset is the median error, so
 * a few badly wrong hours cannot drag it, and it is damped by [strength], which
 * ramps in with the evidence.
 */
object BiasCorrection {

    /** Verified hours below which there is nothing worth calling a pattern. */
    const val MINIMUM_SAMPLES = 12

    /**
     * At this many verified hours the correction is applied in full: about two and a
     * half days, enough to have seen a clear night and a cloudy one.
     */
    const val CONFIDENT_SAMPLES = 60

    /**
     * No correction beyond this is believed, in the variable's own units: a larger
     * offset is far more likely a broken station or a location mix-up.
     */
    const val MAX_TEMPERATURE_OFFSET = 5.0

    /** Records older than this describe a season that has moved on. */
    val MAX_AGE: Duration = Duration.ofDays(30)

    /**
     * Learn the offset for a variable from verified records.
     *
     * @param records already filtered to one place.
     * @return null when there is too little to go on, the normal state for a new
     * location.
     */
    fun learn(records: List<ForecastRecord>, variable: VerifiedVariable): LearnedBias? {
        // One error per hour forecast: several predictions of the same hour are one piece
        // of evidence.
        val errors = records
            .filter { it.variable == variable && it.error != null }
            .groupBy { it.validAt }
            .mapNotNull { (_, ofThatHour) -> median(ofThatHour.mapNotNull { it.error }) }
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

    /** Apply a correction to a raw forecast. A null bias passes it through untouched. */
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
