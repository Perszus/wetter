package lv.bolwarra.wetter.domain.model

import java.time.Duration
import java.time.Instant

/**
 * An unbroken run of hours in which something is falling.
 *
 * This is the shape of the answer to the questions the app exists for — when
 * does it start, how hard does it get, when does it stop — so it is a type
 * rather than something each screen works out for itself.
 */
data class PrecipitationSpell(
    /** The start of the first wet hour. */
    val start: Instant,
    /**
     * The start of the first dry hour after it — exclusive, so a single wet hour
     * at 14:00 runs 14:00 to 15:00 and lasts one hour.
     */
    val end: Instant,
    val peak: PrecipitationIntensity,
    val kind: PrecipitationKind,
    /** Total accumulation across the spell, in millimetres. */
    val totalMillimetres: Double,
    /**
     * True when the spell was still going at the end of the forecast.
     *
     * The difference matters to what the app is allowed to say. A spell that
     * ended has a time it stopped; one that ran off the end of the data does
     * not, and claiming one would be inventing it.
     */
    val isOpenEnded: Boolean,
) {
    val duration: Duration get() = Duration.between(start, end)

    val hours: Long get() = duration.toHours()
}
