package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import lv.bolwarra.wetter.domain.forecast.EnsembleSource
import lv.bolwarra.wetter.domain.forecast.ModelEnsemble
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.radar.RadarField
import lv.bolwarra.wetter.domain.radar.RadarGeometry
import lv.bolwarra.wetter.domain.radar.RadarSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the cache costs in requests.
 *
 * These are counting tests rather than value tests: the thing being protected is
 * that a phone does not re-download tens of kilobytes of imagery it already has,
 * nor poll an index so often that the polling costs more than the images. Both
 * are invisible in any assertion about the forecast itself.
 */
class NowcastCacheTest {

    private val zone = ZoneOffset.UTC
    private val riga = WeatherLocation("Riga", 56.95, 24.11, zone)

    /** A clock the test moves by hand. */
    private class TestClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    /** Counts what was asked of it, and lets the test decide what exists. */
    private class FakeRadar(var latest: Instant?) : RadarSource {
        override val id = "fake"
        override val attribution = "Fake"
        override val sweepInterval: Duration = Duration.ofMinutes(10)

        var indexCalls = 0
        var tileCalls = 0
        var failIndex = false
        var failTiles = false

        override suspend fun latestSweep(): Result<Instant?> {
            indexCalls++
            return if (failIndex) {
                Result.failure(
                    IllegalStateException("down"),
                )
            } else {
                Result.success(latest)
            }
        }

        override suspend fun recentFrames(
            latitude: Double,
            longitude: Double,
            frames: Int,
        ): Result<List<RadarField>> {
            tileCalls++
            if (failTiles) return Result.failure(IllegalStateException("down"))
            val sweep = latest ?: return Result.success(emptyList())
            val geometry = RadarGeometry.ofTileBlock(7, 72, 39, 1, 1)
            // Two sweeps ten minutes apart with a little structure, so a motion
            // estimate is actually possible.
            return Result.success(
                listOf(
                    field(sweep.minus(Duration.ofMinutes(10)), geometry, 0),
                    field(sweep, geometry, 12),
                ),
            )
        }

        private fun field(at: Instant, geometry: RadarGeometry, shift: Int): RadarField {
            val values = FloatArray(geometry.width * geometry.height)
            for (y in 40 until 140) {
                for (x in 40 until 140) {
                    val gx = x + shift
                    if (gx in 0 until geometry.width) {
                        values[y * geometry.width + gx] = 4f + (x % 7) + (y % 5)
                    }
                }
            }
            return RadarField(at, geometry, values)
        }
    }

    private object NoEnsemble : EnsembleSource {
        override suspend fun ensemble(location: WeatherLocation) =
            Result.success(ModelEnsemble(emptyList()))
    }

    private fun repository(radar: FakeRadar, clock: TestClock) =
        NowcastRepository(radar, NoEnsemble, clock)

