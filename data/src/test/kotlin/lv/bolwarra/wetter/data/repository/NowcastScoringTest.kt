package lv.bolwarra.wetter.data.repository

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import lv.bolwarra.wetter.data.db.ForecastRecordDao
import lv.bolwarra.wetter.data.db.ForecastRecordEntity
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.observation.ObservationSource
import lv.bolwarra.wetter.domain.observation.WeatherObservation
import lv.bolwarra.wetter.domain.radar.RadarSample
import lv.bolwarra.wetter.domain.verification.VerifiedVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection being held to its word.
 *
 * The nowcast is the one source here that can be marked without outside help: a
 * real sweep lands every ten minutes and measures exactly the quantity the
 * projection guessed at, over exactly the same field.
 */
class NowcastScoringTest {

    private val sweep: Instant = Instant.parse("2026-09-04T12:00:00Z")

    private val riga = WeatherLocation(
        name = "Riga",
        latitude = 56.9496,
        longitude = 24.1052,
        zone = ZoneId.of("Europe/Riga"),
    )

    private class FakeRecordDao : ForecastRecordDao {
        val rows = mutableListOf<ForecastRecordEntity>()
        private var nextId = 1L

        override suspend fun write(records: List<ForecastRecordEntity>) {
            records.forEach { rows += it.copy(id = nextId++) }
        }

        override suspend fun awaitingVerification(
            nowEpochSecond: Long,
            earliestEpochSecond: Long,
        ): List<ForecastRecordEntity> = rows.filter {
            it.observed == null &&
                it.validAtEpochSecond <= nowEpochSecond &&
                it.validAtEpochSecond >= earliestEpochSecond
        }

        override suspend fun oldestAwaiting(
            source: String,
            nowEpochSecond: Long,
            earliestEpochSecond: Long,
        ): Long? = rows
            .filter {
                it.observed == null &&
                    it.source == source &&
                    it.validAtEpochSecond in earliestEpochSecond..nowEpochSecond
            }
            .minOfOrNull { it.validAtEpochSecond }

        override suspend fun markVerified(id: Long, observed: Double) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(observed = observed)
        }

        override suspend fun verifiedFor(
            cacheKey: String,
            sinceEpochSecond: Long,
        ): List<ForecastRecordEntity> =
            rows.filter { it.cacheKey == cacheKey && it.observed != null }

        override suspend fun verifiedCount(): Int = rows.count { it.observed != null }

