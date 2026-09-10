package lv.bolwarra.wetter.domain.hazard

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardAnnouncementTest {

    private val zone: ZoneId = ZoneId.of("Europe/Riga")
    private val now: Instant = Instant.parse("2026-09-10T12:00:00Z")

    private fun hazard(
        kind: HazardKind,
        severity: HazardSeverity = HazardSeverity.WARNING,
        inHours: Long = 6,
        peak: Double? = null,
    ) = Hazard(
        kind = kind,
        severity = severity,
        from = now.plus(Duration.ofHours(inHours)),
        until = now.plus(Duration.ofHours(inHours + 2)),
        peak = peak,
    )

    @Test
    fun `a storm nobody has been told about is due`() {
        val storm = hazard(HazardKind.THUNDERSTORM)
        val due = HazardAnnouncements.due(listOf(storm), said = emptySet(), zone = zone)
        assertEquals(1, due.size)
        assertEquals(storm, due.single().hazard)
    }

    @Test
    fun `the same storm is not announced twice`() {
        val storm = hazard(HazardKind.THUNDERSTORM)
        val key = HazardAnnouncements.keyFor(storm, zone)
        assertTrue(HazardAnnouncements.due(listOf(storm), setOf(key), zone).isEmpty())
    }

    @Test
    fun `a storm that moves in the forecast is still the same storm`() {
        // The whole point of the key. Every refresh nudges the start time by a
        // few minutes; a key built from the instant would fire all night.
        val atSix = hazard(HazardKind.THUNDERSTORM, inHours = 6)
        val atHalfPast = atSix.copy(from = atSix.from.plus(Duration.ofMinutes(35)))
        assertEquals(
            HazardAnnouncements.keyFor(atSix, zone),
            HazardAnnouncements.keyFor(atHalfPast, zone),
        )
    }

    @Test
    fun `a warning that becomes a danger is said again`() {
        // Not a repeat. The plan that was fine for a gale is not fine for a
        // storm, and somebody who read the first one needs to know it got worse.
        val gale = hazard(HazardKind.DAMAGING_WIND, HazardSeverity.WARNING)
        val storm = gale.copy(severity = HazardSeverity.DANGER)
        val alreadySaid = setOf(HazardAnnouncements.keyFor(gale, zone))

        assertNotEquals(
            HazardAnnouncements.keyFor(gale, zone),
            HazardAnnouncements.keyFor(storm, zone),
        )
        assertEquals(1, HazardAnnouncements.due(listOf(storm), alreadySaid, zone).size)
    }

    @Test
    fun `the same hazard on two different days is two warnings`() {
        val tonight = hazard(HazardKind.ICE, inHours = 6)
        val tomorrow = hazard(HazardKind.ICE, inHours = 30)
        assertNotEquals(
            HazardAnnouncements.keyFor(tonight, zone),
            HazardAnnouncements.keyFor(tomorrow, zone),
        )
    }

    @Test
    fun `a sunny afternoon and a bad-air day are not worth waking anybody for`() {
        // Both stay on the dial. Extreme ultraviolet at the WHO's "very high"
        // band is most summer days in much of the world, and unhealthy air is a
        // reading of right now rather than an event that arrives - a phone that
        // buzzed for either would teach somebody to swipe the storm away too.
        val quiet = listOf(
            hazard(HazardKind.EXTREME_UV),
            hazard(HazardKind.UNBREATHABLE_AIR),
        )
        assertTrue(HazardAnnouncements.due(quiet, emptySet(), zone).isEmpty())
    }

    @Test
    fun `weather that happens to you is worth waking somebody for`() {
        val severe = listOf(
            HazardKind.THUNDERSTORM,
            HazardKind.DAMAGING_WIND,
            HazardKind.TORRENTIAL_RAIN,
            HazardKind.HEAVY_SNOW,
            HazardKind.ICE,
            HazardKind.EXTREME_HEAT,
            HazardKind.EXTREME_COLD,
        ).map { hazard(it) }

        assertEquals(severe.size, HazardAnnouncements.due(severe, emptySet(), zone).size)
    }

    @Test
    fun `something already happening is still announced`() {
        // Somebody indoors does not know a squall arrived ten minutes ago, and
        // the phone that would have told them may have had no signal when it
        // was still in the future.
        val underway = hazard(HazardKind.DAMAGING_WIND, inHours = -1)
        assertEquals(1, HazardAnnouncements.due(listOf(underway), emptySet(), zone).size)
    }

    @Test
    fun `keys are forgotten only once nothing could still be announced for them`() {
        // A key must outlive the horizon it was announced from, or a hazard on
        // the far edge of the forecast gets announced a second time when the
        // record of the first has already been swept away.
        val forgettable = HazardAnnouncements.forgettableBefore(now)
        assertTrue(forgettable.isBefore(now.minus(Hazards.HORIZON)))
    }
}
