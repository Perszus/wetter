package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

    /** An in-memory stand-in for the table the projection is kept in. */
    private class FakeSeriesDao : lv.bolwarra.wetter.data.db.RadarSeriesDao {
        var row: lv.bolwarra.wetter.data.db.RadarSeriesEntity? = null
        override suspend fun read(cacheKey: String) = row?.takeIf { it.cacheKey == cacheKey }
        override suspend fun write(series: lv.bolwarra.wetter.data.db.RadarSeriesEntity) {
            row = series
        }
        override suspend fun deleteOlderThan(cutoffEpochSecond: Long) {
            if ((row?.sweepAtEpochSecond ?: Long.MAX_VALUE) < cutoffEpochSecond) row = null
        }
    }

    private fun repository(radar: FakeRadar, clock: TestClock, dao: FakeSeriesDao? = null) =
        NowcastRepository(
            source = radar,
            ensembles = NoEnsemble,
            clock = clock,
            seriesStore = dao?.let {
                RadarSeriesStore(it, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            },
        )

    private fun forecastAt(location: WeatherLocation, at: Instant) =
        lv.bolwarra.wetter.data.repository.testForecast(location, at)

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

    @Test
    fun `a projection is kept, so the next launch starts from something`() = runBlocking {
        val sweep = Instant.parse("2026-09-04T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val dao = FakeSeriesDao()
        val radar = FakeRadar(latest = sweep)

        // One run fetches and keeps what it found.
        repository(radar, clock, dao).timeline(forecastAt(riga, clock.now), clock.now)
        assertEquals(1, radar.tileCalls)
        assertNotNull("nothing was kept for the next launch", dao.row)

        // A brand new repository - a fresh process - must not have to fetch to
        // put radar on screen.
        val coldRadar = FakeRadar(latest = sweep)
        val cold = repository(coldRadar, TestClock(clock.now), dao)
        val timeline = cold.timeline(forecastAt(riga, clock.now), clock.now)

        assertEquals("a cold start went to the network before drawing", 0, coldRadar.tileCalls)
        assertEquals(0, coldRadar.indexCalls)
        assertTrue(
            "nothing radar-backed reached the timeline",
            timeline.any {
                it.radarShare > 0.0
            },
        )
    }

    @Test
    fun `a kept projection is used only for the part still ahead`() = runBlocking {
        val sweep = Instant.parse("2026-09-04T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val dao = FakeSeriesDao()
        repository(FakeRadar(latest = sweep), clock, dao).timeline(
            forecastAt(riga, clock.now),
            clock.now,
        )

        // Ninety minutes on, the early half of that projection describes weather
        // that has already happened and must not be drawn as forecast.
        val later = TestClock(sweep.plus(Duration.ofMinutes(90)))
        val coldRadar = FakeRadar(latest = sweep)
        val timeline = repository(coldRadar, later, dao)
            .timeline(forecastAt(riga, later.now), later.now)

        assertEquals(0, coldRadar.tileCalls)
        assertTrue(timeline.all { !it.at.isBefore(later.now) })
        // The tail of it is still ahead, so radar still contributes something.
        assertTrue(timeline.any { it.radarShare > 0.0 })
    }

    @Test
    fun `a projection with nothing left ahead is not used at all`() = runBlocking {
        val sweep = Instant.parse("2026-09-04T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofSeconds(30)))
        val dao = FakeSeriesDao()
        repository(FakeRadar(latest = sweep), clock, dao).timeline(
            forecastAt(riga, clock.now),
            clock.now,
        )

        // Four hours on, every sample is in the past. Rather than draw stale
        // weather the repository goes and asks.
        val muchLater = TestClock(sweep.plus(Duration.ofHours(4)))
        val coldRadar = FakeRadar(latest = sweep.plus(Duration.ofHours(4)))
        repository(coldRadar, muchLater, dao).timeline(
            forecastAt(riga, muchLater.now),
            muchLater.now,
        )

        assertTrue("should have fetched rather than drawn the past", coldRadar.tileCalls > 0)
    }

    @Test
    fun `the near steps are fine enough to describe this minute`() {
        // A sweep lands about every ten minutes, so by the time somebody looks
        // the observation can be nine minutes old. On ten-minute steps the
        // closest value to the present could be five minutes away from it,
        // which is a long time in a shower.
        val leads = NowcastRepository.LEADS.map { it.toMinutes() }

        val worstGap = leads.zipWithNext { a, b -> if (a < 30) b - a else 0L }.max()
        assertTrue("near steps are $worstGap minutes apart", worstGap <= 5L)

        // Any instant in the first half hour is within half a step of a value.
        (0..30).forEach { minute ->
            val nearest = leads.minOf { kotlin.math.abs(it - minute) }
            assertTrue("no value within 2.5 min of minute $minute", nearest <= 3L)
        }
    }

    @Test
    fun `the series still reaches two hours`() {
        assertEquals(120L, NowcastRepository.LEADS.max().toMinutes())
        // And is strictly increasing, or the projection would double back.
        val leads = NowcastRepository.LEADS.map { it.toMinutes() }
        assertEquals(leads.sorted().distinct(), leads)
    }

    @Test
    fun `a kept projection answers at once and a fresh one is fetched behind it`() = runBlocking {
        // The point of the background worker: opening must not wait on the
        // network. But answering from disk and stopping there meant the screen
        // showed whatever the last run left behind and never improved while
        // anybody watched - on a layer whose whole argument is that it
        // re-observes every ten minutes.
        val sweep = Instant.parse("2026-09-04T12:00:00Z")
        val clock = TestClock(sweep.plus(Duration.ofMinutes(20)))
        val radar = FakeRadar(latest = clock.now)
        val dao = FakeSeriesDao()

        val ahead = sweep.plus(Duration.ofMinutes(40))
        dao.row = lv.bolwarra.wetter.data.db.RadarSeriesEntity(
            cacheKey = "56.95,24.11",
            sweepAtEpochSecond = sweep.epochSecond,
            payload = "[{\"atEpochSecond\":${ahead.epochSecond},\"leadMinutes\":40," +
                "\"millimetresPerHour\":2.0,\"confidence\":0.8}]",
        )

        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val repository = NowcastRepository(
            source = radar,
            ensembles = NoEnsemble,
            clock = clock,
            seriesStore = RadarSeriesStore(
                dao,
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            ),
            scope = scope,
        )

        repository.timeline(
            forecast = forecastAt(riga, clock.now),
            from = clock.now,
            step = Duration.ofMinutes(10),
            steps = 1,
        )

        // A refresh was sent behind the answer rather than waited on.
        assertTrue("no refresh was requested", radar.tileCalls > 0)
        scope.cancel()
    }
}
