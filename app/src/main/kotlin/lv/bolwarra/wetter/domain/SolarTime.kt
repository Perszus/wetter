package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is, from a date and a place.
 *
 * Wetter needs this because not every provider supplies it. MET Norway publishes
 * no sunrise, no sunset and no day flag, and the timeline's night wash is not
 * optional decoration — an unshaded 03:00 reads as an afternoon. Computing it
 * costs one file of arithmetic and works offline, whereas asking a second
 * service would mean a second network call, a second failure mode and a second
 * set of terms to honour.
 *
 * The NOAA general solar position equations, which are accurate to about a
 * minute at these latitudes. That is far inside the precision anyone reads off a
 * shaded band, and the algorithm has no data files and no dependencies.
 */
object SolarTime {

    /**
     * Standard refraction correction: the sun is called risen when its centre is
     * 50 arcminutes below the horizon, because the atmosphere bends the image up
     * by roughly that much and the disc has a radius of its own.
     */
    private const val SUNRISE_ZENITH_DEGREES = 90.833

    /** Sunrise and sunset for a calendar date at a place, or null when neither happens. */
    fun sunriseSunset(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
    ): SolarDay {
        // Noon local is the reference point for the day's declination: using
        // midnight would compute a declination up to twelve hours stale, which
        // matters near the equinoxes at high latitude.
        val noonUtc = date.atTime(12, 0).atZone(zone).toInstant()
        val gamma = fractionalYear(noonUtc)
        val declination = declination(gamma)
        val equationOfTime = equationOfTime(gamma)

        val latRad = Math.toRadians(latitude)
        val cosHourAngle =
            (cos(Math.toRadians(SUNRISE_ZENITH_DEGREES)) / (cos(latRad) * cos(declination))) -
                (tan(latRad) * tan(declination))

        if (cosHourAngle !in -1.0..1.0) {
            // The sun never crosses the horizon on this date. Which side it stays
            // on is decided by its elevation at local solar noon.
            val polarDay = elevationDegrees(noonUtc, latitude, longitude) > 0.0
            return SolarDay(sunrise = null, sunset = null, isPolarDay = polarDay)
        }

        val hourAngle = Math.toDegrees(acos(cosHourAngle))
        val midnightUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant()

        val sunriseMinutes = 720.0 - 4.0 * (longitude + hourAngle) - equationOfTime
        val sunsetMinutes = 720.0 - 4.0 * (longitude - hourAngle) - equationOfTime

        return SolarDay(
            sunrise = midnightUtc.plusSeconds((sunriseMinutes * 60.0).toLong()),
            sunset = midnightUtc.plusSeconds((sunsetMinutes * 60.0).toLong()),
            isPolarDay = false,
        )
    }

    /** Whether the sun is above the horizon at an instant. */
    fun isDaylight(instant: Instant, latitude: Double, longitude: Double): Boolean =
        elevationDegrees(instant, latitude, longitude) > -(SUNRISE_ZENITH_DEGREES - 90.0)

    /** The sun's elevation above the horizon, in degrees. Negative below. */
    fun elevationDegrees(instant: Instant, latitude: Double, longitude: Double): Double {
        val gamma = fractionalYear(instant)
        val declination = declination(gamma)
        val equationOfTime = equationOfTime(gamma)

        val utc = instant.atZone(ZoneOffset.UTC)
        val minutesUtc = utc.hour * 60.0 + utc.minute + utc.second / 60.0
        val trueSolarTime = minutesUtc + equationOfTime + 4.0 * longitude
        // Solar noon is 720 minutes of true solar time; every four minutes either
        // side is one degree of hour angle.
        val hourAngle = Math.toRadians(trueSolarTime / 4.0 - 180.0)

        val latRad = Math.toRadians(latitude)
        val sinElevation =
            sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(hourAngle)

        return Math.toDegrees(asin(sinElevation.coerceIn(-1.0, 1.0)))
    }

    /** How far through the orbit the date is, in radians. */
    private fun fractionalYear(instant: Instant): Double {
        val utc = instant.atZone(ZoneOffset.UTC)
        val daysInYear = if (utc.toLocalDate().isLeapYear) 366.0 else 365.0
        val dayOfYear = utc.dayOfYear
        return 2.0 * Math.PI / daysInYear * (dayOfYear - 1 + (utc.hour - 12) / 24.0)
    }

    /** Solar declination in radians — the Fourier series from the NOAA equations. */
    private fun declination(gamma: Double): Double =
        0.006918 -
            0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.001480 * sin(3 * gamma)

    /**
     * The difference between clock noon and solar noon, in minutes. It swings by
     * about half an hour over a year and is the reason the earliest sunset is
     * not the shortest day.
     */
    private fun equationOfTime(gamma: Double): Double =
        229.18 * (
            0.000075 +
                0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
}

/**
 * What the sun did on one date at one place.
 *
 * Both times are null above the polar circles, where [isPolarDay] says whether
 * the sun stayed up or stayed down — a distinction that decides whether the
 * whole timeline is shaded or none of it is.
 */
data class SolarDay(
    val sunrise: Instant?,
    val sunset: Instant?,
    val isPolarDay: Boolean,
) {
    val hasSunriseAndSunset: Boolean get() = sunrise != null && sunset != null

    val dayLength: Duration?
        get() = if (sunrise != null && sunset != null) Duration.between(sunrise, sunset) else null
}
