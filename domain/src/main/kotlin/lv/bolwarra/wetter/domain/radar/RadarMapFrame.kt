package lv.bolwarra.wetter.domain.radar

import java.time.Instant

/**
 * One picture on the radar map: the rain field at an instant.
 *
 * [observed] is true for a sweep the radar actually took and false for a step
 * projected forward from the newest one. The map does not label the two
 * differently - the time beside it already says whether it is past or ahead -
 * but anything that wants to treat them differently can.
 */
data class RadarMapFrame(val at: Instant, val field: RadarField, val observed: Boolean)

/**
 * The map's frames so far, and whether the projection is still to come.
 *
 * The observed sweeps arrive in seconds and the projection takes much longer
 * to work out, so the map is shown in two parts - and needs to know which it is
 * looking at, so the time axis can be laid out for the whole loop from the
 * start rather than jumping when the second part lands.
 */
data class RadarMapPicture(val frames: List<RadarMapFrame>, val projecting: Boolean)
