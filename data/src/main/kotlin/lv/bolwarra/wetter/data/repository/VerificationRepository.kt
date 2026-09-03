package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import lv.bolwarra.wetter.data.db.ForecastRecordDao
import lv.bolwarra.wetter.data.db.ForecastRecordEntity
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.observation.LocalEstimate
import lv.bolwarra.wetter.domain.observation.ObservationSource
import lv.bolwarra.wetter.domain.observation.WeatherObservation
import lv.bolwarra.wetter.domain.verification.BiasCorrection
import lv.bolwarra.wetter.domain.verification.ForecastRecord
import lv.bolwarra.wetter.domain.verification.LearnedBias
import lv.bolwarra.wetter.domain.verification.VerifiedVariable

/**
 * The loop that lets the app find out whether it was right.
 *
 * Three things happen here, at different times and deliberately not together.
 * A forecast is written down when it is made. Later, once the hours it described
 * have passed, they are compared against what the aerodromes actually reported.
 * Later still, the accumulated errors are read back as a correction.
 *
 * Nothing about this is fast or urgent, and none of it belongs on the path that
 * draws the screen - the recording is cheap, the verification is a network call
 * for weather that has already happened, and the correction is worth applying
 * only after weeks of records exist.
 *
 * ### Why it records the blend rather than each model
 *
 * The screen shows one forecast, which is what somebody acted on, so that is
 * what is scored. Verifying each of the seven models separately would be the
 * more interesting experiment and a much larger store, and would answer a
 * question nothing currently asks. The ensemble's own disagreement is already
 * available for free and does most of that work.
 */
