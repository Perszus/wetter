package lv.bolwarra.wetter.domain.observation

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.pow

/** What the nearby stations, taken together, say the weather is here. */
data class LocalObservation(
    val at: Instant,
    /** Celsius, corrected for the height difference to the stations used. */
    val temperature: Double?,
    val dewPoint: Double?,
    /** Metres per second. */
    val windSpeed: Double?,
    /** Hectopascals. */
    val pressure: Double?,
    /**
     * The share of usable stations reporting precipitation, 0..1.
     *
     * Not a forecast probability. It is the proportion of nearby places where
     * something is falling right now, which is a different and more literal
     * thing - and over a scattered-showers afternoon the two are nothing alike.
     */
    val precipitatingShare: Double?,
    /** 0..1, how much this estimate deserves to be believed. */
    val confidence: Double,
    /** How many stations survived quality control and contributed. */
    val stations: Int,
)

/**
 * Turns a scatter of station reports into an estimate for one place.
 *
 * ### Why not simply the nearest station
 *
 * docs/providers.md is explicit that the nearest station is the wrong answer,
 * and the reason is visible in the data: the stations around Riga sit between 1
 * and 180 metres above sea level, and height is worth more than distance for
 * temperature. A station 15 km away and 130 m higher is a worse guide to the
 * city than one 80 km away at the same height. Nor does the nearest station have
 * any way to be wrong - if its sensor sticks, so does the app.
 *
 * So every usable station contributes, weighted by how much it deserves to:
 * closeness, similar height, freshness, and having passed quality control.
 *
 * ### The lapse rate, and where it stops being true
 *
 * Temperature is corrected to the target's height at [LAPSE_RATE_C_PER_KM],
 * roughly the free-atmosphere rate. It is a real improvement across a few
 * hundred metres and it is *wrong on clear calm nights*, when cold air pools in
 * valleys and the profile inverts - the correction then has the sign backwards.
 * It is applied anyway because it is right far more often than not, but that is
 * a known limit rather than a solved problem, and it is one of the things a
 * verification history could eventually learn per location.
 */
object LocalEstimate {

    /** Roughly the free-atmosphere lapse rate: cooler with height. */
    const val LAPSE_RATE_C_PER_KM = 6.5

    /** Past this, a station is describing different weather. */
    const val MAX_DISTANCE_KM = 200.0

    /** Past this, a routine report is too old to describe now. */
    val MAX_AGE: Duration = Duration.ofHours(2)

    /** How sharply nearness is preferred. Two is the usual inverse-square. */
    private const val DISTANCE_POWER = 2.0

    /** Distances below this are treated alike, so one station cannot dominate. */
    private const val DISTANCE_FLOOR_KM = 5.0

    /** A height difference this large halves a station's weight. */
    private const val ELEVATION_HALF_WEIGHT_M = 150.0

    /**
     * Estimate conditions at a point.
     *
     * @param observations the latest report from each nearby station.
     * @param at the moment being asked about, for judging staleness.
     * @param elevationMetres the target's height, when known. Without it no
     *   lapse correction is applied, which is better than applying one to a
     *   height that was guessed.
     */
    fun at(
        latitude: Double,
        longitude: Double,
        elevationMetres: Double?,
        observations: List<WeatherObservation>,
        at: Instant,
    ): LocalObservation? {
        val usable = observations.filter { observation ->
            observation.isPlausible() &&
                observation.ageAt(at) <= MAX_AGE &&
                !observation.ageAt(at).isNegative &&
                observation.station.distanceKmTo(latitude, longitude) <= MAX_DISTANCE_KM
        }
        if (usable.isEmpty()) return null

        val weighted = usable.map { it to weightOf(it, latitude, longitude, elevationMetres, at) }
            .filter { it.second > 0.0 }
        if (weighted.isEmpty()) return null

        val temperature = weighted.weightedMean { observation ->
            observation.temperature?.let { reading ->
                elevationMetres?.let {
                    reading + lapseCorrection(observation.station.elevationMetres, it)
                } ?: reading
            }
        }
        val dewPoint = weighted.weightedMean { observation ->
            observation.dewPoint?.let { reading ->
                elevationMetres?.let {
                    reading + lapseCorrection(observation.station.elevationMetres, it)
                } ?: reading
            }
        }

        val wet = weighted.filter { it.first.precipitating != null }
        val precipitatingShare = if (wet.isEmpty()) {
            null
        } else {
            val total = wet.sumOf { it.second }
            wet.filter { it.first.precipitating == true }.sumOf { it.second } / total
        }

        return LocalObservation(
            at = at,
            temperature = temperature,
            dewPoint = dewPoint,
            windSpeed = weighted.weightedMean { it.windSpeed },
            // Pressure is already reduced to sea level in the report, so it
            // needs no height correction - correcting it would apply the
            // reduction twice.
            pressure = weighted.weightedMean { it.pressure },
            precipitatingShare = precipitatingShare,
            confidence = confidenceOf(weighted, latitude, longitude, at),
            stations = weighted.size,
        )
    }

