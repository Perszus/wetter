// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.chart.MonotoneCurve
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.radar.RadarSample

/**
 * One moment of the fused precipitation timeline. [radarShare] says which source
 * it mostly came from: radar is right about the next twenty minutes and silent
 * about tonight, the models the reverse.
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
 * Radar observes precipitation that exists and carries it forward, which is
 * excellent for minutes and decays within hours; the models are weak on what is
 * overhead now and the only thing worth having by tonight. So radar leads its
 * window ([RADAR_AUTHORITY]), the length of which comes from the nowcaster's own
 * measure of how good this motion estimate is, and the models always keep a share,
 * because radar misses snow it cannot detect, rain below the beam and anything
 * outside its coverage.
 */
object PrecipitationFusion {

    /**
     * What radar carries inside its window; the models keep the rest. Past the
     * window radar carries nothing and the answer is the models'.
     */
    const val MAX_RADAR_WEIGHT = 0.80

    /**
     * How far ahead radar decides. Its field is only carried along, which holds well
     * for minutes and is strained by an hour, when cells have grown or died. Measured
     * against the projection's lead rather than the clock.
     */
    val RADAR_AUTHORITY: Duration = Duration.ofHours(1)

    /**
     * The shortest the window gets, however poorly the motion was measured: near zero
     * lead the sample is the observation itself, which a bad vector cannot corrupt.
     */
    val LEAST_AUTHORITY: Duration = Duration.ofMinutes(15)

    /**
     * How long the hand-over takes: long enough not to show, short enough not to be a
     * longer window by another name.
     */
    val AUTHORITY_FADE: Duration = Duration.ofMinutes(20)

    /** The model's confidence when there is no ensemble to measure it from. */
    const val MODEL_CONFIDENCE = 0.6

    /** Radar samples further than this from a step are not about that step. */
    private val MATCH_TOLERANCE: Duration = Duration.ofMinutes(7)

    /**
     * Build the fused timeline.
     *
     * @param hourly the provider's rows, ascending.
     * @param radar the nowcast sampled at this location, possibly empty.
     * @param from first step, inclusive.
     * @param step spacing between steps.
     * @param steps how many to produce, at most. The series stops where both sources
     * run out rather than being padded: a step nothing can answer is a gap in the
     * evidence, not a dry spell.
     * @param ensemble several models over the same hours, when available. The model
     * value is then the median across them and the provider, and its confidence is
     * measured from how far they are apart.
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
        // Every source as a line through time, built once.
        val traces = modelTraces(ensemble)

        return (0 until steps).map { index ->
            val at = from.plus(step.multipliedBy(index.toLong()))
            val sample = nearest(samples, at)
            val modelConfidence = ensemble?.precipitationAgreement(at) ?: MODEL_CONFIDENCE
            val modelRate = medianAt(traces, rows, at)

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
                        // Two sources that agree deserve more belief than either alone, two that
                        // disagree less.
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
        }.dropLastWhile { it.sources == 0 }
    }

    /**
     * How much of the answer the radar gets: [MAX_RADAR_WEIGHT] within its window,
     * easing to nothing after it. Confidence is not raised to match: where a number
     * came from is not how certain it is.
     */
    private fun radarShareFor(sample: RadarSample): Double {
        val authority = authorityFor(sample.motionQuality)
        val past = sample.lead.toMillis() - authority.toMillis()

        // Eased with a smoothstep rather than dropped, so neither the value nor its rate
        // of change steps at the boundary.
        val eased = when {
            past <= 0L -> 0.0
            past >= AUTHORITY_FADE.toMillis() -> 1.0
            else -> {
                val t = past.toDouble() / AUTHORITY_FADE.toMillis()
                t * t * (3.0 - 2.0 * t)
            }
        }

        // Past its window radar is out; what remains is what the models agree on.
        return MAX_RADAR_WEIGHT * (1.0 - eased)
    }

    /**
     * Every source as a continuous line through time, so the fused value can be read
     * at any instant. Monotone, so it passes through each published value and never
     * overshoots between them.
     */
    private fun modelTraces(ensemble: ModelEnsemble?): List<Trace> {
        val readings = ensemble?.readings?.sortedBy { it.at }.orEmpty()
        if (readings.isEmpty()) return emptyList()

        val sources = readings.maxOf { it.precipitationByModel.size }
        return (0 until sources).mapNotNull { source ->
            val points = readings.mapNotNull { reading ->
                reading.precipitationByModel.getOrNull(source)?.let { reading.at to it }
            }
            if (points.size < 2) null else Trace(points)
        }
    }

