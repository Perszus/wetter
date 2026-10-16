// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * MET Norway publishes no sunrise, no sunset and no day flag, and the timeline's
 * night wash depends on them, so they are computed offline rather than fetched
 * from another service. The NOAA solar position algorithm: no data files, no
 * dependencies, and accurate to well under the minute the screen prints.
 */
object SolarTime {

    /**
     * Standard refraction correction: the sun counts as risen with its centre 50
     * arcminutes below the horizon, for atmospheric bending plus the disc's radius.
     */
    private const val SUNRISE_ZENITH_DEGREES = 90.833

    /** Sunrise and sunset for a calendar date at a place, or null when neither happens. */
    fun sunriseSunset(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
    ): SolarDay {
        // Local noon is the reference for the day's declination; midnight would be up to
        // twelve hours stale.
        val noonUtc = date.atTime(12, 0).atZone(zone).toInstant()
        val midnightUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant()

        // Whether there is a sunrise at all is decided at noon, the day's most favourable
        // position.
        val atNoon = solarPosition(noonUtc)
        if (hourAngleFor(latitude, atNoon.declination) == null) {
            val polarDay = elevationDegrees(noonUtc, latitude, longitude) > 0.0
            return SolarDay(sunrise = null, sunset = null, isPolarDay = polarDay)
        }

        // Each end is then solved separately and iterated: the declination moves through
        // the day, and near a grazing sun at high latitude a fraction of a degree is
        // minutes. The noon position gives a first guess, the position is recomputed at
        // that time, and the time solved again.
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
     * One end of the day, solved again from the sun's position at the previous pass's
     * time. Null when that position puts the sun out of reach of the horizon, and the
     * previous answer stands.
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
     * How far from solar noon the sun sits on the horizon, in degrees, or null where
     * it never gets there on this date.
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
        // Solar noon is 720 minutes of true solar time; every four minutes either side is
        // one degree of hour angle.
        val hourAngle = Math.toRadians(trueSolarTime / 4.0 - 180.0)

        val latRad = Math.toRadians(latitude)
        val sinElevation =
            sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(hourAngle)

        return Math.toDegrees(asin(sinElevation.coerceIn(-1.0, 1.0)))
    }

    /**
     * Where the sun is, to about a hundredth of a degree: the full algorithm the NOAA
     * calculator runs (mean longitude and anomaly from the Julian century, equation of
     * centre, nutation, true obliquity). The short Fourier series often quoted as "the
     * NOAA equations" is off by up to 0.43° in declination at the equinoxes, which
     * lengthens the day by minutes at high latitudes.
     */
    private data class SolarPosition(
        /** Radians. */
        val declination: Double,
        /** Minutes by which the sun runs ahead of the clock. */
        val equationOfTime: Double,
    )

    private fun solarPosition(instant: Instant): SolarPosition {
        // Julian centuries since J2000.0, straight from the epoch second: no calendar
        // arithmetic and no leap-year case.
        val julianDay = instant.epochSecond / SECONDS_PER_DAY + JULIAN_DAY_AT_EPOCH
        val t = (julianDay - J2000) / DAYS_PER_JULIAN_CENTURY

        val meanLongitude = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val anomaly = Math.toRadians(meanAnomaly)

        // The orbit is an ellipse, so the sun runs ahead of its mean position half the
        // year and behind it the other half.
        val centre = sin(anomaly) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * anomaly) * (0.019993 - 0.000101 * t) +
            sin(3 * anomaly) * 0.000289

        // The moon's node wobbles the apparent position by a few arcseconds.
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
 * What the sun did on one date at one place. Both times are null above the polar
 * circles, where [isPolarDay] says whether the sun stayed up or down.
 */
data class SolarDay(val sunrise: Instant?, val sunset: Instant?, val isPolarDay: Boolean) {
    val hasSunriseAndSunset: Boolean get() = sunrise != null && sunset != null

    val dayLength: Duration?
        get() = if (sunrise != null && sunset != null) Duration.between(sunrise, sunset) else null
}
