package lv.bolwarra.wetter.domain.forecast

import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/** What several models say about one hour, and how much they differ. */
data class ModelSpread(
    val at: Instant,
    /** The consensus value: the median, not the mean. */
    val consensus: Double,
    /** Standard deviation across the models. */
    val spread: Double,
    val lowest: Double,
    val highest: Double,
    /** How many models actually answered for this hour. */
    val models: Int,
    /**
     * What each model actually said, so a caller can put another value among
     * them and take the median of the lot.
     *
     * The summary alone cannot do that. A median over seven values and a median
     * over those seven plus an eighth are different ranks of a different list,
     * and there is no way to get the second from the first.
     */
    val values: List<Double> = emptyList(),
) {
    /**
     * 0..1, how much the consensus deserves to be believed.
     *
     * Zero models is not uncertainty, it is ignorance, and one model agreeing
     * with itself is not evidence - so both are reported as low rather than as
     * perfect agreement, which a spread of zero would otherwise imply.
     */
    val agreement: Double
        get() = when {
            models < 2 -> ModelAgreement.LONE_MODEL_AGREEMENT
            else -> ModelAgreement.agreementOf(spread, consensus)
        }
}

/**
 * Several models, read together.
 *
 * A single model's forecast has no error bar attached. Run several independent
 * ones over the same hour and the width of their disagreement is a direct
 * measurement of how hard that hour is to forecast - the one uncertainty signal
 * available without a verification history, and it costs nothing: Open-Meteo
 * returns seven models in a single 5 KB request.
 *
 * That disagreement is real and it is large. Measured over Riga, the seven
 * models spanned 3.4 C at twelve hours out and split five to two on whether it
 * would rain at all; three days out the spread on precipitation exceeded the
 * mean. A screen drawing one of those seven and saying nothing is not more
 * accurate than one drawing the consensus, it is only quieter about being wrong.
 */
object ModelAgreement {

    /**
     * The median, not the mean.
     *
     * One model going badly wrong is the normal failure - a single run picking
     * up a spurious feature - and the mean carries it into the answer while the
     * median steps over it. With seven models, three can be nonsense before the
     * consensus moves.
     */
    fun consensusOf(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    /** Population standard deviation - the models are the whole set, not a sample. */
    fun spreadOf(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    /**
     * Turn a spread into a confidence, judged against what is being measured.
     *
     * Relative rather than absolute, because a degree of disagreement is nothing
     * on a temperature and total disagreement on a rainfall rate of one
     * millimetre. The floor stops a near-zero consensus - the usual case, since
     * most hours are dry - from making any disagreement at all look catastrophic.
     */
    fun agreementOf(spread: Double, consensus: Double): Double {
        val scale = maxOf(abs(consensus), SCALE_FLOOR)
        return (1.0 - spread / scale).coerceIn(0.0, 1.0)
    }

    /**
     * Summarise one hour across models.
     *
     * @param values one value per model, with those that did not answer omitted.
     */
    fun summarise(at: Instant, values: List<Double>): ModelSpread? {
        val consensus = consensusOf(values) ?: return null
        return ModelSpread(
            at = at,
            consensus = consensus,
            values = values.sorted(),
            spread = spreadOf(values),
            lowest = values.min(),
            highest = values.max(),
            models = values.size,
        )
    }

    /**
     * The share of models expecting measurable precipitation.
     *
     * This is a probability in the only honest sense available here: not a
     * confidence dressed as a percentage, but the actual proportion of
     * independent forecasts that say it rains. Five of seven is 0.71, and means
     * exactly that.
     */
    fun probabilityOfPrecipitation(values: List<Double>, threshold: Double = WET_MM): Double? {
        if (values.isEmpty()) return null
        return values.count { it >= threshold }.toDouble() / values.size
    }

    /** A lone model has no disagreement to measure, which is not the same as certainty. */
    const val LONE_MODEL_AGREEMENT = 0.5

    /** Below this, differences are rounding rather than disagreement. */
    private const val SCALE_FLOOR = 1.0

    /** The domain's own trace threshold, in millimetres. */
    private const val WET_MM = 0.1
}

/** What the models collectively say about one hour. */
data class ModelReading(
    val at: Instant,
    val temperature: ModelSpread?,
    val precipitation: ModelSpread?,
    /**
     * The share of models forecasting measurable rain, 0..1.
     *
     * The one figure on this screen that is a probability in the plain sense:
     * five of seven independent forecasts saying it rains is 0.71, and means
     * that and nothing else.
     */
    val chanceOfRain: Double?,
    /**
     * What each model said about precipitation this hour, by position, with a
     * null where a model did not answer.
     *
     * Position is the model, and it means the same model in every reading. That
     * is the difference between this and [ModelSpread.values], which is compacted
     * and sorted for statistics: from these one can follow a single model across
     * the hours, which is what taking a median *at a moment* requires.
     */
    val precipitationByModel: List<Double?> = emptyList(),
)

/** Several models' worth of forecast, hour by hour. */
data class ModelEnsemble(val readings: List<ModelReading>) {

    val isEmpty: Boolean get() = readings.isEmpty()

    /** The reading covering an instant, if the ensemble reaches it. */
    fun at(instant: Instant): ModelReading? {
        val sorted = readings.sortedBy { it.at }
        val index = sorted.indexOfLast { !it.at.isAfter(instant) }
        if (index < 0) return null
        val reading = sorted[index]
        val until = sorted.getOrNull(index + 1)?.at
            ?: reading.at.plus(java.time.Duration.ofHours(1))
        return reading.takeIf { instant.isBefore(until) }
    }

    /**
     * How much the models agree about precipitation at an instant, or null where
     * the ensemble has nothing to say.
     */
    fun precipitationAgreement(instant: Instant): Double? = at(instant)?.precipitation?.agreement
}

/**
 * Somewhere several models' forecasts come from at once.
 *
 * Behind an interface for the same reason every other source is: what the fusion
 * needs is an ensemble, not Open-Meteo. It is also the difference between a
 * repository that can be tested and one that can only be run.
 */
interface EnsembleSource {

    /** Several models over the same hours, or a failure if none could be had. */
    suspend fun ensemble(
        location: lv.bolwarra.wetter.domain.model.WeatherLocation,
    ): Result<ModelEnsemble>
}
