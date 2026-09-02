package lv.bolwarra.wetter.ui.format

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far off a day is, in the terms a sentence would use for it.
 *
 * The boundaries are the whole of it. "Rain starts at 23:00" is fine about
 * tonight and misleading about next Wednesday; "Wednesday" is fine about the day
 * after tomorrow and useless seven days out, when it is today's name again.
 */
class DayDistanceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Riga")

    /** A Monday, mid-morning. */
    private val now = LocalDate.of(2026, 9, 7).atTime(10, 0).atZone(zone).toInstant()

    private fun at(days: Long, hour: Int) =
        LocalDate.of(2026, 9, 7).plusDays(days).atTime(hour, 0).atZone(zone).toInstant()

    @Test
    fun `later the same day is today`() {
        assertEquals(DayDistance.TODAY, dayDistance(at(0, 23), now, zone))
    }

    @Test
    fun `earlier the same day is still today`() {
        // Rain already falling this morning is not "yesterday".
        assertEquals(DayDistance.TODAY, dayDistance(at(0, 2), now, zone))
    }

    @Test
    fun `one minute past midnight is tomorrow, not tonight`() {
        // The distinction is by calendar date, not by how many hours away it is:
        // 00:30 tomorrow is fourteen hours off but nobody calls it tonight.
        val justAfterMidnight = at(1, 0).plus(Duration.ofMinutes(30))
        assertEquals(DayDistance.TOMORROW, dayDistance(justAfterMidnight, now, zone))
    }

    @Test
    fun `the day after tomorrow gets a weekday name`() {
        assertEquals(DayDistance.THIS_WEEK, dayDistance(at(2, 9), now, zone))
    }

    @Test
    fun `six days out is still a weekday name`() {
        assertEquals(DayDistance.THIS_WEEK, dayDistance(at(6, 9), now, zone))
    }

    @Test
    fun `seven days out needs a date, because the weekday is today's again`() {
        // Monday to Monday. "Monday" would be a riddle rather than an answer.
        assertEquals(DayDistance.LATER, dayDistance(at(7, 9), now, zone))
        assertEquals(DayDistance.LATER, dayDistance(at(10, 9), now, zone))
    }

    @Test
    fun `the boundary follows the location, not UTC`() {
        // 23:30 local in Riga is 20:30 UTC the same day, but 00:30 local is
        // 21:30 UTC the day before. Judged in UTC, one of these lands on the
        // wrong date and the sentence names the wrong day.
        assertEquals(
            DayDistance.TODAY,
            dayDistance(at(0, 23).plus(Duration.ofMinutes(30)), now, zone),
        )
        assertEquals(
            DayDistance.TOMORROW,
            dayDistance(at(1, 0).plus(Duration.ofMinutes(30)), now, zone),
        )
    }
}