    /** Degrees to add to a station's reading to bring it to another height. */
    fun lapseCorrection(fromMetres: Double, toMetres: Double): Double =
        -(toMetres - fromMetres) / METRES_PER_KM * LAPSE_RATE_C_PER_KM

    /**
     * How much a station's word is worth here.
     *
     * Distance, height and age each cut it independently, because a station can
     * fail any one of them while looking fine on the other two.
     */
    private fun weightOf(
        observation: WeatherObservation,
        latitude: Double,
        longitude: Double,
        elevationMetres: Double?,
        at: Instant,
    ): Double {
        val distance = observation.station.distanceKmTo(latitude, longitude)
            .coerceAtLeast(DISTANCE_FLOOR_KM)
        val byDistance = 1.0 / distance.pow(DISTANCE_POWER)

        val byElevation = elevationMetres?.let {
            val difference = abs(observation.station.elevationMetres - it)
            1.0 / (1.0 + difference / ELEVATION_HALF_WEIGHT_M)
        } ?: 1.0

        val ageMinutes = observation.ageAt(at).toMinutes().toDouble()
        val byAge = (1.0 - ageMinutes / MAX_AGE.toMinutes().toDouble()).coerceIn(0.0, 1.0)

        return byDistance * byElevation * byAge
    }

    /**
     * How much the whole estimate deserves to be believed.
     *
     * A single station 150 km away reporting an hour ago is not the same
     * evidence as five within 30 km reporting ten minutes ago, and an estimate
     * that scored them alike would be the more dangerous of the two.
     */
    private fun confidenceOf(
        weighted: List<Pair<WeatherObservation, Double>>,
        latitude: Double,
        longitude: Double,
        at: Instant,
    ): Double {
        val nearest = weighted.minOf { it.first.station.distanceKmTo(latitude, longitude) }
        val byNearest = (1.0 - nearest / MAX_DISTANCE_KM).coerceIn(0.0, 1.0)
        val freshest = weighted.minOf { it.first.ageAt(at).toMinutes() }.toDouble()
        val byFreshness = (1.0 - freshest / MAX_AGE.toMinutes().toDouble()).coerceIn(0.0, 1.0)
        // More stations is better, with sharply diminishing returns: the second
        // is worth a great deal because it gives the first something to be
        // checked against; the sixth adds very little.
        val byCount = 1.0 - 1.0 / (1.0 + weighted.size)
        return byNearest * byFreshness * byCount
    }

    private fun List<Pair<WeatherObservation, Double>>.weightedMean(
        select: (WeatherObservation) -> Double?,
    ): Double? {
        var total = 0.0
        var weights = 0.0
        forEach { (observation, weight) ->
            val value = select(observation) ?: return@forEach
            total += value * weight
            weights += weight
        }
        return if (weights <= 0.0) null else total / weights
    }

    private const val METRES_PER_KM = 1000.0
}
