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
        val midnightUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant()

        // Is there a sunrise at all? Decided on the noon position, which is the
        // day's most favourable one - if the sun cannot reach the horizon then,
        // it does not reach it at any hour.
        val atNoon = solarPosition(noonUtc)
        if (hourAngleFor(latitude, atNoon.declination) == null) {
            val polarDay = elevationDegrees(noonUtc, latitude, longitude) > 0.0
            return SolarDay(sunrise = null, sunset = null, isPolarDay = polarDay)
        }

        // Then each end is solved for separately, and iterated.
        //
        // The sun's declination is not a property of the day; it moves about
        // four tenths of a degree between one equinox midnight and the next. At
        // Longyearbyen in September sunrise is nearly eight hours before noon,
        // so a declination taken at noon is an eighth of a degree wrong for the
        // moment being solved for - and near a grazing sun an eighth of a degree
        // is minutes.
        //
        // So the noon position gives a first guess at each time, the position is
        // recomputed *at that time*, and the time is solved again. Two passes
        // are enough: the correction is small and the second one moves it by
        // seconds. Measured at Longyearbyen, this is worth about two minutes on
        // sunset and a minute and a half on sunrise; at Rīga, twenty seconds.
        var sunrise = midnightUtc.plusSeconds(
            (
                (
                    720.0 - 4.0 * (longitude + hourAngleFor(latitude, atNoon.declination)!!) -
                        atNoon.equationOfTime
                    ) * 60.0
                ).toLong(),
        )
        var sunset = midnightUtc.plusSeconds(
            (
                (
                    720.0 - 4.0 * (longitude - hourAngleFor(latitude, atNoon.declination)!!) -
                        atNoon.equationOfTime
                    ) * 60.0
                ).toLong(),
        )

        repeat(REFINEMENTS) {
            sunrise = solve(sunrise, latitude, longitude, midnightUtc, rising = true) ?: sunrise
            sunset = solve(sunset, latitude, longitude, midnightUtc, rising = false) ?: sunset
        }

        return SolarDay(sunrise = sunrise, sunset = sunset, isPolarDay = false)
    }

    /**
     * One end of the day, solved again from the sun's position at the time the
     * previous pass produced. Null when that position puts the sun out of reach
     * of the horizon, in which case the previous answer stands.
     */
    private fun solve(
        approximate: Instant,
        latitude: Double,
        longitude: Double,
        midnightUtc: Instant,
        rising: Boolean,
    ): Instant? {
        val position = solarPosition(approximate)
        val hourAngle = hourAngleFor(latitude, position.declination) ?: return null
        val signed = if (rising) hourAngle else -hourAngle
        val minutes = 720.0 - 4.0 * (longitude + signed) - position.equationOfTime
        return midnightUtc.plusSeconds((minutes * 60.0).toLong())
    }

    /**
     * How far from solar noon the sun sits on the horizon, in degrees, or null
     * where it never gets there on this date.
     */
    private fun hourAngleFor(latitude: Double, declination: Double): Double? {
        val latRad = Math.toRadians(latitude)
        val cosHourAngle =
            (cos(Math.toRadians(SUNRISE_ZENITH_DEGREES)) / (cos(latRad) * cos(declination))) -
                (tan(latRad) * tan(declination))
        if (cosHourAngle !in -1.0..1.0) return null
        return Math.toDegrees(acos(cosHourAngle))
    }

    /** Two passes. The second moves the answer by seconds; a third by none. */
    private const val REFINEMENTS = 2

    /** Whether the sun is above the horizon at an instant. */
    fun isDaylight(instant: Instant, latitude: Double, longitude: Double): Boolean =
        elevationDegrees(instant, latitude, longitude) > -(SUNRISE_ZENITH_DEGREES - 90.0)

    /** The sun's elevation above the horizon, in degrees. Negative below. */
    fun elevationDegrees(instant: Instant, latitude: Double, longitude: Double): Double {
        val (declination, equationOfTime) = solarPosition(instant)

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

    /**
     * Where the sun is, to a hundredth of a degree.
     *
     * ### Why not the short version
     *
     * This used to be the Fourier series that circulates as "the NOAA
     * equations" - seven terms for the declination, five for the equation of
     * time, and no Julian date anywhere. It is a genuinely useful approximation
     * and it is wrong by up to 0.43 degrees, with the error at its worst
     * precisely at the equinoxes, where it read the declination as -0.46 on
     * 20 March 2026 when the true value was -0.04. That is the equinox itself
     * misplaced by most of a day.
     *
     * Half a degree of declination is nothing at noon and everything at dawn,
     * because near sunrise the sun moves almost horizontally and it is the
     * *vertical* rate that turns an angle into a time. Measured against an
     * almanac, the old series made the day too long by 5.4 minutes at Rīga, 9.6
     * at Tromsø and 18.7 at Longyearbyen - always too long, always at both ends,
     * growing with latitude, which is the fingerprint of a declination error
     * rather than a clock or a timezone one.
     *
     * So this is the full algorithm the NOAA calculator itself runs: mean
     * longitude and anomaly from the Julian century, the equation of centre,
     * the correction for nutation, and the true obliquity. It is perhaps twenty
     * floating-point operations more expensive, runs once per day per place,
     * and is accurate to about a hundredth of a degree over several centuries -
     * which is to say, to well under the minute the screen prints.
     */
    private data class SolarPosition(
        /** Radians. */
        val declination: Double,
        /** Minutes by which the sun runs ahead of the clock. */
        val equationOfTime: Double,
    )

    private fun solarPosition(instant: Instant): SolarPosition {
        // Julian centuries since J2000.0, straight from the epoch second: no
        // calendar arithmetic, no day-of-year, and no leap-year special case.
        val julianDay = instant.epochSecond / SECONDS_PER_DAY + JULIAN_DAY_AT_EPOCH
        val t = (julianDay - J2000) / DAYS_PER_JULIAN_CENTURY

        val meanLongitude = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val anomaly = Math.toRadians(meanAnomaly)

        // The orbit is an ellipse, so the sun runs ahead of its mean position
        // for half the year and behind for the other half.
        val centre = sin(anomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * anomaly) * (0.019993 - 0.000101 * t) +
            sin(3 * anomaly) * 0.000289

        // The moon's node wobbles the apparent position by a few arcseconds a
        // year. Small, but it is the difference between this and the series it
        // replaces having any point.
        val node = Math.toRadians(125.04 - 1934.136 * t)
        val apparentLongitude = Math.toRadians(
            meanLongitude + centre - 0.00569 - 0.00478 * sin(node),
        )

        val meanObliquity = 23.0 +
            (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = Math.toRadians(meanObliquity + 0.00256 * cos(node))

        val declination = asin(sin(obliquity) * sin(apparentLongitude))

        val y = tan(obliquity / 2.0).let { it * it }
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val longitudeRad = Math.toRadians(meanLongitude)
        val equationOfTime = MINUTES_PER_DEGREE * Math.toDegrees(
            y * sin(2 * longitudeRad) -
                2 * eccentricity * sin(anomaly) +
                4 * eccentricity * y * sin(anomaly) * cos(2 * longitudeRad) -
                0.5 * y * y * sin(4 * longitudeRad) -
                1.25 * eccentricity * eccentricity * sin(2 * anomaly),
        )

        return SolarPosition(declination, equationOfTime)
    }

    private const val SECONDS_PER_DAY = 86_400.0

    /** The Julian day at 1970-01-01T00:00:00Z. */
    private const val JULIAN_DAY_AT_EPOCH = 2_440_587.5

    /** The Julian day at J2000.0, which is 2000-01-01T12:00:00 TT. */
    private const val J2000 = 2_451_545.0

    private const val DAYS_PER_JULIAN_CENTURY = 36_525.0

    /** The earth turns a degree every four minutes. */
    private const val MINUTES_PER_DEGREE = 4.0
}

/**
 * What the sun did on one date at one place.
 *
 * Both times are null above the polar circles, where [isPolarDay] says whether
 * the sun stayed up or stayed down — a distinction that decides whether the
 * whole timeline is shaded or none of it is.
 */
data class SolarDay(val sunrise: Instant?, val sunset: Instant?, val isPolarDay: Boolean) {
    val hasSunriseAndSunset: Boolean get() = sunrise != null && sunset != null

    val dayLength: Duration?
        get() = if (sunrise != null && sunset != null) Duration.between(sunrise, sunset) else null
}
