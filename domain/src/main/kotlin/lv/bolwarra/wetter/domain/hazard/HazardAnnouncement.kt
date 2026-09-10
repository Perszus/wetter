package lv.bolwarra.wetter.domain.hazard

import java.time.Instant
import java.time.ZoneId

/**
 * A hazard worth interrupting somebody for, and the name it is remembered by.
 *
 * The key is the whole of the dedup mechanism. It is deliberately not the
 * hazard's start time: a storm forecast for six is forecast for half past five
 * on the next run and for a quarter to six on the one after, and a key built
 * from that would announce the same storm every fifteen minutes all night.
 */
data class HazardAnnouncement(val hazard: Hazard, val key: String)

/**
 * Which of the hazards in the forecast have not been said yet.
 *
 * Detection is [Hazards]' job and is already done - this decides only what is
 * worth waking a phone for, and what has already been said.
 *
 * ### One line per kind per day, and an upgrade is news
 *
 * The key is the kind, the severity, and the local day the hazard starts on.
 * Kind and day together mean a frost warned about at nine in the evening is not
 * warned about again at midnight, at three, and at six. Severity is in there
 * because a warning that becomes a danger is a different message and worth a
 * second one - the plan that was fine for a gale is not fine for a storm.
 *
 * Nothing that survives the filter below is capped or held back. Two at once is
 * a genuinely unusual day and both halves of it are worth knowing: a blizzard
 * and the gale driving it are one afternoon and two different problems.
 *
 * ### Not everything on the dial is worth a phone buzzing
 *
 * [Hazards] sets a high bar already - twenty millimetres of rain in the hour is
 * drains losing, Beaufort 8 is twigs coming off trees, four centimetres of snow
 * in an hour is a road closing. None of that fires for a wet Tuesday.
 *
 * Two of the nine still do not belong in a notification, and they are excluded
 * here rather than by moving their thresholds, because both are right where
 * they are on the dial:
 *
 * - **Extreme ultraviolet** is a sunny afternoon. At the WHO's "very high" band
 *   it is most summer days across half the world - genuinely worth a hat and
 *   not worth a phone going off, and an alert that fires every clear day in
 *   July trains somebody to swipe the storm away with it.
 * - **Unhealthy air** is a reading of right now from a different service, not a
 *   forecast of an event. In a city that has it, it has it for a season. There
 *   is no moment it arrives at, which is what a warning is for.
 *
 * What is left is weather that happens *to* you: storms, gales, torrential
 * rain, heavy snow, ice, and the two ends of the thermometer.
 *
 * ### It says things that have already started
 *
 * A hazard is announced whether or not it has begun. Somebody indoors does not
 * know a squall arrived ten minutes ago, and the phone that would have told
 * them may have been out of signal when it was still in the future. What
 * changes is the wording, not whether it is said.
 */
object HazardAnnouncements {

    /**
     * The hazards that have not been announced, worst first.
     *
     * @param said the keys already announced for this place. Anything not in
     *   here is new, which on a fresh install is everything - correctly, since
     *   nothing has been said to that reader yet.
     */
    fun due(hazards: List<Hazard>, said: Set<String>, zone: ZoneId): List<HazardAnnouncement> =
        hazards
            .filter { it.kind.isWorthWaking }
            .map { HazardAnnouncement(it, keyFor(it, zone)) }
            .filter { it.key !in said }

    /**
     * Whether this kind is an event that arrives, or a condition that is simply
     * the case. Only the first sort is worth interrupting somebody for.
     */
    val HazardKind.isWorthWaking: Boolean
        get() = when (this) {
            HazardKind.EXTREME_HEAT,
            HazardKind.EXTREME_COLD,
            HazardKind.DAMAGING_WIND,
            HazardKind.TORRENTIAL_RAIN,
            HazardKind.HEAVY_SNOW,
            HazardKind.ICE,
            HazardKind.THUNDERSTORM,
            -> true

            HazardKind.EXTREME_UV, HazardKind.UNBREATHABLE_AIR -> false
        }

    /** The name one hazard is remembered by, for as long as it matters. */
    fun keyFor(hazard: Hazard, zone: ZoneId): String {
        val day = hazard.from.atZone(zone).toLocalDate()
        return "${hazard.kind.name}:${hazard.severity.name}:$day"
    }

    /**
     * When a key stops being worth remembering.
     *
     * A day past the end of the window anything could have been announced for.
     * Sooner would let a hazard on the far edge of the horizon be announced
     * twice; much later only keeps rows nothing will ever ask about again.
     */
    fun forgettableBefore(now: Instant): Instant = now.minus(Hazards.HORIZON).minus(Hazards.HORIZON)
}