        override suspend fun deleteOlderThan(cutoffEpochSecond: Long) {
            rows.removeAll { it.validAtEpochSecond < cutoffEpochSecond }
        }
    }

    /** The radar scores itself, so nothing here needs a station. */
    private object NoObservations : ObservationSource {
        override val id = "none"
        override val attribution = "none"

        override suspend fun near(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): Result<List<WeatherObservation>> = Result.success(emptyList())

        override suspend fun history(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
            hours: Int,
        ): Result<List<WeatherObservation>> = Result.success(emptyList())
    }

    private fun samples(vararg leadToRate: Pair<Long, Float>) = leadToRate.map { (lead, rate) ->
        RadarSample(
            at = sweep.plus(Duration.ofMinutes(lead)),
            lead = Duration.ofMinutes(lead),
            millimetresPerHour = rate,
            confidence = 0.9f,
            motionQuality = 1f,
        )
    }

    private fun repository(dao: FakeRecordDao) = VerificationRepository(dao, NoObservations)

    @Test
    fun `the observation is not scored against itself`() = runBlocking {
        // Lead zero is the sweep, not a claim about it. Recording it would be
        // marking the radar's homework against a copy of the same homework.
        val dao = FakeRecordDao()
        repository(dao).recordNowcast(riga, sweep, samples(0L to 3f, 10L to 4f, 20L to 5f))

        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.none { it.validAtEpochSecond == sweep.epochSecond })
    }

    @Test
    fun `claims are written as precipitation, under the radar's own name`() = runBlocking {
        val dao = FakeRecordDao()
        repository(dao).recordNowcast(riga, sweep, samples(10L to 4f))

        val row = dao.rows.single()
        assertEquals(VerificationRepository.NOWCAST_SOURCE, row.source)
        assertEquals(VerifiedVariable.PRECIPITATION.name, row.variable)
        assertEquals(4.0, row.predicted, 1e-6)
        assertEquals(sweep.epochSecond, row.issuedAtEpochSecond)
        assertNull(row.observed)
    }

    @Test
    fun `nothing is claimed beyond the horizon it is shown for`() = runBlocking {
        val dao = FakeRecordDao()
        repository(dao).recordNowcast(riga, sweep, samples(60L to 1f, 200L to 9f))

        assertEquals(1, dao.rows.size)
        assertEquals(1.0, dao.rows.single().predicted, 1e-6)
    }

    @Test
    fun `the next sweep settles what the last one claimed`() = runBlocking {
        val dao = FakeRecordDao()
        val repository = repository(dao)

        // A projection made at noon, claiming 4 mm/h ten minutes out.
        repository.recordNowcast(riga, sweep, samples(10L to 4f))

        // Ten minutes later the real sweep says it was 1.5.
        val settled = repository.settleFromRadar(
            location = riga,
            observedAt = sweep.plus(Duration.ofMinutes(10)),
            observed = 1.5,
        )

        assertEquals(1, settled)
        assertEquals(1.5, dao.rows.single().observed!!, 1e-6)
    }

    @Test
    fun `a sweep settles only the moment it describes`() = runBlocking {
        val dao = FakeRecordDao()
        val repository = repository(dao)
        repository.recordNowcast(riga, sweep, samples(10L to 4f, 40L to 8f))

        repository.settleFromRadar(riga, sweep.plus(Duration.ofMinutes(10)), observed = 1.5)

        val byLead = dao.rows.associateBy { it.validAtEpochSecond }
        assertEquals(
            1.5,
            byLead.getValue(sweep.plus(Duration.ofMinutes(10)).epochSecond).observed!!,
            1e-6,
        )
        // The forty-minute claim has not happened yet and must still be open.
        assertNull(byLead.getValue(sweep.plus(Duration.ofMinutes(40)).epochSecond).observed)
    }

    @Test
    fun `a sweep does not settle another place's claims`() = runBlocking {
        val dao = FakeRecordDao()
        val repository = repository(dao)
        val elsewhere = riga.copy(name = "Oslo", latitude = 59.91, longitude = 10.75)

        repository.recordNowcast(elsewhere, sweep, samples(10L to 4f))
        val settled = repository.settleFromRadar(
            location = riga,
            observedAt = sweep.plus(Duration.ofMinutes(10)),
            observed = 1.5,
        )

        assertEquals(0, settled)
        assertNull(dao.rows.single().observed)
    }

    @Test
    fun `every lead gets settled, not just the ones a wake-up lands on`() = runBlocking {
        // The background worker wakes every thirty minutes. Settling only
        // against the newest sweep would score leads 30, 60, 90 and 120 and
        // nothing else - never the near leads, which is where the app puts its
        // weight. Each fetch carries two hours of frames, so one run can settle
        // them all.
        val dao = FakeRecordDao()
        val repository = repository(dao)
        repository.recordNowcast(riga, sweep, samples(5L to 1f, 10L to 2f, 15L to 3f, 30L to 4f))

        // Half an hour later, the frames covering that stretch arrive together.
        listOf(5L, 10L, 15L, 30L).forEach { minute ->
            repository.settleFromRadar(
                location = riga,
                observedAt = sweep.plus(Duration.ofMinutes(minute)),
                observed = 0.5,
            )
        }

        assertEquals(4, dao.rows.size)
        assertTrue(
            "every claim should be settled: " + dao.rows.map { it.observed },
            dao.rows.all { it.observed != null },
        )
    }
}
