package lv.bolwarra.wetter.domain.hazard

import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.climate.Climatology

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
 * ### The dial says what is dangerous. This says what is worth waking up for.
 *
 * Those are not the same question, and collapsing them into one was measured to
 * fail badly. [Hazards] lets an absolute danger through wherever it is reached -
 * correctly, because forty-one degrees of heat index is dangerous to a body
 * whatever that body is used to, and the amber mark belongs on the dial. But
 * replaying a year of archive through it showed what that does to a phone:
 *
 * | place | days warned | of which danger |
 * |---|---|---|
 * | Doha | 44% | 129 heat |
 * | Yakutsk | 31% | 104 cold |
 * | Phoenix | 21% | 64 heat |
 *
 * A danger notification every other day through a Gulf summer is not a warning
 * system, it is a weather app somebody uninstalls in July. Meanwhile Rīga sat at
 * 4.9% and Quito at zero, so the thresholds themselves are sound - what is wrong
 * is treating "dangerous" as "worth interrupting somebody about".
 *
 * So a warning must clear the place's own bar as well: past the 95th percentile
 * of what this date does here. In Doha that turns 129 heat dangers into about
 * eighteen, and the eighteen are the days that are hard *for Doha*. Nothing is
 * hidden - every one of those days still carries its mark on the dial, which is
 * where a reader who wants to know what the afternoon is like will look.
 *
 * Rain, snow and ice are exempt, for the reason they are exempt in [Hazards]:
 * their thresholds are rates rather than climates, and a wet place has more such
 * hours without having them daily.
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
    fun due(
        hazards: List<Hazard>,
        said: Set<String>,
        zone: ZoneId,
        climate: Climatology? = null,
    ): List<HazardAnnouncement> = hazards
        .filter { it.kind.isWorthWaking }
        .filter { isUnusualHere(it, zone, climate) }
        .map { HazardAnnouncement(it, keyFor(it, zone)) }
        .filter { it.key !in said }

    /**
     * Whether this would be out of the ordinary here, which is a different
     * question from whether it is dangerous.
     *
     * With no climatology every hazard passes: an archive that did not answer
     * must not silence the warnings.
     */
    fun isUnusualHere(hazard: Hazard, zone: ZoneId, climate: Climatology?): Boolean {
        val normal = climate?.at(hazard.from.atZone(zone).toLocalDate()) ?: return true
        val peak = hazard.peak ?: return true

        return when (hazard.kind) {
            HazardKind.EXTREME_HEAT -> normal.warmTail?.let { peak >= it } ?: true

            // The peak arrives negated, the way Hazards judges it, so it is
            // turned back the right way up to compare with a temperature.
            HazardKind.EXTREME_COLD -> normal.coldTail?.let { -peak <= it } ?: true

            HazardKind.DAMAGING_WIND -> normal.gustTail?.let { peak >= it } ?: true

            // Rates, not climates. See the note on this object.
            HazardKind.TORRENTIAL_RAIN,
            HazardKind.HEAVY_SNOW,
            HazardKind.ICE,
            HazardKind.THUNDERSTORM,
            -> true

            HazardKind.EXTREME_UV, HazardKind.UNBREATHABLE_AIR -> false
        }
    }

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
