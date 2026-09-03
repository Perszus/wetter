package lv.bolwarra.wetter.domain.observation

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A place that reports what the weather actually is. */
data class ObservationStation(
    /** The reporting identifier - an ICAO code for an airport. */
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level. The reason two nearby stations disagree. */
    val elevationMetres: Double,
) {
    /**
     * Great-circle distance in kilometres.
     *
     * Spherical rather than a flat approximation: at the latitudes this app is
     * used in, a degree of longitude is about half a degree of latitude, and
     * treating them alike would make a station due east look twice as far away
     * as it is - which is exactly the error that would pick the wrong station.
     */
    fun distanceKmTo(latitude: Double, longitude: Double): Double {
        val dLat = Math.toRadians(latitude - this.latitude)
        val dLon = Math.toRadians(longitude - this.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(this.latitude)) * cos(Math.toRadians(latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private companion object {
        const val EARTH_RADIUS_KM = 6371.0
    }
}

/**
 * What one station measured, at one moment.
 *
 * An observation, not a forecast, and the distinction is the whole point of this
 * package: everything else in the app is a prediction, and there is no way to
 * tell whether a prediction was any good without something that was actually
 * measured to compare it against.
 *
 * Precipitation arrives as a category rather than a rate. A routine report says
 * that light rain is falling, not that 0.4 mm fell in the last ten minutes, so
 * this carries [precipitating] and an intensity qualifier and does not invent a
 * number. That is enough to verify whether rain was forecast correctly, which is
 * the question worth asking.
 */
data class WeatherObservation(
    val station: ObservationStation,
    val at: Instant,
    /** Celsius. */
    val temperature: Double?,
    /** Celsius. */
    val dewPoint: Double?,
    /** Metres per second. */
    val windSpeed: Double?,
    /** Degrees clockwise from north, where the wind blows from. */
    val windDirection: Int?,
    /** Hectopascals, reduced to sea level. */
    val pressure: Double?,
    /** Metres. Capped by the reporting convention, so "10000" often means "more". */
    val visibilityMetres: Double?,
    /** Null when the report says nothing either way, which is not the same as dry. */
    val precipitating: Boolean?,
    /** Present-weather qualifier, when something is falling. */
    val intensity: ObservedIntensity?,
) {
    fun ageAt(instant: Instant): Duration = Duration.between(at, instant)

    /**
     * Whether this reading is physically possible.
     *
     * A stuck or failed sensor reports numbers rather than reporting nothing,
     * and one broken station left in the average is worse than none at all:
     * everything downstream is derived from these, so a -60 C in a Baltic
     * September would propagate into the bias correction and stay there.
     */
    fun isPlausible(): Boolean {
        val t = temperature
        if (t != null && (t < MIN_TEMPERATURE || t > MAX_TEMPERATURE)) return false
        val d = dewPoint
        // Dew point above air temperature is thermodynamically impossible. A
        // small overshoot is rounding in a saturated report and is allowed.
        if (t != null && d != null && d > t + DEW_POINT_SLACK) return false
        val w = windSpeed
        if (w != null && (w < 0 || w > MAX_WIND_MS)) return false
        val p = pressure
        if (p != null && (p < MIN_PRESSURE || p > MAX_PRESSURE)) return false
        val bearing = windDirection
        if (bearing != null && (bearing < 0 || bearing > FULL_TURN)) return false
        return true
    }

    private companion object {
        const val MIN_TEMPERATURE = -90.0
        const val MAX_TEMPERATURE = 60.0
        const val DEW_POINT_SLACK = 0.6
        const val MAX_WIND_MS = 120.0
        const val MIN_PRESSURE = 850.0
        const val MAX_PRESSURE = 1100.0
        const val FULL_TURN = 360
    }
}

/** How hard it is falling, as a routine report describes it. */
enum class ObservedIntensity { LIGHT, MODERATE, HEAVY }

/** Two observations of the same station, for spotting a sensor that has stopped. */
fun List<WeatherObservation>.hasStuckTemperature(): Boolean {
    val readings = mapNotNull { it.temperature }
    if (readings.size < STUCK_READINGS) return false
    // Real air temperature moves. Several hours of a bit-identical value is a
    // sensor reporting its last good reading for ever, which no plausibility
    // range would catch because the value itself is perfectly reasonable.
    return readings.all { abs(it - readings.first()) < STUCK_TOLERANCE }
}

private const val STUCK_READINGS = 8
private const val STUCK_TOLERANCE = 0.001
