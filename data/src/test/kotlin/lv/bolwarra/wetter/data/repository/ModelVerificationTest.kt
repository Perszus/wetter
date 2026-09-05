package lv.bolwarra.wetter.data.repository

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import lv.bolwarra.wetter.data.db.ForecastRecordDao
import lv.bolwarra.wetter.data.db.ForecastRecordEntity
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.observation.ObservationSource
import lv.bolwarra.wetter.domain.observation.ObservationStation
import lv.bolwarra.wetter.domain.observation.WeatherObservation
import lv.bolwarra.wetter.domain.verification.VerifiedVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The model's homework, marked against the aerodromes.
 *
 * This half of verification had never settled a single record. The reason was a
 * mismatch nothing would surface: records were matched to reports bucketed by
 * the hour, and the estimate was then asked for at the *start* of that hour -
 * which every report in the bucket is necessarily later than, and a reading
 * cannot describe a moment before it was taken.
 *
 * Aerodromes report at twenty and fifty minutes past. Across forty consecutive
 * reports around Riga, the number landing exactly on the hour was zero, so the
 * match could never be made and the count of zero read like "no observations
 * yet" rather than like a fault.
 */
class ModelVerificationTest {

    private val issued: Instant = Instant.parse("2026-09-05T08:00:00Z")

    /** The moment a forecast is being held to - a whole hour, as the rows are. */
    private val validAt: Instant = Instant.parse("2026-09-05T09:00:00Z")

    private val riga = WeatherLocation(
        name = "Riga",
        latitude = 56.9496,
        longitude = 24.1052,
        zone = ZoneId.of("Europe/Riga"),
    )

    private val airport = ObservationStation(
        id = "EVRA",
        name = "Riga",
        latitude = 56.924,
        longitude = 23.968,
        elevationMetres = 10.0,
    )

    /** One report, at the minutes past the hour aerodromes actually use. */
    private fun reportAt(minute: Long, temperature: Double) = WeatherObservation(
        station = airport,
        at = validAt.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
            .plus(Duration.ofMinutes(minute)),
        temperature = temperature,
        dewPoint = 9.0,
        windSpeed = 3.0,
        windDirection = 240,
        pressure = 1012.0,
        visibilityMetres = 10_000.0,
        precipitating = false,
        intensity = null,
    )

    private class FakeRecordDao(rows: List<ForecastRecordEntity>) : ForecastRecordDao {
        val rows = rows.toMutableList()
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

    private class Reports(private val reports: List<WeatherObservation>) : ObservationSource {
        override val id = "test"
        override val attribution = "test"

        override suspend fun near(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
        ): Result<List<WeatherObservation>> = Result.success(reports)

        override suspend fun history(
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
            hours: Int,
        ): Result<List<WeatherObservation>> = Result.success(reports)
    }

    private fun claim() = ForecastRecordEntity(
        id = 0,
        cacheKey = "56.9496,24.1052",
        latitude = riga.latitude,
        longitude = riga.longitude,
        validAtEpochSecond = validAt.epochSecond,
        issuedAtEpochSecond = issued.epochSecond,
        source = "met-norway",
        variable = VerifiedVariable.TEMPERATURE.name,
        predicted = 14.0,
        observed = null,
    )

    @Test
    fun `a report at ten to the hour settles the hour it precedes`() = runBlocking {
        // The exact shape that never matched: nothing on the hour itself, a
        // reading fifty minutes into the hour before it.
        val dao = FakeRecordDao(listOf(claim().copy(id = 1)))
        val reports = Reports(listOf(reportAt(minute = -10, temperature = 15.0)))

        val settled = VerificationRepository(dao, reports).verify(riga)

        assertEquals(1, settled)
        assertEquals(15.0, dao.rows.single().observed!!, 0.5)
    }

    @Test
    fun `the usual twenty and fifty past are both usable`() = runBlocking {
        val dao = FakeRecordDao(listOf(claim().copy(id = 1)))
        val reports = Reports(
            listOf(
                reportAt(minute = -40, temperature = 13.0),
                reportAt(minute = -10, temperature = 15.0),
            ),
        )

        assertEquals(1, VerificationRepository(dao, reports).verify(riga))
        assertNotNull(dao.rows.single().observed)
    }

    @Test
    fun `a reading taken after the moment cannot describe it`() = runBlocking {
        // Not a technicality. Settling 09:00 from a 09:20 report would be
        // marking a forecast against weather that had not happened when the
        // forecast was for, and the whole point of the store is that it cannot.
        val dao = FakeRecordDao(listOf(claim().copy(id = 1)))
        val reports = Reports(listOf(reportAt(minute = 20, temperature = 15.0)))

        assertEquals(0, VerificationRepository(dao, reports).verify(riga))
        assertEquals(null, dao.rows.single().observed)
    }
}
