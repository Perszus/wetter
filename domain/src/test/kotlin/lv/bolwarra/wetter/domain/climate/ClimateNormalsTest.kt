package lv.bolwarra.wetter.domain.climate

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A decade of past days, turned into what a date usually does.
 *
 * The properties worth holding: the window is what makes the answer steady, a
 * median is a value that actually happened, wet is counted rather than averaged
 * so one storm cannot move it, and a date with too little behind it is left out
 * rather than answered badly.
 */
class ClimateNormalsTest {

    private val years = 2015..2024

    /** Ten years of a place whose weather is a pure function of the date. */
    private fun decade(
        high: (LocalDate) -> Double? = { 10.0 },
        low: (LocalDate) -> Double? = { 2.0 },
        rain: (LocalDate) -> Double? = { 0.0 },
    ): List<ArchivedDay> = years.flatMap { year ->
        val start = LocalDate.of(year, 1, 1)
        (0 until start.lengthOfYear()).map { offset ->
            val date = start.plusDays(offset.toLong())
            ArchivedDay(date, high(date), low(date), rain(date))
        }
    }

    @Test
    fun `a steady climate comes back unchanged`() {
        val normals = ClimateNormals.build(decade(high = { 14.0 }, low = { 6.0 }))
        val day = normals.at(LocalDate.of(2026, 9, 30))

        assertNotNull(day)
        assertEquals(14.0, day!!.medianHigh!!, 1e-9)
        assertEquals(6.0, day.medianLow!!, 1e-9)
    }

    @Test
    fun `the window puts a hundred and ten days behind a date`() {
        // Ten years, eleven days each. This is the number the whole design rests
        // on: ten samples is noise with a season under it, and this is what turns
        // it into a normal.
        val normals = ClimateNormals.build(decade())
        val day = normals.at(LocalDate.of(2026, 6, 15))!!

        assertEquals(110, day.samples)
    }

    @Test
    fun `the window smooths a single freak day instead of enshrining it`() {
        // One 30 C day in one year, on a date that is otherwise 10 C. Without a
        // window that date's median across ten years would still be 10 - but the
        // day either side of it would be unaffected, and the point here is the
        // opposite property: the freak cannot move the median at all, because it
        // is one sample in a hundred and ten.
        val freak = LocalDate.of(2019, 7, 4)
        val normals = ClimateNormals.build(
            decade(high = { if (it == freak) 30.0 else 10.0 }),
        )

        assertEquals(10.0, normals.at(LocalDate.of(2026, 7, 4))!!.medianHigh!!, 1e-9)
    }

    @Test
    fun `a seasonal trend survives the window`() {
        // Half a degree a day, which is far steeper than any real season. The
        // window must not flatten it: a normal that smoothed away the seasons
        // would be a yearly average with extra steps.
        val normals = ClimateNormals.build(
            decade(high = { it.dayOfYear * 0.5 }),
        )

        val early = normals.at(LocalDate.of(2026, 3, 1))!!.medianHigh!!
        val later = normals.at(LocalDate.of(2026, 3, 11))!!.medianHigh!!

        assertTrue("expected the trend to survive, got $early then $later", later > early)
        // Ten days apart at half a degree a day. The window is symmetric, so it
        // shifts the level not at all and the gap comes through intact.
        assertEquals(5.0, later - early, 0.51)
    }

    @Test
    fun `wet is a share of days, and one deluge cannot move it`() {
        // Every date bone dry except the 20th of each month, which is soaked.
        // Averaged, that one day would drag its neighbours up; counted, it is one
        // day in eleven and says so.
        val normals = ClimateNormals.build(
            decade(rain = { if (it.dayOfMonth == 20) 90.0 else 0.0 }),
        )

        val onTheDay = normals.at(LocalDate.of(2026, 5, 20))!!.wetShare
        assertEquals("one date in the window is wet", 1.0 / 11.0, onTheDay, 0.02)
    }

    @Test
    fun `a trace of drizzle is not a wet day`() {
        // Half a millimetre across a whole day is a damp pavement. The rain-day
        // threshold is a millimetre and it is an accumulation, not a rate - the
        // distinction that once had a day of drizzle marked as rain.
        val normals = ClimateNormals.build(decade(rain = { 0.5 }))

        assertEquals(0.0, normals.at(LocalDate.of(2026, 5, 20))!!.wetShare, 1e-9)
    }

    @Test
    fun `a date with too little behind it is left out rather than answered`() {
        // Two years is twenty-two samples in a window, under the floor. An empty
        // square is the right drawing of a date nobody can speak for.
        val thin = (2023..2024).flatMap { year ->
            (0 until 365).map {
                val date = LocalDate.of(year, 1, 1).plusDays(it.toLong())
                ArchivedDay(date, 10.0, 2.0, 0.0)
            }
        }

        assertNull(ClimateNormals.build(thin).at(LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun `the twenty-ninth of February gets a normal like any other date`() {
        // It exists in two of these ten years and would have twenty samples of
        // its own. Its window reaches into the days either side, which happen
        // every year, so it clears the floor on the strength of its neighbours -
        // which is exactly what a window is for.
        val normals = ClimateNormals.build(decade())
        val leapDay = normals.at(LocalDate.of(2024, 2, 29))

        assertNotNull("29 February should be reachable", leapDay)
        assertTrue(leapDay!!.samples >= ClimateNormals.LEAST_USEFUL_SAMPLE)
    }

    @Test
    fun `a freezing leap day does not drag the days around it down`() {
        // 29 February exists in two of these ten years, so pooled by calendar
        // date it is at most two samples in any window and cannot move a median.
        // Pooled by day-of-year it would instead be merged into 1 March for the
        // three years in four that have no 29 February - and then a cold leap day
        // is not a rarity at all, it is part of what 1 March is.
        val normals = ClimateNormals.build(
            decade(high = { if (it.monthValue == 2 && it.dayOfMonth == 29) -20.0 else 10.0 }),
        )

        val march = normals.at(LocalDate.of(2026, 3, 1))!!.medianHigh!!
        assertEquals("1 March came out at $march", 10.0, march, 1e-9)

        // The leap day's own normal is 10 C as well, and that is not a bug: its
        // window is the same eleven dates, of which it is two samples in a
        // hundred and ten. A date does not get to be its own normal - the window
        // is the whole reason a single freak year cannot become the climate.
        assertEquals(10.0, normals.at(LocalDate.of(2024, 2, 29))!!.medianHigh!!, 1e-9)
    }

    @Test
    fun `nothing in gives an empty climatology rather than a crash`() {
        assertTrue(ClimateNormals.build(emptyList()).isEmpty)
    }
}