    /** One source's forecast as a line, with the tangents it is drawn through. */
    private class Trace(private val points: List<Pair<Instant, Double>>) {
        private val values = points.map { it.second.toFloat() }
        private val tangents = MonotoneCurve.tangents(values)

        /** What this source says at a moment, or null where it does not reach. */
        fun valueAt(at: Instant): Double? {
            val after = points.indexOfFirst { it.first.isAfter(at) }
            if (after == 0) return null
            if (after < 0) {
                val last = points.last()
                return last.second.takeIf {
                    Duration.between(last.first, at) <= Duration.ofHours(1)
                }
            }
            val before = points[after - 1]
            val next = points[after]
            val span = Duration.between(before.first, next.first).toMillis().toDouble()
            if (span <= 0) return before.second
            val into = Duration.between(before.first, at).toMillis().toDouble()
            return MonotoneCurve.valueAt(values, tangents, after - 1, (into / span).toFloat())
                .toDouble()
        }
    }

    /**
     * The median of every source at one moment, the provider included as one of
     * them. Read at the moment itself rather than as a line through hourly medians,
     * so the answer is a value some source actually holds then. A median, so one
     * outlying model can drag the hour neither up nor down. With no ensemble this is
     * the provider alone.
     */
    private fun medianAt(traces: List<Trace>, rows: List<HourlyWeather>, at: Instant): Double? {
        val provider = interpolate(rows, at)
        val voices = traces.mapNotNull { it.valueAt(at) }
        if (voices.isEmpty()) return provider
        return ModelAgreement.consensusOf(if (provider != null) voices + provider else voices)
    }

    /**
     * How far ahead this projection may decide: the full [RADAR_AUTHORITY] for a sharp
     * motion match, down to [LEAST_AUTHORITY] for an ambiguous one such as flat drizzle.
     * The observation itself is never handed back to the models.
     */
    fun authorityFor(motionQuality: Float): Duration {
        val quality = motionQuality.toDouble().coerceIn(0.0, 1.0)
        val span = RADAR_AUTHORITY.toMinutes() - LEAST_AUTHORITY.toMinutes()
        return LEAST_AUTHORITY.plusMinutes((span * quality).toLong())
    }

    /**
     * How closely two rates agree, from 0 to 1, relative to their size: half a
     * millimetre apart is close in a downpour and far apart in drizzle.
     */
    fun agreement(a: Double, b: Double): Double {
        val scale = maxOf(a, b, AGREEMENT_SCALE_FLOOR)
        return (1.0 - kotlin.math.abs(a - b) / scale).coerceIn(0.0, 1.0)
    }

    /**
     * The provider's rate at an instant along the same monotone spline the chart
     * uses, so the curve has no corner at every hour and a dry hour stays dry.
     */
    private fun interpolate(rows: List<HourlyWeather>, at: Instant): Double? {
        if (rows.isEmpty()) return null
        val after = rows.indexOfFirst { it.timestamp.isAfter(at) }
        if (after == 0) return null
        if (after < 0) {
            val last = rows.last()
            // Only just past the end counts.
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

        // Tangents need the neighbours either side.
        val values = rows.map { (it.precipitation ?: 0.0).toFloat() }
        val tangents = MonotoneCurve.tangents(values)
        return MonotoneCurve.valueAt(values, tangents, after - 1, (into / span).toFloat())
            .toDouble()
            .coerceAtLeast(0.0)
    }

    /** The radar sample describing an instant, if one is close enough. */
    private fun nearest(samples: List<RadarSample>, at: Instant): RadarSample? =
        samples.minByOrNull { Duration.between(it.at, at).abs() }
            ?.takeIf { Duration.between(it.at, at).abs() <= MATCH_TOLERANCE }

    /** Even total disagreement leaves some confidence: one of them is probably right. */
    private const val AGREEMENT_FLOOR = 0.5

    /** Below this rate, differences are noise rather than disagreement. */
    private const val AGREEMENT_SCALE_FLOOR = 0.5
}
