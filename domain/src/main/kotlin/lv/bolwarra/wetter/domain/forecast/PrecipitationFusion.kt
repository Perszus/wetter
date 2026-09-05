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
     * What radar carries inside its own window; the models keep the rest.
     *
     * Radar sees precipitation, not the sky - it misses snow it cannot detect,
     * misses what falls below the beam, and on this source cannot even say where
     * its coverage ends. A fifth left with the models is what stops those gaps
     * emptying the answer rather than merely degrading it.
     *
     * Past the window this goes to zero rather than to some smaller share, so
     * the whole of the answer is the models agreeing among themselves.
     */
    const val MAX_RADAR_WEIGHT = 0.80

    /**
     * How far ahead the radar simply decides.
     *
     * Inside this window it is not one opinion of two. It is the only source
     * that has actually looked: it sees where the cloud is, how dense it is and
     * which way it is moving, and it looks again every ten minutes. A model's
     * next hours were computed hours ago from an analysis older still, and
     * nothing about them improves as the hour approaches - whatever was sent is
     * what you get. Nobody needs a report from half an hour ago to know there is
     * rain overhead.
     *
     * **One hour, not two.** The method underneath is advection: the field is
     * assumed frozen and only carried along. Over ten minutes that is very
     * nearly true. By an hour it is strained, and by two the cell that was
     * coming may have rained itself out while something new built overhead that
     * no amount of looking at old frames could have shown. An hour is where the
     * assumption is still doing more good than harm, and handing back earlier
     * also means a thin, structureless field - the one case where the motion
     * estimate is worth least - is trusted alone for less time.
     *
     * Measured against the lead of the projection rather than against the clock.
     * A sweep half an hour old asked about ninety minutes from now has been
     * pushed two hours downwind, and how far the field has been carried is what
     * decides whether it is still an observation or a guess of its own.
     */
    val RADAR_AUTHORITY: Duration = Duration.ofHours(1)

    /**
     * The shortest the window ever gets, however poorly the motion was measured.
     *
     * At zero lead there is no advection at all: the sample is the observed
     * field at those coordinates, and a motion estimate fifty degrees wrong
     * cannot corrupt it. The error a bad vector introduces grows with lead and
     * with speed, so what a poor match should cost is *reach*, not the
     * observation itself. This is the reach that survives regardless.
     */
    val LEAST_AUTHORITY: Duration = Duration.ofMinutes(15)

    /**
     * How long the hand-over takes.
     *
     * The projection does not stop being an observation at a stroke, so the
     * weighting should not either. Twenty minutes is long enough that nobody
     * can see where it happens and short enough that it is not quietly a
     * longer window by another name.
     */
    val AUTHORITY_FADE: Duration = Duration.ofMinutes(20)

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
        // Every source as a line through time. Built once, because a line does
        // not depend on which point of it is being read.
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
    private fun radarShareFor(sample: RadarSample): Double {
        val authority = authorityFor(sample.motionQuality)
        val past = sample.lead.toMillis() - authority.toMillis()

        // Eased across the boundary rather than dropped at it.
        //
        // A step in the weighting is a step in the answer, and there is nothing
        // in the sky that steps at the moment a projection turns an hour old.
        // It showed as a kink in the curve at the same place every time, which
        // is the signature of an artefact rather than of weather.
        //
        // Smoothstep, not a straight ramp, because its slope is zero at both
        // ends: the answer leaves full authority gently and arrives at the
        // confidence-weighted blend gently. A linear fade removes the step in
        // the value and leaves one in its rate of change, which the eye still
        // finds.
        val eased = when {
            past <= 0L -> 0.0
            past >= AUTHORITY_FADE.toMillis() -> 1.0
            else -> {
                val t = past.toDouble() / AUTHORITY_FADE.toMillis()
                t * t * (3.0 - 2.0 * t)
            }
        }

        // Radar leads its window, then leaves entirely.
        //
        // It used to fade to `MAX_RADAR_WEIGHT * confidence` instead of to
        // nothing, which sounds like a gentle retreat and is not one: at two
        // hours out that still left radar carrying about a third of the answer.
        // A projection that has run out of things to say says zero, and a third
        // of zero is a third of the way to a dry evening - so a wet night with
        // six of seven models forecasting rain drew as flat and empty, held down
        // by an extrapolation nobody should still have been listening to.
        //
        // Past its window radar is out, and what remains is what the models
        // agree on between themselves.
        return MAX_RADAR_WEIGHT * (1.0 - eased)
    }

    /**
     * How far ahead this particular projection is allowed to decide.
     *
     * Not *whether* the radar leads - it always leads, being the only source
     * that looked - but how far the look can be carried before the measurement
     * behind it stops supporting it.
     *
     * A sharp match on a field with edges and cells earns the full hour. A flat
     * sheet of drizzle looks identical wherever you slide it, so the match is
     * ambiguous and the vector is close to a guess; that projection keeps only
     * the reach where advection barely matters. The observation is never taken
     * away in either case, which is the difference between this and a quality
     * gate - a gate can hand a near-observation back to a model, and this
     * cannot.
     */
    /**
     * What the models say, rather than what one of them says.
     *
     * The provider chosen for a place is a single deterministic run, and a
     * single run is wrong in ways nothing in its own output reveals. Measured on
     * a wet evening in Riga: the app's chosen provider gave 0.0 mm for every
     * hour after the next, symbol "cloudy", while the seven-model ensemble the
     * app was *already downloading* had six of seven wet over the same hours -
     * ECMWF at 1.3, UKMO at 1.7, DMI at 1.9. The screen showed a flat dry
     * evening, in the rain, with the contradicting evidence already on the
     * device and used only to tint a confidence number.
     *
     * That is the app's own rule inverted: one source was overruling six on the
     * strength of being the one we asked first.
     *
     * So the provider becomes a vote rather than the verdict. The value is the
     * median across the ensemble members *and* the provider - the provider is
     * genuinely in there, not blended in afterwards, because a median over seven
     * values and a median over those seven plus an eighth are different ranks of
     * a different list.
     *
     * A median rather than a mean on purpose. It is the statistic that ignores
     * how wrong an outlier is: one model forecasting a deluge cannot drag the
     * hour upward any more than one forecasting nothing can drag it down, and on
     * this evidence both failures happen.
     *
     * With no ensemble - offline, or a place none of them cover - this is the
     * provider alone, exactly as before.
     */
    /**
     * Every source as a line through time.
     *
     * The unit of this app is a point in time, not an hour. An hour is only
     * where a particular source happens to have put a number; it is not a thing
     * the weather does, and nothing downstream should inherit it. So each source
     * becomes a continuous line that can be read at any moment, and the hours it
     * was published at survive only as the points that line passes through.
     *
     * Monotone specifically: the line passes through every value the source
     * actually gave and cannot overshoot between them, so it never dips below
     * zero on the way from a dry point to a wet one and never invents a peak
     * higher than anything the source forecast.
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
     * The middle of what every source says about one moment.
     *
     * Three sources saying 2.0, 1.5 and 1.0 for a quarter to six make 1.5 for a
     * quarter to six, because it is the middle one. That is the whole rule, and
     * it is applied to the moment being drawn rather than to some interval the
     * moment falls in.
     *
     * The order matters and was wrong before. Taking a middle for each hour and
     * drawing a line through those middles is a different operation, because a
     * median is not linear: the middle of the averages is not the average of the
     * middles. Reading every line at the point and taking the middle there means
     * the answer is always a value some source actually holds at that moment,
     * which is the property a median is picked for.
     *
     * The chosen provider is one of the sources rather than something applied
     * afterwards, so with an even number of them the answer is the mean of the
     * middle two, exactly as a median is.
     */
    private fun medianAt(traces: List<Trace>, rows: List<HourlyWeather>, at: Instant): Double? {
        val provider = interpolate(rows, at)
        val voices = traces.mapNotNull { it.valueAt(at) }
        if (voices.isEmpty()) return provider
        return ModelAgreement.consensusOf(if (provider != null) voices + provider else voices)
    }

    fun authorityFor(motionQuality: Float): Duration {
        val quality = motionQuality.toDouble().coerceIn(0.0, 1.0)
        val span = RADAR_AUTHORITY.toMinutes() - LEAST_AUTHORITY.toMinutes()
        return LEAST_AUTHORITY.plusMinutes((span * quality).toLong())
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
