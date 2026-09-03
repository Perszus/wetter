package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.roundToInt

/** One projected sweep: where the rain is expected to be, and how much to believe it. */
data class RadarNowcastStep(
    val at: Instant,
    val lead: Duration,
    val field: RadarField,
    /** 0..1. Falls with lead time, because extrapolation stops working. */
    val confidence: Float,
)

/** A radar-derived projection forward from the latest sweep. */
data class RadarNowcast(
    val issuedAt: Instant,
    val motion: MotionField,
    val steps: List<RadarNowcastStep>,
) {
    /**
     * The rate expected at a place over time.
     *
     * Steps where the trajectory left the grid or crossed a coverage hole are
     * dropped rather than reported as dry - off the edge of the radar we do not
     * know, and the fusion layer must be able to tell that from a forecast of no
     * rain.
     */
    fun seriesAt(latitude: Double, longitude: Double): List<RadarSample> =
        steps.mapNotNull { step ->
            step.field.rateAt(latitude, longitude)?.let {
                RadarSample(step.at, step.lead, it, step.confidence)
            }
        }
}

data class RadarSample(
    val at: Instant,
    val lead: Duration,
    val millimetresPerHour: Float,
    val confidence: Float,
)

/**
 * Projects the observed rain field forward.
 *
 * ### Backward trajectories, not forward scatter
 *
 * The obvious implementation pushes each pixel to where it is going. It leaves
 * holes: where the flow spreads out, no source pixel lands, and the output is
 * torn with dry gaps that were never forecast. So the loop runs the other way -
 * for each output pixel, ask where the air arriving there came from, and sample
 * that. Every output pixel gets exactly one answer and no tearing is possible.
 *
 * The trajectory is traced in two half steps rather than one whole one. With a
 * flow that varies in space, taking the velocity at the destination for the
 * entire journey is wrong wherever the flow curves; sampling again at the
 * midpoint corrects most of it for one extra lookup.
 *
 * ### Growth and decay, but on a leash
 *
 * Pure advection says a shower will still be exactly as heavy in two hours,
 * which is never true. The intensity trend of each block is measured across the
 * recent sweeps and carried forward - but its cumulative effect saturates,
 * because a cell that has been intensifying for ten minutes is not going to keep
 * intensifying for two hours. Without that ceiling the arithmetic happily
 * forecasts negative rain, or a thunderstorm over a drizzle.
 */
object RadarNowcaster {

    /**
     * How long the observed trend keeps contributing. A cell growing steadily
     * gains at most trend x this, no matter how far ahead we look.
     */
    const val TREND_SATURATION_MINUTES = 20.0

    /**
     * The lead at which radar skill has halved, and the model is about to take
     * the answer over.
     *
     * Radar and a model are good at opposite ends. Radar is looking at rain that
     * exists, so for the next hour it is simply better; a model has to be told
     * what is currently overhead and is not. Past a couple of hours the
     * extrapolation has lost the plot - storms grow, die and turn - and the
     * model is the only thing left worth having. Just under two hours is where
     * those two curves cross.
     */
    const val CROSSOVER_MINUTES = 110.0

    /**
     * How sharply skill falls away either side of the crossover.
     *
     * Two, which makes this a sigmoid rather than an exponential, and that shape
     * matters. An exponential decays fastest at zero lead - precisely where
     * radar is at its best and should be losing nothing at all. This holds near
     * the top for the first half hour, then gives way.
     */
    private const val SKILL_STEEPNESS = 2.0

    /**
     * The match sharpness a genuinely good estimate reads, measured rather than
     * assumed.
     *
     * This is the correction to a units error that had been quietly gutting the
     * radar. Sharpness is a peak-contrast measure - how much better the best
     * displacement fits than the average one - and on real precipitation fields
     * a good match reads about 0.3. It never approaches 1. Feeding it straight
     * in as though it were a probability therefore capped radar at roughly a
     * third of the answer even for the current minute: measured across ten
     * European sites, sharpness ran 0.16 to 0.38, so radar was being given
     * 15-36% weight at zero lead while the model carried the rest. For an app
     * whose whole purpose is that radar knows what is happening now, that was
     * backwards.
     */
    const val GOOD_MATCH_SHARPNESS = 0.30f

