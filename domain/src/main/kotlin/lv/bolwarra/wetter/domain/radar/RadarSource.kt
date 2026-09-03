package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import java.time.Instant

/**
 * Somewhere recent radar sweeps come from.
 *
 * Deliberately small, and deliberately not named after any service. Radar
 * licensing is the most restrictive part of this whole subject - national
 * composites and OPERA are each governed differently, and the source that is
 * legal and available in one country is neither in the next - so which service
 * answers has to be a routing decision rather than something the nowcaster knows
 * about (docs/providers.md).
 *
 * Everything behind this returns fields already converted to millimetres per
 * hour, because turning reflectivity into a rate depends on the radar, on the
 * assumed drop-size distribution, and on whether the echo is rain or snow. That
 * is knowledge the adapter has and the engine should never acquire.
 */
interface RadarSource {

    /** Stable identifier, persisted with cached frames. */
    val id: String

    /** Shown verbatim wherever the radar is used. Several sources require it. */
    val attribution: String

    /**
     * How often this source publishes a new sweep.
     *
     * Lets a caller work out when one is next due rather than polling to find
     * out, which is the difference between a request every ten minutes and a
     * request every minute for the same information.
     */
    val sweepInterval: Duration

    /**
     * When the newest available sweep was taken, without fetching it.
     *
     * The cheap half of the transaction. Radar imagery is expensive - a usable
     * block of tiles is tens of kilobytes - while the index saying what exists
     * is a fraction of that, so asking "is there anything new" separately from
     * "give me it" is what keeps a phone from re-downloading the same sweep.
     *
     * Null when the source has nothing at all.
     */
    suspend fun latestSweep(): Result<Instant?>

    /**
     * The most recent sweeps covering a place, oldest first.
     *
     * @param frames how many sweeps back to fetch. Motion needs two and is
     *   steadier with three; asking for many more costs requests for very little.
     */
    suspend fun recentFrames(
        latitude: Double,
        longitude: Double,
        frames: Int,
    ): Result<List<RadarField>>
}
