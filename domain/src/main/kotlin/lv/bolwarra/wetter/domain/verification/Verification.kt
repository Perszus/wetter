// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.verification

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/** Which quantity a record is about. */
enum class VerifiedVariable { TEMPERATURE, PRECIPITATION }

/**
 * One prediction, kept so it can be marked once the hour it describes has
 * passed.
 */
data class ForecastRecord(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    /** The hour being forecast. */
    val validAt: Instant,
    /** When the forecast was made. [validAt] minus this is the lead time. */
    val issuedAt: Instant,
    /** Which provider, model or blend said it. */
    val source: String,
    val variable: VerifiedVariable,
    val predicted: Double,
    /** Filled in once the hour has passed and an observation exists. */
    val observed: Double? = null,
) {
    val lead: Duration get() = Duration.between(issuedAt, validAt)

    /** Positive means the forecast was too high. */
    val error: Double? get() = observed?.let { predicted - it }

    val isVerified: Boolean get() = observed != null
}

/** How well a source did, over a set of records. */
data class SkillScore(
    val source: String,
    val variable: VerifiedVariable,
    val samples: Int,
    /** Mean absolute error - the typical size of a miss, in the variable's units. */
    val meanAbsoluteError: Double,
    /** Root mean square error. Larger than the MAE when misses are occasionally big. */
    val rootMeanSquareError: Double,
    /**
     * Mean signed error. Positive means it runs high. The one worth acting on: a
     * systematic offset can be subtracted, scatter cannot.
     */
    val bias: Double,
)

/**
 * How a forecast of a yes-or-no event turned out. Precipitation is verified this
 * way because reports are categorical (light rain, never 0.4 mm), and whether the
 * rain came when forecast is what people rely on.
 */
data class ContingencyTable(
    /** Forecast and observed. */
    val hits: Int,
    /** Observed but not forecast. The failure people remember. */
    val misses: Int,
    /** Forecast but not observed. The failure that costs belief. */
    val falseAlarms: Int,
    /** Neither forecast nor observed. */
    val correctNegatives: Int,
) {
    val total: Int get() = hits + misses + falseAlarms + correctNegatives

    /**
     * Probability of detection: of the times it rained, how often was that
     * forecast. Null when it never rained, because there was nothing to detect.
     */
    val probabilityOfDetection: Double?
        get() = (hits + misses).takeIf { it > 0 }?.let { hits.toDouble() / it }

    /**
     * False alarm ratio: of the times rain was forecast, how often it did not
     * come. Null when rain was never forecast.
     */
    val falseAlarmRatio: Double?
        get() = (hits + falseAlarms).takeIf { it > 0 }?.let { falseAlarms.toDouble() / it }

    /**
     * Critical success index, the single number worth quoting. It ignores correct
     * negatives, so forecasting "dry" forever in a mostly dry climate scores nothing.
     */
    val criticalSuccessIndex: Double?
        get() = (hits + misses + falseAlarms).takeIf { it > 0 }?.let { hits.toDouble() / it }
}

/**
 * Scoring what was predicted against what happened: the numbers [BiasCorrection]
 * is derived from.
 */
object Verification {

    /** Rain, for the purposes of a yes-or-no verification. */
    const val WET_MM = 0.1

    /**
     * Score one source on one variable. Unverified records are ignored, not counted
     * as successes.
     */
    fun score(
        records: List<ForecastRecord>,
        source: String,
        variable: VerifiedVariable,
    ): SkillScore? {
        val errors = records
            .filter { it.source == source && it.variable == variable }
            .mapNotNull { it.error }
        if (errors.isEmpty()) return null

        return SkillScore(
            source = source,
            variable = variable,
            samples = errors.size,
            meanAbsoluteError = errors.sumOf { abs(it) } / errors.size,
            rootMeanSquareError = sqrt(errors.sumOf { it * it } / errors.size),
            bias = errors.average(),
        )
    }

    /** Score every source present, best first. */
    fun leaderboard(records: List<ForecastRecord>, variable: VerifiedVariable): List<SkillScore> =
        records
            .filter { it.variable == variable && it.isVerified }
            .map { it.source }
            .distinct()
            .mapNotNull { score(records, it, variable) }
            .sortedBy { it.meanAbsoluteError }

    /** Build the yes-or-no table for precipitation records. */
    fun contingency(records: List<ForecastRecord>, threshold: Double = WET_MM): ContingencyTable {
        var hits = 0
        var misses = 0
        var falseAlarms = 0
        var correctNegatives = 0

        records.filter { it.variable == VerifiedVariable.PRECIPITATION && it.isVerified }
            .forEach { record ->
                val forecast = record.predicted >= threshold
                val happened = record.observed!! >= threshold
                when {
                    forecast && happened -> hits++
                    !forecast && happened -> misses++
                    forecast && !happened -> falseAlarms++
                    else -> correctNegatives++
                }
            }
        return ContingencyTable(hits, misses, falseAlarms, correctNegatives)
    }

    /**
     * Match predictions to observations by the hour they describe.
     *
     * @param tolerance how far apart a record and an observation may be and
     *   still be about the same moment.
     */
    fun <T> matchByTime(
        records: List<ForecastRecord>,
        observations: List<Pair<Instant, T>>,
        tolerance: Duration = Duration.ofMinutes(30),
    ): List<Pair<ForecastRecord, T>> {
        if (observations.isEmpty()) return emptyList()
        return records.mapNotNull { record ->
            val nearest = observations.minByOrNull {
                Duration.between(it.first, record.validAt).abs()
            } ?: return@mapNotNull null
            val gap = Duration.between(nearest.first, record.validAt).abs()
            if (gap <= tolerance) record to nearest.second else null
        }
    }
}