class VerificationRepository internal constructor(
    private val dao: ForecastRecordDao,
    private val observations: ObservationSource,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Write down what was just forecast, so it can be marked later.
     *
     * Only the near term is kept. A three-day-old prediction for next Tuesday is
     * a legitimate thing to score, but the store would grow by every hour of
     * every horizon on every refresh, and the near term is where an error
     * actually costs somebody something.
     */
    suspend fun record(forecast: WeatherForecast) {
        val now = Instant.now(clock)
        val until = now.plus(RECORD_HORIZON)
        val key = cacheKeyOf(forecast.location)

        val rows = forecast.hourly
            .filter { it.timestamp.isAfter(now) && !it.timestamp.isAfter(until) }
            .flatMap { hour ->
                buildList {
                    hour.temperature?.let {
                        add(
                            row(
                                key,
                                forecast,
                                hour.timestamp,
                                now,
                                VerifiedVariable.TEMPERATURE,
                                it,
                            ),
                        )
                    }
                    hour.precipitation?.let {
                        add(
                            row(
                                key,
                                forecast,
                                hour.timestamp,
                                now,
                                VerifiedVariable.PRECIPITATION,
                                it,
                            ),
                        )
                    }
                }
            }
        if (rows.isNotEmpty()) dao.write(rows)
    }

    /**
     * Check every outstanding prediction whose hour has passed.
     *
     * @return how many were settled. Zero is the normal answer when there is
     *   nothing new to check, or when no station near enough had anything to say.
     */
    suspend fun verify(location: WeatherLocation): Int {
        val now = Instant.now(clock)
        val earliest = now.minus(BiasCorrection.MAX_AGE)
        val pending = dao.awaitingVerification(now.epochSecond, earliest.epochSecond)
        if (pending.isEmpty()) return 0

        val history = observations.history(
            latitude = location.latitude,
            longitude = location.longitude,
            radiusKm = LocalEstimate.MAX_DISTANCE_KM,
            hours = HISTORY_HOURS,
        ).getOrElse { return 0 }
        if (history.isEmpty()) return 0

        val byHour = history.groupBy { it.at.truncatedTo(ChronoUnit.HOURS) }
        var settled = 0

        pending.forEach { record ->
            val hour = Instant.ofEpochSecond(record.validAtEpochSecond)
                .truncatedTo(ChronoUnit.HOURS)
            val reports = byHour[hour] ?: return@forEach
            val observed = observedValue(record.variable, reports, location, hour)
                ?: return@forEach
            dao.markVerified(record.id, observed)
            settled++
        }
        return settled
    }

    /**
     * What this location's forecasts get systematically wrong, once enough
     * records exist to tell. Null until they do, which is the normal state for
     * the first few weeks.
     */
    suspend fun learnedBias(
        location: WeatherLocation,
        variable: VerifiedVariable = VerifiedVariable.TEMPERATURE,
    ): LearnedBias? {
        val since = Instant.now(clock).minus(BiasCorrection.MAX_AGE)
        val records = dao.verifiedFor(cacheKeyOf(location), since.epochSecond)
            .map { it.toDomain() }
        return BiasCorrection.learn(records, variable)
    }

    /** How many predictions have been checked so far, across all locations. */
    suspend fun verifiedCount(): Int = dao.verifiedCount()

    /** Drop records past the window a correction is learned from. */
    suspend fun prune() {
        val cutoff = Instant.now(clock).minus(BiasCorrection.MAX_AGE)
        dao.deleteOlderThan(cutoff.epochSecond)
    }

    /**
     * What actually happened in an hour, as one number.
     *
     * Temperature comes from the weighted local estimate. Precipitation is the
     * share of nearby stations reporting something falling, turned into a rate
     * only far enough to be comparable against a forecast - the reports carry no
     * amount, so this is deliberately coarse and is used as a yes-or-no event
     * rather than as a measurement.
     */
    private fun observedValue(
        variable: String,
        reports: List<WeatherObservation>,
        location: WeatherLocation,
        hour: Instant,
    ): Double? {
        val estimate = LocalEstimate.at(
            latitude = location.latitude,
            longitude = location.longitude,
            elevationMetres = null,
            observations = reports,
            at = hour,
        ) ?: return null

        return when (variable) {
            VerifiedVariable.TEMPERATURE.name -> estimate.temperature
            VerifiedVariable.PRECIPITATION.name ->
                estimate.precipitatingShare?.let { share ->
                    if (share >= WET_SHARE) OBSERVED_WET_MM else 0.0
                }
            else -> null
        }
    }

    private fun row(
        key: String,
        forecast: WeatherForecast,
        validAt: Instant,
        issuedAt: Instant,
        variable: VerifiedVariable,
        predicted: Double,
    ) = ForecastRecordEntity(
        cacheKey = key,
        latitude = forecast.location.latitude,
        longitude = forecast.location.longitude,
        validAtEpochSecond = validAt.epochSecond,
        issuedAtEpochSecond = issuedAt.epochSecond,
        source = forecast.provider.id,
        variable = variable.name,
        predicted = predicted,
        observed = null,
    )

    private fun ForecastRecordEntity.toDomain() = ForecastRecord(
        id = id,
        latitude = latitude,
        longitude = longitude,
        validAt = Instant.ofEpochSecond(validAtEpochSecond),
        issuedAt = Instant.ofEpochSecond(issuedAtEpochSecond),
        source = source,
        variable = VerifiedVariable.valueOf(variable),
        predicted = predicted,
        observed = observed,
    )

    /**
     * Two nearby points share a record set.
     *
     * Matches how the forecast cache is keyed, so a bias learned for a place is
     * not split across a handful of near-identical coordinates and never reaches
     * the sample count it needs.
     */
    private fun cacheKeyOf(location: WeatherLocation): String {
        val latitude = String.format(java.util.Locale.ROOT, "%.2f", location.latitude)
        val longitude = String.format(java.util.Locale.ROOT, "%.2f", location.longitude)
        return "$latitude,$longitude"
    }

    private companion object {
        /** How far ahead predictions are written down. */
        val RECORD_HORIZON: Duration = Duration.ofHours(12)

        /** Enough to catch up after a couple of days without the app being opened. */
        const val HISTORY_HOURS = 48

        /** Above this share of nearby stations reporting rain, the hour was wet. */
        const val WET_SHARE = 0.5

        /**
         * The rate a confirmed wet hour is recorded as.
         *
         * Reports give no amount, so this is a token above the trace threshold
         * rather than a measurement - enough to make the record compare equal to
         * a forecast that said rain, which is the only question being asked of
         * it.
         */
        const val OBSERVED_WET_MM = 0.5
    }
}
