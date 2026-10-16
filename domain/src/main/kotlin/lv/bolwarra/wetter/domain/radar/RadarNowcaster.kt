// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
    /** 0..1, falling with lead time as extrapolation stops working. */
    val confidence: Float,
)

/** A radar-derived projection forward from the latest sweep. */
data class RadarNowcast(
    val issuedAt: Instant,
    val motion: MotionField,
    val steps: List<RadarNowcastStep>,
) {
    /**
     * The rate expected at a place over time. Steps whose trajectory left the grid or
     * crossed a coverage hole are dropped rather than reported as dry.
     */
    fun seriesAt(latitude: Double, longitude: Double): List<RadarSample> {
        // Say nothing where the radar is not watching: the source draws "no echo" and
        // "outside coverage" identically, so a dry reading counts only where there is echo
        // in the neighbourhood at some step. Every step is checked, so rain approaching
        // from beyond the neighbourhood still counts.
        val observing = steps.any { it.field.hasEchoNear(latitude, longitude, COVERAGE_RADIUS) }
        if (!observing) return emptyList()

        return steps.mapNotNull { step ->
            step.field.rateAt(latitude, longitude)?.let {
                RadarSample(
                    at = step.at,
                    lead = step.lead,
                    millimetresPerHour = it,
                    confidence = step.confidence,
                    motionQuality = RadarNowcaster.matchQualityOf(motion.confidence).toFloat(),
                )
            }
        }
    }

    private companion object {
        /**
         * How far to look for evidence that the composite covers this place: one motion
         * block, about forty kilometres.
         */
        const val COVERAGE_RADIUS = 64
    }
}

data class RadarSample(
    val at: Instant,
    val lead: Duration,
    val millimetresPerHour: Float,
    val confidence: Float,
    /**
     * How well the motion behind this projection was measured, 0..1, kept apart from
     * [confidence] (which also folds in lead decay) and carried on each sample so a
     * projection read back from disk still knows it.
     */
    val motionQuality: Float = 1f,
)

/**
 * Projects the observed rain field forward.
 *
 * Backward trajectories rather than forward scatter: for each output pixel, trace
 * where its air came from and sample there, so the output cannot tear. The trace
 * uses the midpoint rule, which handles curving flow.
 *
 * Each block's measured intensity trend is carried forward, but its cumulative
 * effect saturates ([TREND_SATURATION_MINUTES]), so a growing cell is not
 * extrapolated into a deluge.
 */
object RadarNowcaster {

    /** How long the observed trend keeps contributing to a projection. */
    const val TREND_SATURATION_MINUTES = 20.0

    /**
     * The lead at which radar skill has halved and the models are about to take the
     * answer over: just under two hours.
     */
    const val CROSSOVER_MINUTES = 110.0

    /**
     * How sharply skill falls either side of the crossover: a sigmoid, so it holds
     * near the top for the first half hour instead of decaying fastest at zero.
     */
    private const val SKILL_STEEPNESS = 2.0

    /**
     * The match sharpness a good motion estimate actually reads. Sharpness is a peak
     * contrast, and on real fields a good match is about 0.3, never near 1.
     */
    const val GOOD_MATCH_SHARPNESS = 0.30f

    /** Below this there is no trackable structure and the motion is a guess. */
    const val MINIMUM_MATCH_SHARPNESS = 0.10f

    /**
     * How quickly the answer comes to depend on the motion estimate. At zero lead it
     * does not at all, since that step is the sweep itself; by about twenty minutes it
     * rests on it, so a featureless field is trusted now and discounted after.
     */
    const val MOTION_RELEVANCE_MINUTES = 20.0

    /** Rain cannot be negative, and a trend extrapolated downward will try. */
    private const val MIN_RATE = 0f

    /** Beyond this the trend is noise rather than growth. */
    private const val MAX_TREND_MM_PER_HOUR_PER_MINUTE = 2.0f

    /**
     * @param recent sweeps in ascending order: the last is the starting point, the
     * earlier ones measure the motion and trend.
     */
    fun nowcast(recent: List<RadarField>, leads: List<Duration>): RadarNowcast? =
        project(recent, leads, around = null)

    /**
     * The same projection, computed only in a window around one place, wide enough
     * for [RadarNowcast.seriesAt] and its coverage check. Every window pixel is still
     * traced through the full motion field and sampled from the whole sweep, so rain
     * from outside arrives as it would; only pixels never read are skipped.
     *
     * Null where the place is off the grid.
     */
    fun nowcastAround(
        recent: List<RadarField>,
        leads: List<Duration>,
        latitude: Double,
        longitude: Double,
    ): RadarNowcast? {
        val latest = recent.maxByOrNull { it.at } ?: return null
        val point = latest.geometry.pointOf(latitude, longitude) ?: return null
        // The coverage radius, plus one for the bilinear sample's neighbour.
        val reach = AROUND_RADIUS + 1
        val x0 = (point.x.toInt() - reach).coerceAtLeast(0)
        val y0 = (point.y.toInt() - reach).coerceAtLeast(0)
        val x1 = (point.x.toInt() + reach + 1).coerceAtMost(latest.width - 1)
        val y1 = (point.y.toInt() + reach + 1).coerceAtMost(latest.height - 1)
        return project(recent, leads, around = Window(x0, y0, x1 - x0 + 1, y1 - y0 + 1))
    }

