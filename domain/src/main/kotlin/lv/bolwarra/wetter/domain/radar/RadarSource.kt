// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.radar

import java.time.Duration
import java.time.Instant

/**
 * Somewhere recent radar sweeps come from, deliberately not named after any
 * service: radar licensing differs by country, so which service answers is a
 * routing decision (docs/providers.md).
 *
 * Fields arrive already in millimetres per hour; converting reflectivity depends
 * on the radar and belongs to the adapter.
 */
interface RadarSource {

    /** Stable identifier, persisted with cached frames. */
    val id: String

    /** Shown verbatim wherever the radar is used. Several sources require it. */
    val attribution: String

    /**
     * How often this source publishes a new sweep, so a caller can work out when one
     * is due rather than polling.
     */
    val sweepInterval: Duration

    /**
     * When the newest available sweep was taken, without fetching it: the index is a
     * fraction of the imagery's size, so a phone never re-downloads the same sweep.
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

    /**
     * The same sweeps over a much wider area at coarser resolution, for the map.
     * Nothing by default: a source that cannot offer it has no map.
     */
    suspend fun wideFrames(
        latitude: Double,
        longitude: Double,
        frames: Int,
    ): Result<List<RadarField>> = Result.success(emptyList())
}
