package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a shower out of an hourly forecast.
 *
 * These are the answers the whole app is built to give — when does it start, how
 * hard does it get, when does it stop — so the awkward cases matter: a gap in
 * the middle, a spell that has already begun, and one that runs off the end of
 * the data and therefore has no end anybody can honestly state.
 */
class HourlySeriesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Riga")

    /** Midnight local on 14 March 2026, which is 22:00 UTC on the 13th. */
    private val midnight: Instant = LocalDate.of(2026, 3, 14).atStartOfDay(zone).toInstant()

    private fun at(hour: Int): Instant = midnight.plus(Duration.ofHours(hour.toLong()))

    /** One row per millimetre figure, starting at local midnight. */
    private fun series(
        vararg millimetres: Double?,
        condition: WeatherCondition = WeatherCondition.RAIN,
    ): List<HourlyWeather> = millimetres.mapIndexed { hour, mm ->
        HourlyWeather(
            timestamp = at(hour),
            temperature = 4.0,
            precipitationProbability = null,
            precipitation = mm,
            rain = mm,
            snowfall = null,
            condition = if (mm != null && mm > 0.0) condition else WeatherCondition.OVERCAST,
            windSpeed = null,
            windGust = null,
            apparentTemperature = null,
            uvIndex = null,
            cloudCover = null,
            isDay = hour in 7..18,
        )
    }

    // --- days ------------------------------------------------------------------

    @Test
    fun `a day is the hours that fall on it locally`() {
        val twoDays = series(*Array(48) { 0.0 })
        val today = twoDays.onDay(LocalDate.of(2026, 3, 14), zone)

        assertEquals(24, today.size)
        assertEquals(at(0), today.first().timestamp)
        assertEquals(at(23), today.last().timestamp)
    }

    @Test
    fun `the day boundary follows the location, not UTC`() {
        // Local midnight here is 22:00 UTC the previous day. An implementation
        // that grouped in UTC would put those two hours on the wrong date.
        val hours = series(*Array(24) { 0.0 })
        assertEquals(24, hours.onDay(LocalDate.of(2026, 3, 14), zone).size)
        assertTrue(hours.onDay(LocalDate.of(2026, 3, 13), zone).isEmpty())
    }

    // --- spells ----------------------------------------------------------------

    @Test
    fun `a dry forecast has no spells`() {
        assertTrue(series(0.0, 0.0, 0.0).precipitationSpells().isEmpty())
        assertTrue(emptyList<HourlyWeather>().precipitationSpells().isEmpty())
    }

    @Test
    fun `a spell ends when its last wet hour ends, not when it begins`() {
        // One wet hour at 02:00 lasts until 03:00. Reporting it as ending at
        // 02:00 would be a zero-length shower.
        val spell = series(0.0, 0.0, 1.5, 0.0).precipitationSpells().single()

        assertEquals(at(2), spell.start)
        assertEquals(at(3), spell.end)
        assertEquals(Duration.ofHours(1), spell.duration)
        assertFalse(spell.isOpenEnded)
    }

    @Test
    fun `contiguous wet hours are one spell`() {
        val spell = series(0.0, 0.6, 1.8, 4.1, 0.0, 0.0).precipitationSpells().single()

        assertEquals(at(1), spell.start)
        assertEquals(at(4), spell.end)
        assertEquals(3L, spell.hours)
        assertEquals(6.5, spell.totalMillimetres, 1e-9)
        assertEquals(PrecipitationIntensity.MODERATE, spell.peak)
    }

    @Test
    fun `a dry hour splits a shower rather than being bridged`() {
        // Told it stops at 03:00, somebody can leave at 03:00. Smoothing the gap
        // away to make the chart tidier would invent rain the forecast does not
        // contain.
        val spells = series(0.0, 2.0, 2.0, 0.0, 3.0, 3.0, 0.0).precipitationSpells()

        assertEquals(2, spells.size)
        assertEquals(at(1), spells[0].start)
        assertEquals(at(3), spells[0].end)
        assertEquals(at(4), spells[1].start)
        assertEquals(at(6), spells[1].end)
    }

    @Test
    fun `a trace below the threshold does not start a spell`() {
        assertTrue(series(0.05, 0.09, 0.0).precipitationSpells().isEmpty())
    }

    @Test
    fun `a spell still going at the end of the data is open-ended`() {
        val spell = series(0.0, 2.0, 2.0).precipitationSpells().single()

        assertTrue("the forecast stops; the weather does not", spell.isOpenEnded)
        assertEquals(at(3), spell.end)
    }

    @Test
    fun `the peak is the heaviest hour in the spell`() {
        val spell = series(0.2, 9.0, 0.3).precipitationSpells().single()
        assertEquals(PrecipitationIntensity.HEAVY, spell.peak)
    }

    @Test
    fun `a spell that turns to sleet is reported as sleet`() {
        // No rain/snow breakdown on these rows, which is MET Norway's shape and
        // the case where the condition is what decides the kind.
        val hours = series(0.0, 2.0, 2.0, 0.0)
            .map { it.copy(rain = null, snowfall = null) }
            .mapIndexed { i, h -> if (i == 2) h.copy(condition = WeatherCondition.SLEET) else h }
        // The awkward part of a spell is the part worth knowing about.
        assertEquals(PrecipitationKind.MIXED, hours.precipitationSpells().single().kind)
    }

    @Test
    fun `a snow spell is snow`() {
        val hours = series(0.0, 2.0, 2.0, 0.0, condition = WeatherCondition.SNOW)
            .map { it.copy(rain = null, snowfall = if (it.intensity.isWet) 2.0 else null) }
        assertEquals(PrecipitationKind.SNOW, hours.precipitationSpells().single().kind)
    }

    // --- what happens next -------------------------------------------------------

    @Test
    fun `the next spell is the one that has not finished yet`() {
        val hours = series(2.0, 2.0, 0.0, 0.0, 5.0, 5.0, 0.0)

        // Standing at 03:00, between the two, the answer is the later one.
        assertEquals(at(4), hours.nextPrecipitation(at(3))!!.start)
    }

    @Test
    fun `rain already falling is the next thing that happens`() {
        val hours = series(0.0, 2.0, 2.0, 2.0, 0.0)

        // Asked at 02:00, mid-shower, "when does it rain" must not skip to
        // tomorrow — the useful answer is that it is raining and stops at 04:00.
        val next = hours.nextPrecipitation(at(2))!!
        assertEquals(at(1), next.start)
        assertEquals(at(4), next.end)
    }

    @Test
    fun `nothing comes back when the rest of the forecast is dry`() {
        val hours = series(2.0, 2.0, 0.0, 0.0)
        assertNull(hours.nextPrecipitation(at(3)))
    }

    @Test
    fun `it is raining now only while a spell is in progress`() {
        val hours = series(0.0, 2.0, 2.0, 0.0)

        assertNull(hours.precipitationAt(at(0)))
        assertEquals(at(1), hours.precipitationAt(at(1))!!.start)
        assertEquals(at(1), hours.precipitationAt(at(2))!!.start)
        assertNull("03:00 is the first dry hour", hours.precipitationAt(at(3)))
    }

    // --- totals -------------------------------------------------------------------

    @Test
    fun `totals add up the reported hours and ignore the unreported ones`() {
        assertEquals(4.0, series(1.0, null, 3.0).totalPrecipitation()!!, 1e-9)
        assertEquals(3.0, series(1.0, null, 3.0).peakPrecipitation()!!, 1e-9)
    }

    @Test
    fun `a series that reports nothing has no total, as opposed to zero`() {
        assertNull(series(null, null).totalPrecipitation())
        assertNull(series(null, null).peakPrecipitation())
    }
}
