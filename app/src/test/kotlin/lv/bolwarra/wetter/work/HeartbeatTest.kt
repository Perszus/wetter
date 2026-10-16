package lv.bolwarra.wetter.work

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatTest {

    private val sweep = Duration.ofMinutes(10)
    private val t0: Instant = Instant.parse("2026-10-15T12:00:00Z")

    private fun wake(nowMinutes: Long, lastSweepMinutes: Long?): Long {
        val now = t0.plus(Duration.ofMinutes(nowMinutes))
        val last = lastSweepMinutes?.let { t0.plus(Duration.ofMinutes(it)) }
        return Duration.between(t0, Heartbeat.nextWake(now, last, sweep)).toMinutes()
    }

    @Test
    fun wakesJustAfterTheNextSweepIsPublished() {
        // swept at 12:00, collected at 12:03: the next one lands at 12:10 and is
        // looked for two minutes later, not at 12:13 or 12:18
        assertEquals(12, wake(nowMinutes = 3, lastSweepMinutes = 0))
    }

    @Test
    fun neverWaitsLongerThanOneCycle() {
        // a sweep stamped in the future would otherwise put the wake far ahead
        assertEquals(12, wake(nowMinutes = 0, lastSweepMinutes = 30))
    }

    @Test
    fun aSweepCollectedTheMomentItAppearedTimesTheNextOneExactly() {
        // 12:20 sweep collected at 12:21:20: wake at 12:32, when 12:30's is out -
        // not a few seconds before it, which costs a second wake
        val now = t0.plus(Duration.ofMinutes(21)).plusSeconds(20)
        val at = Heartbeat.nextWake(now, t0.plus(Duration.ofMinutes(20)), sweep)
        assertEquals(t0.plus(Duration.ofMinutes(32)), at)
    }

    @Test
    fun looksAgainEveryMinuteWhileASweepIsLate() {
        assertEquals(13, wake(nowMinutes = 12, lastSweepMinutes = 0))
        assertEquals(18, wake(nowMinutes = 17, lastSweepMinutes = 0))
    }

    @Test
    fun goesBackToThePlainCadenceWhenTheRadarHasGoneQuiet() {
        // ten minutes due plus eight of chasing, then every ten
        assertEquals(28, wake(nowMinutes = 18, lastSweepMinutes = 0))
        assertEquals(130, wake(nowMinutes = 120, lastSweepMinutes = 0))
    }

    @Test
    fun withoutRadarItIsEveryTenMinutes() {
        assertEquals(10, wake(nowMinutes = 0, lastSweepMinutes = null))
    }

    @Test
    fun neverSetsAWakeInThePast() {
        // the next sweep's publication is seconds away: no zero-delay alarm loop
        val now = t0.plus(Duration.ofMinutes(12)).minusSeconds(10)
        val at = Heartbeat.nextWake(now, t0, sweep)
        assert(at.isAfter(now.plus(Heartbeat.MIN_GAP).minusMillis(1))) { "wake at $at, now $now" }
    }
}
