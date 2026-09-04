package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.chart.MonotoneCurve
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.radar.RadarSample

/**
 * One moment of the fused precipitation timeline.
 *
 * [radarShare] is carried because the two sources fail differently and a reader
 * of this timeline may need to know which one it is looking at. Radar is nearly
 * always right about the next twenty minutes and says nothing useful about
 * tonight; the model is the reverse.
 */
data class FusedPrecipitation(
    val at: Instant,
    val millimetresPerHour: Double,
    /** 0..1, how much this value deserves to be believed. */
    val confidence: Double,
    /** 0..1, the weight radar carried here. Zero means this is the model alone. */
    val radarShare: Double,
    /** How many independent sources contributed. */
    val sources: Int,
)

/**
 * Combines what the radar sees with what the models predict.
 *
 * The two are not alternatives. Radar observes precipitation that exists and
 * extrapolates it, which works superbly for twenty minutes and decays to nothing
 * by two hours because storms grow, die and turn. A model predicts the
 * atmosphere's evolution, which is poor about the next twenty minutes - it does
 * not know what is currently overhead - and is the only thing worth having by
 * tonight. Choosing between them throws away whichever one is right.
 *
 * ### The weight comes from the radar's own confidence
 *
 * The usual operational recipe is a fixed table of lead times: radar 80-95% for
 * the first half hour, 60-85% to an hour, 40-70% to two, then handing over. That
 * shape is right on average and it is blind to the one thing that matters most,
 * which is whether *this* estimate is any good. The nowcaster measures exactly
 * that, so the weight is taken from there: it reproduces the table on a
 * confident match and, unlike the table, backs off on a poor one.
 *
 * That difference is not hypothetical. Checked against the 700 hPa steering
 * flow, motion over the Baltic, Berlin, Copenhagen and Reykjavik came out within
 * twelve degrees; over Dublin, which had a fraction of the echo, it was fifty
 * degrees wrong. A fixed table would have given that estimate 90% of the weight
 * for the next half hour.
 *
 * ### The model is never switched off
 *
 * Radar sees precipitation, not the sky. It misses snow it cannot detect, misses
 * what falls below the beam, and cannot see beyond its own coverage - and this
 * source in particular cannot even say where its coverage ends. Leaving the
 * model a share at every lead means those gaps degrade the answer rather than
 * emptying it.
 */
object PrecipitationFusion {

    /**
     * The most the radar is ever allowed, even at zero lead with a perfect
     * match. The remainder is the model's standing share, for everything radar
     * structurally cannot see.
     */
    const val MAX_RADAR_WEIGHT = 0.95

    /**
     * How far ahead the radar simply decides.
     *
     * Inside this window it is not one opinion of two. It is the only source
     * that has actually looked: it sees where the cloud is, how dense it is and
     * which way it is moving, and it looks again every ten minutes. A model's
     * next two hours were computed hours ago from an analysis older still, and
     * nothing about them improves as the hour approaches - whatever was sent is
     * what you get.
     *
     * So inside two hours the projection carries the answer and the model fills
     * only the last sliver. Blending the two in proportion, which is what
     * happened before, let a stale hourly average pull a measured shower back
     * towards the middle exactly where the measurement was strongest.
     *
     * Measured against the lead of the projection rather than against the clock.
     * A sweep half an hour old asked about ninety minutes from now has been
     * pushed two hours downwind, and how far the field has been carried is what
     * decides whether it is still an observation or a guess of its own.
     */
    val RADAR_AUTHORITY: Duration = Duration.ofHours(2)

    /**
     * Confidence attributed to the model when there is nothing to check it
     * against - no ensemble, so no measurement of how hard the hour is.
     */
    const val MODEL_CONFIDENCE = 0.6

    /** Radar samples further than this from a step are not about that step. */
    private val MATCH_TOLERANCE: Duration = Duration.ofMinutes(7)

