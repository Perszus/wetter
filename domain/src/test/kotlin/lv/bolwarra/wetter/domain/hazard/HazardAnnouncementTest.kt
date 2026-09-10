package lv.bolwarra.wetter.domain.hazard

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.climate.DayNormal
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

    @Test
    fun `a dangerous day that is ordinary here does not wake anybody`() {
        // Measured by replaying a year of archive: Doha reached the absolute heat
        // danger on 129 days and Yakutsk the absolute cold danger on 104. Both
        // are genuinely dangerous and both belong on the dial; neither is worth
        // a phone going off every other day for two months.
        //
        // Doha's own mid-July tails are 46.8 warning, 47.7 exceptional.
        val doha = climatologyOf(warmTail = 46.8, warmExtreme = 47.7)

        val ordinary = Hazard(
            kind = HazardKind.EXTREME_HEAT,
            severity = HazardSeverity.DANGER,
            from = now.plus(Duration.ofHours(4)),
            until = now.plus(Duration.ofHours(9)),
            peak = 43.0,
        )
        assertTrue(
            "a routine Gulf afternoon should not be announced",
            HazardAnnouncements.due(listOf(ordinary), emptySet(), zone, doha).isEmpty(),
        )

        // And a day that is hard even for Doha still is.
        val exceptional = ordinary.copy(peak = 48.5)
        assertEquals(
            1,
            HazardAnnouncements.due(listOf(exceptional), emptySet(), zone, doha).size,
        )
    }

    @Test
    fun `the same reading in a temperate place is still announced`() {
        // The other half of the same rule. Thirty-five degrees is below Doha's
        // bar and far past Rīga's, whose mid-July tails are 31.7 and 34.9.
        val riga = climatologyOf(warmTail = 31.7, warmExtreme = 34.9)
        val hot = Hazard(
            kind = HazardKind.EXTREME_HEAT,
            severity = HazardSeverity.WARNING,
            from = now.plus(Duration.ofHours(4)),
            until = now.plus(Duration.ofHours(9)),
            peak = 35.0,
        )
        assertEquals(1, HazardAnnouncements.due(listOf(hot), emptySet(), zone, riga).size)
    }

    @Test
    fun `rain and ice are not held to a local bar`() {
        // Their thresholds are rates rather than climates: twenty millimetres in
        // an hour overwhelms drainage anywhere, and a wet place has more such
        // hours without having them daily.
        val anywhere = climatologyOf(warmTail = 40.0, coldTail = -30.0, gustTail = 30.0)
        val downpour = Hazard(
            kind = HazardKind.TORRENTIAL_RAIN,
            severity = HazardSeverity.WARNING,
            from = now.plus(Duration.ofHours(2)),
            until = now.plus(Duration.ofHours(4)),
            peak = 22.0,
        )
        assertEquals(1, HazardAnnouncements.due(listOf(downpour), emptySet(), zone, anywhere).size)
    }

    @Test
    fun `with no climatology nothing is suppressed`() {
        // An archive that did not answer must not silence the warnings.
        val storm = Hazard(
            kind = HazardKind.THUNDERSTORM,
            severity = HazardSeverity.WARNING,
            from = now.plus(Duration.ofHours(3)),
            until = now.plus(Duration.ofHours(5)),
            peak = null,
        )
        assertEquals(1, HazardAnnouncements.due(listOf(storm), emptySet(), zone, null).size)
    }

    private fun climatologyOf(
        warmTail: Double? = null,
        warmExtreme: Double? = null,
        coldTail: Double? = null,
        coldExtreme: Double? = null,
        gustTail: Double? = null,
        gustExtreme: Double? = null,
    ) = Climatology(
        (1..366).map { LocalDate.ofYearDay(2024, it) }
            .map { MonthDay.of(it.month, it.dayOfMonth) }
            .associateWith {
                DayNormal(
                    monthDay = it,
                    medianHigh = null,
                    medianLow = null,
                    wetShare = 0.0,
                    samples = 110,
                    warmTail = warmTail,
                    coldTail = coldTail,
                    gustTail = gustTail,
                    warmExtreme = warmExtreme,
                    coldExtreme = coldExtreme,
                    gustExtreme = gustExtreme,
                )
            },
    )
}
