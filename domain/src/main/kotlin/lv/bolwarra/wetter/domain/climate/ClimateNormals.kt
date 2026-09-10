package lv.bolwarra.wetter.domain.climate

import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.ceil

/** One past day at a place, as the archive records it. */
data class ArchivedDay(
    val date: LocalDate,
    /** °C */
    val high: Double?,
    /** °C */
    val low: Double?,
    /** mm over the whole day */
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
 * Deliberately not shaped like [lv.bolwarra.wetter.domain.model.DailyWeather].
 * It is a different kind of statement and must not be substitutable for one: no
 * condition, because there is no sky that a decade of Septembers agrees on, and
 * a share of years rather than a probability of rain, because that is the thing
 * actually counted.
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
     * The upper and lower tails of what this date does here, and how often it
     * thunders. Null where the archive did not carry the variable.
     *
     * These are what make a warning mean something. A threshold that is right
     * for a place is not a number anybody can write down once: thirty-two
     * degrees of heat index is a September night in Kolkata and a heatwave in
     * Rīga, and an app that says the same word about both is only saying it
     * about one of them. See `HazardAnnouncements`.
     */
    val warmTail: Double? = null,
    val coldTail: Double? = null,
    /** m/s. */
    val gustTail: Double? = null,
    /**
     * The same three again, further out: about the worst comparable day in a
     * decade rather than the worst in twenty. This is what lets a place have a
     * *danger* level of its own instead of borrowing one set for the planet.
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
 * ### Why this exists at all
 *
 * The Month page is thirty days long and no service forecasts thirty days. The
 * ensembles that reach that far stop carrying information well before they stop
 * producing numbers — measured at Rīga, day 33 of a 31-member GEFS run had two
 * fifths of its members wet, which is the base rate for October there, and
 * 16.8 °C between its warmest and coldest member. Dressing that up as a forecast
 * would be the app inventing weather.
 *
 * What *is* honest for a day three weeks out is what that date has actually done
 * before. It is not a prediction and it is not drawn as one; it is the only
 * truthful thing there is to put in the square.
 *
 * ### The window is the whole trick
 *
 * Ten years gives ten samples for a date, and ten samples of Baltic weather is
 * not a climate — it is noise with a seasonal trend under it. Measured at Rīga
 * the raw medians ran 16.5, 14.2, 13.9, 13.1 °C across four consecutive days of
 * late September, and none of those steps is real: September does not cool by
 * 2.3 degrees in a day and warm back up.
 *
 * Pooling [WINDOW_DAYS] either side puts about a hundred and ten samples behind
 * each date and the same four days come out 14.7, 14.4, 14.2, 14.2 — a seasonal
 * decline, which is what a normal is supposed to be. The window costs a little
 * sharpness at the turn of the seasons and buys a curve that is not an artefact
 * of how many Septembers happened to be warm.
 *
 * ### Wet is counted, not averaged
 *
 * A mean rainfall for a date is dominated by the one year a storm sat over the
 * city, and reads as though that is a normal Tuesday. The share of past days
 * that were wet at all is the figure that survives an outlier and is also the
 * one anybody actually wants: how often does this date rain.
 */
object ClimateNormals {

    /**
     * Days either side of a date that are pooled into its normal.
     *
     * Five, from measurement rather than convention: three still left visible
     * steps between neighbouring dates at ten years of history, and seven began
     * to flatten the shoulders of the seasons without making the middle of them
     * any steadier.
     */
    const val WINDOW_DAYS = 5

    /**
     * A day counts as wet at a millimetre, which is the conventional "rain day".
     *
     * Deliberately not one of [PrecipitationIntensity]'s constants and
     * deliberately not near them. Those are *rates* in millimetres per hour and
     * this is an *accumulation* over a whole day; the two are only the same
     * number for a one-hour window, and treating one as the other is exactly the
     * mistake that once had a day of thin drizzle marked as rain. A separate
     * name in a separate unit is the cheapest way to keep them from being
     * confused again.
     */
    const val WET_DAY_MM = 1.0

    /**
     * Below this the medians are being taken over too little to mean anything and
     * the date is left out, so the page draws an empty square rather than a
     * number nobody should read.
     */
    const val LEAST_USEFUL_SAMPLE = 30

    fun build(days: List<ArchivedDay>, window: Int = WINDOW_DAYS): Climatology {
        if (days.isEmpty()) return Climatology(emptyMap())

        // Grouped by calendar date rather than by day-of-year: the 60th day of
        // the year is 1 March in three years out of four and 29 February in the
        // fourth, and pooling those together shifts every spring normal by a day
        // in leap years.
        val byMonthDay = HashMap<MonthDay, MutableList<ArchivedDay>>()
        days.forEach { day ->
            val key = MonthDay.of(day.date.month, day.date.dayOfMonth)
            byMonthDay.getOrPut(key) { mutableListOf() } += day
        }

        val normals = HashMap<MonthDay, DayNormal>()
        byMonthDay.keys.forEach { monthDay ->
            // A leap year, so 29 February is a real date to walk through and
            // gets a window like every other day.
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
     * Where "unusual for this place at this time of year" is drawn.
     *
     * The 95th percentile, which with about a hundred and ten samples behind a
     * date is roughly the warmest — or windiest — one day in twenty: something
     * like a day a month at this point in the year. Rare enough that saying so
     * is news, common enough that the figure is actually estimable from ten
     * years rather than being the single worst day on record.
     *
     * Deliberately not the median plus some number of degrees. A place with
     * steady weather and a place with wild weather can share a median and have
     * nothing else in common, and the whole point of this is to ask how far out
     * of the ordinary a day is *here*.
     */
    const val UNUSUAL_HERE = 0.95

    /**
     * Where "about as bad as this place gets at this time of year" is drawn.
     *
     * The 99th percentile, which over a decade and an eleven-day window is
     * around the second-worst comparable day in ten years. Coarse, and
     * deliberately so: it is being asked to stand in for a danger level, and a
     * danger level that fires more than about once a decade is not one.
     */
    const val EXCEPTIONAL_HERE = 0.99

    /**
     * The value at a rank, by nearest-rank rather than by interpolation.
     *
     * The same reasoning as the median below it: an interpolated percentile is a
     * number that never happened, and the entire use of this is to say what an
     * unusual day at this place actually looks like.
     */
    private fun List<Double>.percentile(fraction: Double): Double? {
        if (size < LEAST_USEFUL_SAMPLE) return null
        val sorted = sorted()
        val rank = ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * The middle value, taking the lower of the two in an even-sized set.
     *
     * Not the mean of the middle pair. A median is chosen here precisely because
     * it is a value that actually happened, and averaging two of them puts back
     * the invented number the median was avoiding.
     */
    private fun List<Double>.median(): Double? {
        if (isEmpty()) return null
        return sorted()[(size - 1) / 2]
    }

    /** Any year with a 29 February, so the window can be walked across one. */
    private const val LEAP_YEAR = 2024
}
