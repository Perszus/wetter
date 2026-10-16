// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.climate

import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.ceil

/** One past day at a place, as the archive records it. */
data class ArchivedDay(
    val date: LocalDate,
    /** °C. */
    val high: Double?,
    /** °C. */
    val low: Double?,
    /** mm over the whole day. */
    val precipitation: Double?,
    /** °C, what the warmest part of the day felt like. */
    val apparentHigh: Double? = null,
    /** °C, what the coldest part of the day felt like. */
    val apparentLow: Double? = null,
    /** m/s, the strongest gust of the day. */
    val gust: Double? = null,
)

/**
 * What a date at this place usually does.
 *
 * Not shaped like [lv.bolwarra.wetter.domain.model.DailyWeather], so
 * it cannot stand in for a forecast: no condition, and a share of wet years rather
 * than a probability of rain.
 */
data class DayNormal(
    val monthDay: MonthDay,
    /** Median of the daily maxima in the window, °C. */
    val medianHigh: Double?,
    /** Median of the daily minima in the window, °C. */
    val medianLow: Double?,
    /** The fraction of days in the window that were wet, 0..1. */
    val wetShare: Double,
    /** How many past days the medians were taken over. */
    val samples: Int,
    /**
     * The tails of what this date does here, and how often it thunders; null where
     * the archive did not carry the variable. They give warnings a threshold that
     * belongs to the place (see `HazardAnnouncements`).
     */
    val warmTail: Double? = null,
    val coldTail: Double? = null,
    /** m/s. */
    val gustTail: Double? = null,
    /**
     * The same three further out, about the worst comparable day in a decade: a
     * danger level of the place's own.
     */
    val warmExtreme: Double? = null,
    val coldExtreme: Double? = null,
    /** m/s. */
    val gustExtreme: Double? = null,
)

/** A whole year of [DayNormal], ready to be read by date. */
data class Climatology(private val byDay: Map<MonthDay, DayNormal>) {

    fun at(date: LocalDate): DayNormal? = byDay[MonthDay.of(date.month, date.dayOfMonth)]

    val isEmpty: Boolean get() = byDay.isEmpty()

    val size: Int get() = byDay.size
}

/**
 * Turns a decade of archived days into what each date usually does.
 *
 * The Month page reaches past where any forecast carries information, so beyond
 * it each square shows what that date has done before. It is not a prediction
 * and is not drawn as one.
 *
 * Ten years gives ten samples per date, which is noise with a seasonal trend under
 * it, so [WINDOW_DAYS] either side are pooled into each date's normal. Wet is
 * counted, not averaged: the share of wet days survives the one storm year a mean
 * would be dominated by.
 */
object ClimateNormals {

    /**
     * Days either side of a date pooled into its normal. Three left visible steps
     * between neighbouring dates; seven flattened the seasons' shoulders.
     */
    const val WINDOW_DAYS = 5

    /**
     * A day counts as wet at a millimetre, the conventional rain day. An accumulation
     * over a day, not a rate like [PrecipitationIntensity]'s constants.
     */
    const val WET_DAY_MM = 1.0

    /**
     * Below this many samples a date is left out, so the page draws an empty square
     * rather than a number nobody should read.
     */
    const val LEAST_USEFUL_SAMPLE = 30

    fun build(days: List<ArchivedDay>, window: Int = WINDOW_DAYS): Climatology {
        if (days.isEmpty()) return Climatology(emptyMap())

        // Grouped by calendar date rather than day-of-year, which would shift every
        // spring normal by a day in leap years.
        val byMonthDay = HashMap<MonthDay, MutableList<ArchivedDay>>()
        days.forEach { day ->
            val key = MonthDay.of(day.date.month, day.date.dayOfMonth)
            byMonthDay.getOrPut(key) { mutableListOf() } += day
        }

        val normals = HashMap<MonthDay, DayNormal>()
        byMonthDay.keys.forEach { monthDay ->
            // A leap year, so 29 February gets a window like every other day.
            val anchor = monthDay.atYear(LEAP_YEAR)
            val pooled = (-window..window).flatMap { offset ->
                val at = anchor.plusDays(offset.toLong())
                byMonthDay[MonthDay.of(at.month, at.dayOfMonth)].orEmpty()
            }

            val highs = pooled.mapNotNull { it.high }
            val lows = pooled.mapNotNull { it.low }
            val rain = pooled.mapNotNull { it.precipitation }
            if (rain.size < LEAST_USEFUL_SAMPLE) return@forEach

            val apparentHighs = pooled.mapNotNull { it.apparentHigh ?: it.high }
            val apparentLows = pooled.mapNotNull { it.apparentLow ?: it.low }
            val gusts = pooled.mapNotNull { it.gust }

            normals[monthDay] = DayNormal(
                monthDay = monthDay,
                medianHigh = highs.median(),
                medianLow = lows.median(),
                wetShare = rain.count { it >= WET_DAY_MM }
                    .toDouble() / rain.size,
                samples = rain.size,
                warmTail = apparentHighs.percentile(UNUSUAL_HERE),
                coldTail = apparentLows.percentile(1.0 - UNUSUAL_HERE),
                gustTail = gusts.percentile(UNUSUAL_HERE),
                warmExtreme = apparentHighs.percentile(EXCEPTIONAL_HERE),
                coldExtreme = apparentLows.percentile(1.0 - EXCEPTIONAL_HERE),
                gustExtreme = gusts.percentile(EXCEPTIONAL_HERE),
            )
        }
        return Climatology(normals)
    }

    /**
     * Where "unusual for this place at this time of year" is drawn: the 95th
     * percentile, roughly one day in twenty. A percentile rather than the median plus
     * some degrees, because places with the same median can differ in everything
     * else.
     */
    const val UNUSUAL_HERE = 0.95

    /**
     * Where "about as bad as this place gets at this time of year" is drawn: the 99th
     * percentile, around the second-worst comparable day in ten years.
     */
    const val EXCEPTIONAL_HERE = 0.99

    /**
     * The value at a rank, by nearest rank: an interpolated percentile is a number
     * that never happened.
     */
    private fun List<Double>.percentile(fraction: Double): Double? {
        if (size < LEAST_USEFUL_SAMPLE) return null
        val sorted = sorted()
        val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * The middle value, taking the lower of an even-sized pair rather than averaging
     * them, so it is a value that actually happened.
     */
    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        return sorted()[(size - 1) / 2]
    }

    /** Any year with a 29 February, so the window can be walked across one. */
    private const val LEAP_YEAR = 2024
}