    /** Below this there is no trackable structure and the motion is a guess. */
    const val MINIMUM_MATCH_SHARPNESS = 0.10f

    /**
     * How quickly the answer comes to depend on the motion estimate.
     *
     * At zero lead it does not depend on it at all: that is the sweep itself, an
     * observation of rain that is currently falling, and it is worth believing
     * whether or not anything could be tracked between frames. Only the
     * projection needs the motion, and the further ahead it reaches the more
     * entirely it rests on it. Around twenty minutes the two have swapped over.
     *
     * This is why a flat, featureless rain field - London, at 0.16 - is still
     * trusted completely about the present moment and discounted quickly for
     * anything beyond it.
     */
    const val MOTION_RELEVANCE_MINUTES = 20.0

    /** Rain cannot be negative, and a trend extrapolated downward will try. */
    private const val MIN_RATE = 0f

    /** Beyond this the trend is noise rather than growth. */
    private const val MAX_TREND_MM_PER_HOUR_PER_MINUTE = 2.0f

    /**
     * @param recent sweeps in ascending time order. The last is the starting
     *   point; earlier ones are what the motion and trend are measured from.
     */
    fun nowcast(recent: List<RadarField>, leads: List<Duration>): RadarNowcast? {
        if (recent.size < 2 || leads.isEmpty()) return null
        val frames = recent.sortedBy { it.at }
        val latest = frames.last()
        val motion = MotionEstimator.estimate(frames[frames.size - 2], latest) ?: return null

        val trend = estimateTrend(frames, motion)

        // Zero is kept, unlike before: that step is the latest sweep itself -
        // what is falling right now, observed rather than predicted - and it is
        // the single most reliable thing this whole package produces.
        val steps = leads.filter { !it.isNegative }.map { lead ->
            val minutes = lead.toMillis() / MILLIS_PER_MINUTE
            RadarNowcastStep(
                at = latest.at.plus(lead),
                lead = lead,
                field = advance(latest, motion, trend, minutes),
                confidence = confidenceAt(minutes, motion.confidence),
            )
        }
        return RadarNowcast(issuedAt = latest.at, motion = motion, steps = steps)
    }

    /**
     * How much a projected step deserves to be believed.
     *
     * Two independent things multiplied: how well radar can see this far ahead
     * at all, and how much this particular estimate can be trusted to get it
     * there. Keeping them apart is the point - the first is a property of radar,
     * the second of tonight's rain field, and collapsing them into one number is
     * what made the near term unreliable.
     */
    fun confidenceAt(minutes: Double, motionSharpness: Float): Float {
        val quality = matchQualityOf(motionSharpness)
        // Near zero lead the sweep stands on its own; further out it leans
        // entirely on the motion estimate.
        val leaning = exp(-minutes / MOTION_RELEVANCE_MINUTES)
        val effective = quality + (1.0 - quality) * leaning
        return (skillAtLead(minutes) * effective).toFloat().coerceIn(0f, 1f)
    }

    /** How far radar can usefully see, as a share of its best, 0..1. */
    fun skillAtLead(minutes: Double): Double =
        1.0 / (1.0 + Math.pow(minutes.coerceAtLeast(0.0) / CROSSOVER_MINUTES, SKILL_STEEPNESS))

    /**
     * A raw sharpness reading turned into a 0..1 quality.
     *
     * Normalised against what a good match actually measures, so an ordinary
     * good one scores full marks and only a genuinely structureless field is
     * marked down.
     */
    fun matchQualityOf(sharpness: Float): Double {
        val span = (GOOD_MATCH_SHARPNESS - MINIMUM_MATCH_SHARPNESS).toDouble()
        return ((sharpness - MINIMUM_MATCH_SHARPNESS) / span).coerceIn(0.0, 1.0)
    }