    @Test
    fun `nothing is asked while the sweep in hand is still the newest there is`() = runBlocking {
        val sweep = Instant.parse("2026-09-03T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val radar = FakeRadar(latest = sweep)
        val repository = repository(radar, clock)

        assertNotNull(repository.nowcast(riga))
        assertEquals(1, radar.tileCalls)

        // Nine minutes of the screen rebuilding its timeline every minute. The
        // next sweep is not due, so none of those ticks may cost a request -
        // not even the cheap one.
        val indexAfterFirst = radar.indexCalls
        repeat(9) {
            clock.now = clock.now.plus(Duration.ofMinutes(1))
            repository.nowcast(riga)
        }
        assertEquals(1, radar.tileCalls)
        assertEquals(indexAfterFirst, radar.indexCalls)
    }

    @Test
    fun `once a sweep is overdue the index is asked, but not the tiles`() = runBlocking {
        val sweep = Instant.parse("2026-09-03T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val radar = FakeRadar(latest = sweep)
        val repository = repository(radar, clock)
        repository.nowcast(riga)
        val tilesAfterFirst = radar.tileCalls
        val indexAfterFirst = radar.indexCalls

        // Publication is late. Three minutes of looking for it.
        repeat(3) {
            clock.now = clock.now.plus(Duration.ofMinutes(4))
            repository.nowcast(riga)
        }

        // It looked, and found nothing new, so it must not have paid for tiles.
        assertEquals(
            "asked for imagery it already had",
            tilesAfterFirst,
            radar.tileCalls,
        )
        assertTrue(radar.indexCalls > indexAfterFirst)
    }

    @Test
    fun `a newly published sweep is fetched`() = runBlocking {
        val sweep = Instant.parse("2026-09-03T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val radar = FakeRadar(latest = sweep)
        val repository = repository(radar, clock)
        repository.nowcast(riga)
        assertEquals(1, radar.tileCalls)

        clock.now = clock.now.plus(Duration.ofMinutes(11))
        radar.latest = sweep.plus(Duration.ofMinutes(10))
        assertNotNull(repository.nowcast(riga))

        assertEquals("a new sweep was published and not collected", 2, radar.tileCalls)
    }

    @Test
    fun `exactly one download per sweep over an hour`() = runBlocking {
        // The whole point. Six sweeps published, sixty timeline rebuilds.
        var sweep = Instant.parse("2026-09-03T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(10)))
        val radar = FakeRadar(latest = sweep)
        val repository = repository(radar, clock)

        repeat(60) {
            clock.now = clock.now.plus(Duration.ofMinutes(1))
            // A new sweep lands every ten minutes.
            if (Duration.between(sweep, clock.now) >= Duration.ofMinutes(10)) {
                sweep = sweep.plus(Duration.ofMinutes(10))
                radar.latest = sweep
            }
            repository.nowcast(riga)
        }

        // Six new sweeps in the hour, plus the one it started with.
        assertEquals("downloaded imagery more than once per sweep", 7, radar.tileCalls)
        // And the looking stayed cheap: only in the short window after each was
        // due, never through the nine minutes before.
        assertTrue(
            "polled the index ${radar.indexCalls} times in an hour",
            radar.indexCalls <= 20,
        )
    }

    @Test
    fun `a source that is down is left alone rather than retried every minute`() = runBlocking {
        val clock = TestClock(Instant.parse("2026-09-03T12:00:00Z"))
        val radar = FakeRadar(latest = null).apply {
            failIndex = true
            failTiles = true
        }
        val repository = repository(radar, clock)
        repository.nowcast(riga)
        val afterFirst = radar.tileCalls

        // Four minutes of ticks against a service that is not answering.
        repeat(4) {
            clock.now = clock.now.plus(Duration.ofMinutes(1))
            repository.nowcast(riga)
        }
        assertEquals("hammered a broken source", afterFirst, radar.tileCalls)

        // But it does try again eventually.
        clock.now = clock.now.plus(Duration.ofMinutes(6))
        repository.nowcast(riga)
        assertTrue(radar.tileCalls > afterFirst)
    }

    @Test
    fun `moving to another place fetches for it rather than reusing the last`() = runBlocking {
        val sweep = Instant.parse("2026-09-03T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val radar = FakeRadar(latest = sweep)
        val repository = repository(radar, clock)
        repository.nowcast(riga)
        assertEquals(1, radar.tileCalls)

        val berlin = WeatherLocation("Berlin", 52.52, 13.40, zone)
        repository.nowcast(berlin)
        assertEquals("served Berlin with Riga's radar", 2, radar.tileCalls)
    }

    @Test
    fun `a step across the street is the same radar answer`() = runBlocking {
        // A pixel is most of a kilometre here, so anything closer than that is
        // the same reading and must not cost another twenty tiles.
        val sweep = Instant.parse("2026-09-03T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val radar = FakeRadar(latest = sweep)
        val repository = repository(radar, clock)
        repository.nowcast(riga)

        repository.nowcast(riga.copy(latitude = riga.latitude + 0.001))
        assertEquals(1, radar.tileCalls)
    }
}
