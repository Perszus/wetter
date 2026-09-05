package lv.bolwarra.wetter.data.repository

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lv.bolwarra.wetter.data.db.ForecastRecordDao
import lv.bolwarra.wetter.data.db.ForecastRecordEntity
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.observation.LocalEstimate
import lv.bolwarra.wetter.domain.observation.ObservationSource
import lv.bolwarra.wetter.domain.observation.WeatherObservation
import lv.bolwarra.wetter.domain.radar.RadarSample
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
     * Record what the radar projection claimed, so it can be held to it.
     *
     * The nowcast is the one source in this app that can be marked without any
     * outside help. Every ten minutes a real sweep arrives, and it is a
     * measurement of exactly the quantity the projection guessed at, over
     * exactly the same field. No station, no interpolation, no waiting an hour
     * for a METAR that may say nothing useful.
     *
     * That matters more than convenience. The hour the radar is trusted for is
     * currently a judgement - a reasoned one, but nobody has measured where the
     * projection actually stops beating the model. With both scored the
     * hand-over can sit where the two curves genuinely cross, per location,
     * rather than where anybody reasoned it should.
     *
     * The zero-lead sample is deliberately not recorded. It is the observation
     * itself, and scoring it would be marking the radar's homework against a
     * copy of the same homework.
     */
    suspend fun recordNowcast(
        location: WeatherLocation,
        issuedAt: Instant,
        samples: List<RadarSample>,
    ) {
        val key = cacheKeyOf(location)
        val rows = samples
            .filter { !it.lead.isZero && it.lead <= NOWCAST_HORIZON }
            .map { sample ->
                ForecastRecordEntity(
                    cacheKey = key,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    validAtEpochSecond = sample.at.epochSecond,
                    issuedAtEpochSecond = issuedAt.epochSecond,
                    source = NOWCAST_SOURCE,
                    variable = VerifiedVariable.PRECIPITATION.name,
                    predicted = sample.millimetresPerHour.toDouble(),
                    observed = null,
                )
            }
        if (rows.isNotEmpty()) dao.write(rows)
    }

    /**
     * Settle outstanding radar predictions against a sweep that has now landed.
     *
     * @param observedAt the moment the sweep describes.
     * @param observed what it measured at this place, mm/h.
     * @return how many claims were settled.
     */
    suspend fun settleFromRadar(
        location: WeatherLocation,
        observedAt: Instant,
        observed: Double,
    ): Int {
        val key = cacheKeyOf(location)
        val outstanding = dao.awaitingVerification(
            nowEpochSecond = observedAt.plus(SWEEP_TOLERANCE).epochSecond,
            earliestEpochSecond = observedAt.minus(SWEEP_TOLERANCE).epochSecond,
        ).filter { it.cacheKey == key && it.source == NOWCAST_SOURCE }

        outstanding.forEach { dao.markVerified(it.id, observed) }
        return outstanding.size
    }

    /**
     * Check every outstanding prediction whose hour has passed.
     *
     * @return how many were settled. Zero is the normal answer when there is
     *   nothing new to check, or when no station near enough had anything to say.
     */
    /**
     * Marks past predictions against what the aerodromes actually reported.
     *
     * ### The hour that could never match
     *
     * This used to bucket the reports by their own truncated hour and then ask
     * [LocalEstimate] for an estimate *at the start* of that hour. Every report
     * in a bucket is by construction at or after the hour it was bucketed into,
     * and LocalEstimate rejects observations from the future - a reading cannot
     * describe a moment before it was taken. So every candidate was filtered out
     * and the answer was always null.
     *
     * It settled nothing, ever, and did it silently: a fetch that works, a
     * grouping that looks reasonable, and a count of zero that reads like "no
     * observations yet". Measured on a phone after sixteen hours: 119 model
     * predictions due, 119 unsettled, while the radar half - which never went
     * through this path - had scored 186.
     *
     * The tell is in the data rather than the code. METAR is issued at twenty
     * and fifty minutes past; across forty consecutive reports around Riga, the
     * number landing exactly on the hour was zero. Nothing could have matched.
     *
     * So the estimate is now asked for at the moment actually being verified,
     * and LocalEstimate does what it was built to do: take the most recent
     * report at or before that moment, within the two hours it considers fresh.
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

        var settled = 0

        pending.forEach { record ->
            val validAt = Instant.ofEpochSecond(record.validAtEpochSecond)
            val observed = observedValue(record.variable, history, location, validAt)
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
        val rows = dao.verifiedFor(cacheKeyOf(location), since.epochSecond)
        // Up to thirty days of records mapped and regressed. Room runs the query
        // off the main thread but hands the rows back on the caller's, and the
        // caller is a view model - so the arithmetic landed on the frame clock.
        return withContext(Dispatchers.Default) {
            BiasCorrection.learn(rows.map { it.toDomain() }, variable)
        }
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

    companion object {
        /**
         * The name the radar projection is scored under.
         *
         * A source like any other, which is the point: it sits in the same
         * table as the providers and is compared on the same terms, so the
         * hand-over between them can be read off the numbers rather than
         * argued about.
         */
        const val NOWCAST_SOURCE = "radar-nowcast"

        /**
         * How close a projected step must sit to a sweep to be the same moment.
         *
         * Sweeps land about every ten minutes and the projection steps at the
         * same cadence, so half a step either way pairs each claim with the
         * observation that answers it and no other.
         */
        val SWEEP_TOLERANCE: Duration = Duration.ofMinutes(5)

        /**
         * How far ahead the projection is held to its word. Beyond this the
         * model already carries the answer, so scoring it would be scoring
         * something nobody is shown.
         */
        val NOWCAST_HORIZON: Duration = Duration.ofHours(2)

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