    /** A rectangle of the grid, in grid pixels. */
    private data class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /** How far around the point [nowcastAround] works: the coverage check's reach. */
    private const val AROUND_RADIUS = 64

    private fun project(
        recent: List<RadarField>,
        leads: List<Duration>,
        around: Window?,
    ): RadarNowcast? {
        if (recent.size < 2 || leads.isEmpty()) return null
        val frames = recent.sortedBy { it.at }
        val latest = frames.last()
        // Every frame held: a long baseline finds motion on smooth fields, and the two
        // spans check each other.
        val motion = MotionEstimator.estimate(frames) ?: return null

        val trend = estimateTrend(frames, motion)

        // Zero lead is kept: it is the latest sweep itself, the most reliable step.
        val steps = leads.filter { !it.isNegative }.map { lead ->
            val minutes = lead.toMillis() / MILLIS_PER_MINUTE
            RadarNowcastStep(
                at = latest.at.plus(lead),
                lead = lead,
                field = advance(latest, motion, trend, minutes, around),
                confidence = confidenceAt(minutes, motion.confidence),
            )
        }
        return RadarNowcast(issuedAt = latest.at, motion = motion, steps = steps)
    }

    /**
     * How much a projected step deserves belief: radar's skill at this lead, times
     * how far this particular motion estimate can be trusted to carry it.
     */
    fun confidenceAt(minutes: Double, motionSharpness: Float): Float {
        val quality = matchQualityOf(motionSharpness)
        // Near zero lead the sweep stands alone; further out it rests on the motion.
        val leaning = exp(-minutes / MOTION_RELEVANCE_MINUTES)
        val effective = quality + (1.0 - quality) * leaning
        return (skillAtLead(minutes) * effective).toFloat().coerceIn(0f, 1f)
    }

    /** How far radar can usefully see, as a share of its best, 0..1. */
    fun skillAtLead(minutes: Double): Double =
        1.0 / (1.0 + Math.pow(minutes.coerceAtLeast(0.0) / CROSSOVER_MINUTES, SKILL_STEEPNESS))

    /**
     * A raw sharpness reading as a 0..1 quality, normalised against what a good
     * match measures.
     */
    fun matchQualityOf(sharpness: Float): Double {
        val span = (GOOD_MATCH_SHARPNESS - MINIMUM_MATCH_SHARPNESS).toDouble()
        return ((sharpness - MINIMUM_MATCH_SHARPNESS) / span).coerceIn(0.0, 1.0)
    }

    /**
     * One projected field. Blocks with no measured trend simply advect: absent
     * evidence of growth, rain carries on as it is.
     */
    private fun advance(
        latest: RadarField,
        motion: MotionField,
        trend: FloatArray,
        minutes: Double,
        around: Window? = null,
    ): RadarField {
        val full = latest.geometry
        val window = around ?: Window(0, 0, full.width, full.height)
        // The output covers the window, computed in the full grid's coordinates so each
        // pixel matches a whole-grid projection.
        val geometry = if (around == null) {
            full
        } else {
            RadarGeometry(
                zoom = full.zoom,
                originX = full.originX + window.x,
                originY = full.originY + window.y,
                width = window.width,
                height = window.height,
            )
        }
        val out = FloatArray(geometry.width * geometry.height)
        val boost = TREND_SATURATION_MINUTES * (1.0 - exp(-minutes / TREND_SATURATION_MINUTES))

        for (wy in 0 until geometry.height) {
            for (wx in 0 until geometry.width) {
                val x = wx + window.x
                val y = wy + window.y
                val source = traceBack(motion, x.toFloat(), y.toFloat(), minutes)
                val advected = latest.sampleAt(source)
                if (advected.isNoEcho()) {
                    out[wy * geometry.width + wx] = RadarField.NO_ECHO
                    continue
                }
                // Growth only where rain is, using the trend of the block the rain came from,
                // where it was measured growing.
                if (advected < RadarField.TRACE_MM_PER_HOUR) {
                    out[wy * geometry.width + wx] = advected.coerceAtLeast(MIN_RATE)
                    continue
                }
                val block = blockIndexAt(motion, source.x.toInt(), source.y.toInt())
                val grown = advected + (trend[block] * boost).toFloat()
                out[wy * geometry.width + wx] = grown.coerceAtLeast(MIN_RATE)
            }
        }
        return RadarField(
            latest.at.plus(Duration.ofMinutes(minutes.roundToInt().toLong())),
            geometry,
            out,
        )
    }

    /**
     * Where the rain now at (x, y) will have come from: half a step at the arrival
     * velocity, re-read there, then the whole step (the midpoint rule).
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
     * How fast each block is intensifying, in mm/h per minute, measured after motion
     * compensation so it is growth rather than rain arriving and leaving.
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
                // Only where something is falling, so the dry majority does not bury the trend.
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