    /**
     * One projected field.
     *
     * Blocks with no measured trend simply advect, which is the correct default:
     * absent evidence of growth, the honest forecast is that the rain carries on
     * as it is.
     */
    private fun advance(
        latest: RadarField,
        motion: MotionField,
        trend: FloatArray,
        minutes: Double,
    ): RadarField {
        val geometry = latest.geometry
        val out = FloatArray(geometry.width * geometry.height)
        val boost = TREND_SATURATION_MINUTES * (1.0 - exp(-minutes / TREND_SATURATION_MINUTES))

        for (y in 0 until geometry.height) {
            for (x in 0 until geometry.width) {
                val source = traceBack(motion, x.toFloat(), y.toFloat(), minutes)
                val advected = latest.sampleAt(source)
                if (advected.isNoEcho()) {
                    out[y * geometry.width + x] = RadarField.NO_ECHO
                    continue
                }
                val block = blockIndexAt(motion, x, y)
                val grown = advected + (trend[block] * boost).toFloat()
                out[y * geometry.width + x] = grown.coerceAtLeast(MIN_RATE)
            }
        }
        return RadarField(
            latest.at.plus(Duration.ofMinutes(minutes.roundToInt().toLong())),
            geometry,
            out,
        )
    }

    /**
     * Where the rain now at (x, y) will have come from.
     *
     * Half a step at the arrival velocity, re-read there, then the whole step at
     * that better estimate - the midpoint rule. Straight-line tracing at the
     * arrival velocity is only right where the flow is uniform.
     */
    private fun traceBack(motion: MotionField, x: Float, y: Float, minutes: Double): GridPoint {
        val arrival = motion.at(x, y)
        val halfX = x - arrival.x * (minutes / 2).toFloat()
        val halfY = y - arrival.y * (minutes / 2).toFloat()
        val midpoint = motion.at(halfX, halfY)
        return GridPoint(x - midpoint.x * minutes.toFloat(), y - midpoint.y * minutes.toFloat())
    }

    private fun blockIndexAt(motion: MotionField, x: Int, y: Int): Int {
        val column = (x / motion.blockSize).coerceIn(0, motion.blocksAcross - 1)
        val row = (y / motion.blockSize).coerceIn(0, motion.blocksDown - 1)
        return row * motion.blocksAcross + column
    }

    /**
     * How fast each block is intensifying, in mm/h per minute.
     *
     * Measured after motion compensation, which is the whole point: comparing
     * the same map square across two sweeps measures rain arriving and leaving,
     * not rain growing. Comparing a square against where its contents came from
     * measures the change in the weather itself.
     */
    private fun estimateTrend(frames: List<RadarField>, motion: MotionField): FloatArray {
        val blocks = motion.blocksAcross * motion.blocksDown
        val trend = FloatArray(blocks)
        if (frames.size < 2) return trend

        val latest = frames.last()
        val earlier = frames[frames.size - 2]
        val minutes = Duration.between(earlier.at, latest.at).toMillis() / MILLIS_PER_MINUTE
        if (minutes <= 0.0) return trend

        val sumNow = DoubleArray(blocks)
        val sumThen = DoubleArray(blocks)
        val counts = IntArray(blocks)

        for (y in 0 until latest.height) {
            for (x in 0 until latest.width) {
                val now = latest[x, y]
                if (now.isNoEcho()) continue
                val source = traceBack(motion, x.toFloat(), y.toFloat(), minutes)
                val then = earlier.sampleAt(source)
                if (then.isNoEcho()) continue
                // Only where something is actually falling: averaging in the dry
                // majority would bury every real trend under its own area.
                if (now < RadarField.TRACE_MM_PER_HOUR &&
                    then < RadarField.TRACE_MM_PER_HOUR
                ) {
                    continue
                }
                val block = blockIndexAt(motion, x, y)
                sumNow[block] += now
                sumThen[block] += then
                counts[block]++
            }
        }

        for (block in 0 until blocks) {
            if (counts[block] < MIN_TREND_SAMPLES) continue
            val rate = ((sumNow[block] - sumThen[block]) / counts[block] / minutes).toFloat()
            trend[block] = rate.coerceIn(
                -MAX_TREND_MM_PER_HOUR_PER_MINUTE,
                MAX_TREND_MM_PER_HOUR_PER_MINUTE,
            )
        }
        return trend
    }

    /** A handful of pixels is not a trend. */
    private const val MIN_TREND_SAMPLES = 32

    private const val MILLIS_PER_MINUTE = 60_000.0
}