    /**
     * Build the fused timeline.
     *
     * @param hourly the model's rows, ascending. Interpolated linearly between,
     *   which claims no more resolution than an hourly series has.
     * @param radar the nowcast sampled at this location, possibly empty.
     * @param from first step, inclusive.
     * @param step spacing between steps.
     * @param steps how many to produce.
     * @param ensemble several models over the same hours, when available. Where
     *   it reaches, the model's confidence is *measured* from how far the models
     *   are apart rather than assumed: an hour they all agree on deserves more
     *   weight against the radar than one they are split over, and a flat
     *   constant cannot express the difference.
     */
    fun fuse(
        hourly: List<HourlyWeather>,
        radar: List<RadarSample>,
        from: Instant,
        step: Duration,
        steps: Int,
        ensemble: ModelEnsemble? = null,
    ): List<FusedPrecipitation> {
        if (steps <= 0 || step.isZero || step.isNegative) return emptyList()
        val rows = hourly.sortedBy { it.timestamp }
        val samples = radar.sortedBy { it.at }

        return (0 until steps).map { index ->
            val at = from.plus(step.multipliedBy(index.toLong()))
            val modelRate = interpolate(rows, at)
            val sample = nearest(samples, at)
            val modelConfidence = ensemble?.precipitationAgreement(at) ?: MODEL_CONFIDENCE

            when {
                sample == null && modelRate == null -> FusedPrecipitation(at, 0.0, 0.0, 0.0, 0)
                sample == null -> FusedPrecipitation(
                    at = at,
                    millimetresPerHour = modelRate!!,
                    confidence = modelConfidence,
                    radarShare = 0.0,
                    sources = ensemble?.at(at)?.precipitation?.models ?: 1,
                )
                modelRate == null -> FusedPrecipitation(
                    at = at,
                    millimetresPerHour = sample.millimetresPerHour.toDouble(),
                    confidence = sample.confidence.toDouble(),
                    radarShare = 1.0,
                    sources = 1,
                )
                else -> {
                    val share = radarShareFor(sample)
                    FusedPrecipitation(
                        at = at,
                        millimetresPerHour =
                        share * sample.millimetresPerHour + (1 - share) * modelRate,
                        // Two sources that agree deserve more belief than either
                        // alone; two that disagree deserve less. The gap between
                        // them is the only evidence available about which.
                        confidence = agreement(sample.millimetresPerHour.toDouble(), modelRate)
                            .let { agree ->
                                val base =
                                    share * sample.confidence + (1 - share) * modelConfidence
                                (base * (AGREEMENT_FLOOR + (1 - AGREEMENT_FLOOR) * agree))
                                    .coerceIn(0.0, 1.0)
                            },
                        radarShare = share,
                        sources = 1 + (ensemble?.at(at)?.precipitation?.models ?: 1),
                    )
                }
            }
        }
    }

    /**
     * How closely two rates agree, 0 to 1.
     *
     * Relative to their own size, because half a millimetre apart is close
     * agreement in a downpour and complete disagreement in a drizzle.
     */
    /**
     * How much of the answer the radar gets.
     *
     * Full weight while the projection is still inside its window, and the
     * confidence-weighted blend after it - beyond two hours a field has been
     * carried further than the motion it was measured from can justify, and the
     * model's physics starts being the better bet.
     *
     * Confidence is deliberately not raised to match. Taking the value from the
     * radar says where the number came from; it does not say the number is
     * certain, and a reading two hours out is less certain than one ten minutes
     * out however it was arrived at.
     */
    private fun radarShareFor(sample: RadarSample): Double = if (sample.lead <= RADAR_AUTHORITY) {
        MAX_RADAR_WEIGHT
    } else {
        sample.confidence.toDouble().coerceIn(0.0, 1.0) * MAX_RADAR_WEIGHT
    }

    fun agreement(a: Double, b: Double): Double {
        val scale = maxOf(a, b, AGREEMENT_SCALE_FLOOR)
        return (1.0 - kotlin.math.abs(a - b) / scale).coerceIn(0.0, 1.0)
    }

    /**
     * The model's rate at an instant, along a curve rather than a straight line.
     *
     * Straight lines between hourly rows put a corner at every hour, and no
     * amount of smoothing downstream can remove it: a spline drawn through
     * points that are already collinear reproduces the straight line exactly,
     * corner and all. The bend has to be introduced here, where the only real
     * samples are.
     *
     * The same monotone spline the chart uses, so an hour of nothing between two
     * wet hours still reads as nothing - it cannot invent a shower in a dry gap
     * or dip below zero on the way into one.
     */
    private fun interpolate(rows: List<HourlyWeather>, at: Instant): Double? {
        if (rows.isEmpty()) return null
        val after = rows.indexOfFirst { it.timestamp.isAfter(at) }
        if (after == 0) return null
        if (after < 0) {
            val last = rows.last()
            // Only just past the end counts; hours beyond it are not covered.
            return if (Duration.between(last.timestamp, at) <= Duration.ofHours(1)) {
                last.precipitation
            } else {
                null
            }
        }
        val before = rows[after - 1]
        val next = rows[after]
        val a = before.precipitation ?: return null
        val b = next.precipitation ?: return a
        val span = Duration.between(before.timestamp, next.timestamp).toMillis().toDouble()
        if (span <= 0) return a
        val into = Duration.between(before.timestamp, at).toMillis().toDouble()

        // Tangents need the neighbours either side, so the curve leaving one
        // hour matches the curve arriving at the next.
        val values = rows.map { (it.precipitation ?: 0.0).toFloat() }
        val tangents = MonotoneCurve.tangents(values)
        return MonotoneCurve.valueAt(values, tangents, after - 1, (into / span).toFloat())
            .toDouble()
            .coerceAtLeast(0.0)
    }

    /** The radar sample describing an instant, if one is close enough to. */
    private fun nearest(samples: List<RadarSample>, at: Instant): RadarSample? =
        samples.minByOrNull { Duration.between(it.at, at).abs() }
            ?.takeIf { Duration.between(it.at, at).abs() <= MATCH_TOLERANCE }

    /** Even total disagreement leaves some confidence: one of them is probably right. */
    private const val AGREEMENT_FLOOR = 0.5

    /** Below this rate, differences are noise rather than disagreement. */
    private const val AGREEMENT_SCALE_FLOOR = 0.5
}
