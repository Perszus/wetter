// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.hazard

import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.climate.Climatology

/**
 * A hazard worth interrupting somebody for, and the name it is remembered by.
 * The key is not the start time, which shifts between runs and would announce the
 * same storm on every refresh.
 */
data class HazardAnnouncement(val hazard: Hazard, val key: String)

/**
 * Which of the hazards in the forecast have not been said yet. Detection is
 * [Hazards]' job; this decides what is worth a notification.
 *
 * The key is kind, severity and the local day the hazard starts on, so a frost is
 * warned about once a night, while a warning upgraded to a danger is news.
 *
 * Extreme ultraviolet and unhealthy air stay on the dial but never notify: one is
 * an ordinary sunny afternoon across half the world, the other a standing
 * condition with no moment of arrival.
 *
 * What is dangerous is not the same as what is worth an interruption. An
 * absolute danger such as 41° heat index is marked on the dial anywhere, but
 * replayed through a year of archive it would notify on 44% of days in Doha. So a
 * warning must also clear the place's own bar, the 95th percentile of what this
 * date does here ([isUnusualHere]). Rain, snow and ice are exempt: their
 * thresholds are rates, not climates.
 *
 * A hazard that has already begun is still announced, with different wording:
 * somebody indoors may not know.
 */
object HazardAnnouncements {

    /**
     * The hazards that have not been announced, worst first.
     *
     * @param said the keys already announced for this place; on a fresh install,
     * none.
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
     * Whether this would be out of the ordinary here, a different question from
     * whether it is dangerous. With no climatology every hazard passes: a missing
     * archive must not silence the warnings.
     */
    fun isUnusualHere(hazard: Hazard, zone: ZoneId, climate: Climatology?): Boolean {
        val normal = climate?.at(hazard.from.atZone(zone).toLocalDate()) ?: return true
        val peak = hazard.peak ?: return true

        return when (hazard.kind) {
            HazardKind.EXTREME_HEAT -> normal.warmTail?.let { peak >= it } ?: true

            // The peak arrives negated, as Hazards judges it, so it is turned back to compare
            // with a temperature.
            HazardKind.EXTREME_COLD -> normal.coldTail?.let { -peak <= it } ?: true

            HazardKind.DAMAGING_WIND -> normal.gustTail?.let { peak >= it } ?: true

            // Rates, not climates.
            HazardKind.TORRENTIAL_RAIN,
            HazardKind.HEAVY_SNOW,
            HazardKind.ICE,
            HazardKind.THUNDERSTORM,
            -> true

            HazardKind.EXTREME_UV, HazardKind.UNBREATHABLE_AIR -> false
        }
    }

    /**
     * Whether this kind is an event that arrives, or a condition that simply is the
     * case. Only the first is worth interrupting somebody for.
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
     * When a key stops being worth remembering: a day past the end of the window
     * anything could have been announced for.
     */
    fun forgettableBefore(now: Instant): Instant = now.minus(Hazards.HORIZON).minus(Hazards.HORIZON)
}
