// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * Finds out whether the app was right.
 *
 * A forecast is written down when it is made; once its hours have passed they are
 * compared against what the aerodromes reported; the accumulated errors are read
 * back as a correction. None of it is on the path that draws the screen.
 *
 * The blend is scored rather than each model, because the blend is what the
 * reader saw and acted on.
 */
class VerificationRepository internal constructor(
    private val dao: ForecastRecordDao,
    private val observations: ObservationSource,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Write down what was just forecast, so it can be marked later. Only the near
     * term ([RECORD_HORIZON]) is kept: that is where an error costs somebody something,
     * and every horizon on every refresh would grow the store without limit.
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
     * Every sweep is a measurement of exactly the quantity the projection guessed at,
     * over the same field, so the nowcast can be scored without any station. With
     * radar and models both scored, the hand-over can sit where their errors actually
     * cross. The zero-lead sample is the observation itself and is not recorded.
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
                    // Kept with the record: afterwards there is no way to recover how much this
                    // projection deserved belief.
                    confidence = sample.confidence.toDouble(),
                    motionQuality = sample.motionQuality.toDouble(),
                )
            }
        if (rows.isNotEmpty()) dao.write(rows)
    }

    /**
     * How far back unsettled radar claims reach, or null if there are none. A claim
     * can only be settled while a sweep near its moment is in hand, so a run after the
     * device slept must reach back far enough to clear what came due in the gap.
     */
    suspend fun oldestUnsettledNowcast(now: Instant): Instant? = dao.oldestAwaiting(
        source = NOWCAST_SOURCE,
        nowEpochSecond = now.epochSecond,
        earliestEpochSecond = now.minus(NOWCAST_HORIZON).epochSecond,
    )?.let(Instant::ofEpochSecond)

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
     * Marks past predictions against what the aerodromes reported. The estimate is
     * asked for at the verified moment itself, since METAR is issued at twenty and
     * fifty past and [LocalEstimate] takes the latest report at or before a moment.
     *
     * @return how many were settled. Zero is normal when there is nothing new or no
     * station near enough had anything to say.
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
     * What this location's forecasts get systematically wrong, once enough records
     * exist to tell; null until then.
     */
    suspend fun learnedBias(
        location: WeatherLocation,
        variable: VerifiedVariable = VerifiedVariable.TEMPERATURE,
    ): LearnedBias? {
        val since = Instant.now(clock).minus(BiasCorrection.MAX_AGE)
        val rows = dao.verifiedFor(cacheKeyOf(location), since.epochSecond)
        // The regression runs off the main thread; Room hands rows back on the caller's.
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
     * What actually happened in an hour, as one number. Temperature comes from the
     * weighted local estimate. Precipitation is the share of nearby stations reporting
     * something falling: the reports carry no amount, so it is a yes-or-no event.
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
            // The place's height lets the estimate bring each station to it at the lapse
            // rate and prefer stations at a similar height. Without it a hill town would be
            // scored against the aerodrome below, and the gap learned as model bias.
            elevationMetres = location.elevationMetres,
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
     * Rounded like the forecast cache, so a bias learned for a place is not split
     * across near-identical coordinates.
     */
    private fun cacheKeyOf(location: WeatherLocation): String {
        val latitude = String.format(java.util.Locale.ROOT, "%.2f", location.latitude)
        val longitude = String.format(java.util.Locale.ROOT, "%.2f", location.longitude)
        return "$latitude,$longitude"
    }

    companion object {
        /**
         * The name the radar projection is scored under, in the same table as the
         * providers and on the same terms.
         */
        const val NOWCAST_SOURCE = "radar-nowcast"

        /**
         * How close a projected step must sit to a sweep to be the same moment: half the
         * ten-minute cadence either way.
         */
        val SWEEP_TOLERANCE: Duration = Duration.ofMinutes(5)

        /**
         * How far ahead the projection is held to its word. Beyond this the models carry
         * the answer.
         */
        val NOWCAST_HORIZON: Duration = Duration.ofHours(2)

        /** How far ahead predictions are written down. */
        val RECORD_HORIZON: Duration = Duration.ofHours(12)

        /** Enough to catch up after a couple of days without the app being opened. */
        const val HISTORY_HOURS = 48

        /** Above this share of nearby stations reporting rain, the hour was wet. */
        const val WET_SHARE = 0.5

        /**
         * The rate a confirmed wet hour is recorded as: a token above the trace
         * threshold, since reports give no amount.
         */
        const val OBSERVED_WET_MM = 0.5
    }
}
