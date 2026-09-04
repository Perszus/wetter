package lv.bolwarra.wetter.ui.format

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.CompassPoint
import lv.bolwarra.wetter.domain.MoonPhaseName
import lv.bolwarra.wetter.domain.air.AirQualityBand
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.sky.TideState

/**
 * The one place where domain values become text.
 *
 * Keeping this out of the composables means a unit change or a wording change is
 * a single edit, and it is testable without a device.
 */

/**
 * What a reading looks like when there isn't one. An em dash, not a zero and not
 * an empty string: the row keeps its shape and says plainly that the number is
 * missing.
 */
const val NO_READING: String = "\u2014"

/**
 * Temperatures are rounded, never truncated, and always carry the degree sign
 * without a space. The unit letter is shown once in the header rather than after
 * every number.
 */
fun formatTemperature(celsius: Double?): String =
    if (celsius == null) NO_READING else "${celsius.roundToInt()}\u00b0"

/**
 * A temperature adjustment, signed and to one decimal.
 *
 * Signed on purpose, and to a finer resolution than a temperature: the whole
 * point of showing it is that it is a small correction with a direction, and
 * rounding it the way a reading is rounded would turn most corrections into
 * "0" or hide which way they went.
 */
fun formatTemperatureDelta(celsius: Double): String {
    val sign = if (celsius > 0) "+" else "−"
    return "$sign${String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(celsius))}°"
}

/** Millimetres, to one decimal below 10 and whole above it. */
fun formatMillimetres(mm: Double?): String = when {
    mm == null -> NO_READING
    mm < 10.0 -> String.format(Locale.getDefault(), "%.1f", mm)
    else -> mm.roundToInt().toString()
}

/** "4.2 mm", or an em dash. Used where the unit is not already in the label. */
fun formatMillimetresWithUnit(mm: Double?): String =
    if (mm == null) NO_READING else "${formatMillimetres(mm)} mm"

/** Whole metres per second. Sub-unit precision on wind is noise. */
fun formatWindSpeed(metresPerSecond: Double?): String =
    if (metresPerSecond == null) NO_READING else "${metresPerSecond.roundToInt()} m/s"

fun formatPercent(value: Int?): String = if (value == null) NO_READING else "$value%"

fun formatPressure(hectopascals: Double?): String =
    if (hectopascals == null) NO_READING else "${hectopascals.roundToInt()} hPa"

/** Clock time in the location's own zone, 24-hour. */
fun formatTime(instant: Instant?, zone: ZoneId): String =
    if (instant == null) NO_READING else HOUR_MINUTE.format(instant.atZone(zone))

/** "11h 37m" - how long the sun is up, or how long a shower lasts. */
fun formatDuration(duration: Duration?): String {
    if (duration == null) return NO_READING
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return when {
        hours == 0L -> "${minutes}m"
        // "1h 0m" is not how anybody writes an hour.
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

/** Short weekday for a column of days: "Mon". */
fun formatWeekdayShort(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** Day of the month, for the second line of a daily row. */
fun formatDayOfMonth(date: LocalDate): String = date.dayOfMonth.toString()

/** Full weekday, for a sentence rather than a column. */
fun formatWeekday(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

/**
 * How far off a day is, in the terms people actually use for it.
 *
 * The distinction is not decorative. "Rain starts at 23:00" is a fine thing to
 * say about tonight and a misleading thing to say about next Wednesday, and the
 * boundary between a weekday name and a date is the point where the name stops
 * being unambiguous — seven days out, "Wednesday" is today's name again.
 */
enum class DayDistance { TODAY, TOMORROW, THIS_WEEK, LATER }

/** Which of those a moment falls into, judged by calendar date in [zone]. */
fun dayDistance(instant: Instant, now: Instant, zone: ZoneId): DayDistance {
    val days = daysBetween(instant, now, zone)
    return when {
        days <= 0L -> DayDistance.TODAY
        days == 1L -> DayDistance.TOMORROW
        days < DAYS_IN_WEEK -> DayDistance.THIS_WEEK
        else -> DayDistance.LATER
    }
}

/**
 * "12 September", ordered the way the reader's locale orders it.
 *
 * The year is deliberately absent: no forecast reaches far enough for it to be
 * in question.
 */
fun formatDayAndMonth(date: LocalDate): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), DAY_MONTH_SKELETON)
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)
}

private const val DAYS_IN_WEEK = 7L
private const val DAY_MONTH_SKELETON = "dMMMM"

/** How many whole calendar days apart two instants are, in the location's zone. */
fun daysBetween(instant: Instant, now: Instant, zone: ZoneId): Long = ChronoUnit.DAYS.between(
    now.atZone(zone).toLocalDate(),
    instant.atZone(zone).toLocalDate(),
)

/**
 * What an intensity is called.
 *
 * The reason this exists at all: millimetres per hour is a unit almost nobody
 * has a feel for, including people who work near meteorology. "Light" lands.
 */
@StringRes
fun PrecipitationIntensity.labelRes(): Int = when (this) {
    PrecipitationIntensity.NONE -> R.string.intensity_none
    PrecipitationIntensity.TRACE -> R.string.intensity_trace
    PrecipitationIntensity.LIGHT -> R.string.intensity_light
    PrecipitationIntensity.MODERATE -> R.string.intensity_moderate
    PrecipitationIntensity.HEAVY -> R.string.intensity_heavy
    PrecipitationIntensity.VIOLENT -> R.string.intensity_violent
}

@StringRes
fun PrecipitationKind.labelRes(): Int = when (this) {
    PrecipitationKind.NONE -> R.string.kind_precipitation
    PrecipitationKind.RAIN -> R.string.kind_rain
    PrecipitationKind.SNOW -> R.string.kind_snow
    PrecipitationKind.MIXED -> R.string.kind_sleet
}

@StringRes
fun WeatherCondition.labelRes(): Int = when (this) {
    WeatherCondition.CLEAR -> R.string.condition_clear
    WeatherCondition.MAINLY_CLEAR -> R.string.condition_mainly_clear
    WeatherCondition.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
    WeatherCondition.OVERCAST -> R.string.condition_overcast
    WeatherCondition.FOG -> R.string.condition_fog
    WeatherCondition.DRIZZLE -> R.string.condition_drizzle
    WeatherCondition.FREEZING_DRIZZLE -> R.string.condition_freezing_drizzle
    WeatherCondition.RAIN -> R.string.condition_rain
    WeatherCondition.FREEZING_RAIN -> R.string.condition_freezing_rain
    WeatherCondition.SLEET -> R.string.condition_sleet
    WeatherCondition.SNOW -> R.string.condition_snow
    WeatherCondition.SNOW_GRAINS -> R.string.condition_snow_grains
    WeatherCondition.RAIN_SHOWERS -> R.string.condition_rain_showers
    WeatherCondition.SNOW_SHOWERS -> R.string.condition_snow_showers
    WeatherCondition.THUNDERSTORM -> R.string.condition_thunderstorm
    WeatherCondition.THUNDERSTORM_WITH_HAIL -> R.string.condition_thunderstorm_hail
    WeatherCondition.UNKNOWN -> R.string.condition_unknown
}

private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The compass point a wind bearing falls in, as a short cardinal name. */
fun CompassPoint.labelRes(): Int = when (this) {
    CompassPoint.NORTH -> R.string.compass_n
    CompassPoint.NORTH_EAST -> R.string.compass_ne
    CompassPoint.EAST -> R.string.compass_e
    CompassPoint.SOUTH_EAST -> R.string.compass_se
    CompassPoint.SOUTH -> R.string.compass_s
    CompassPoint.SOUTH_WEST -> R.string.compass_sw
    CompassPoint.WEST -> R.string.compass_w
    CompassPoint.NORTH_WEST -> R.string.compass_nw
}

fun MoonPhaseName.labelRes(): Int = when (this) {
    MoonPhaseName.NEW -> R.string.moon_new
    MoonPhaseName.WAXING_CRESCENT -> R.string.moon_waxing_crescent
    MoonPhaseName.FIRST_QUARTER -> R.string.moon_first_quarter
    MoonPhaseName.WAXING_GIBBOUS -> R.string.moon_waxing_gibbous
    MoonPhaseName.FULL -> R.string.moon_full
    MoonPhaseName.WANING_GIBBOUS -> R.string.moon_waning_gibbous
    MoonPhaseName.LAST_QUARTER -> R.string.moon_last_quarter
    MoonPhaseName.WANING_CRESCENT -> R.string.moon_waning_crescent
}

/**
 * Which band a UV index falls in.
 *
 * The number alone is only meaningful to somebody who already knows the scale:
 * "7" is a hat and shade, "2" is nothing, and nothing about the digits says so.
 * The bands are the WHO's, which is what almost every country's public advice is
 * written against - so this reads the same in Riga and in Nairobi.
 */
@StringRes
fun uvBandLabel(index: Double): Int = when (index.roundToInt()) {
    in Int.MIN_VALUE..2 -> R.string.uv_low
    in 3..5 -> R.string.uv_moderate
    in 6..7 -> R.string.uv_high
    in 8..10 -> R.string.uv_very_high
    else -> R.string.uv_extreme
}

/** The index rounded, for pairing with [uvBandLabel]. */
fun formatUvIndex(index: Double): String = index.roundToInt().toString()

/**
 * A pollutant concentration, in micrograms per cubic metre.
 *
 * One decimal below ten and none above it. Below ten the difference between 3
 * and 3.9 is a third of the reading; above a hundred a decimal is noise from a
 * model with an 11 km grid.
 */
@Composable
fun formatConcentration(value: Double?): String = when {
    value == null -> NO_READING
    value < 10.0 -> stringResource(
        R.string.concentration,
        String.format(Locale.getDefault(), "%.1f", value),
    )
    else -> stringResource(R.string.concentration, value.roundToInt().toString())
}

/**
 * The tide's place in its monthly rhythm.
 *
 * In the Moon group and nowhere else, because that is the honest frame: this is
 * a fact about where the sun and moon are, not a water level. When high water
 * arrives and how far it climbs are local, set by the shape of a particular
 * coast, and would need a tide gauge to say.
 */
@StringRes
fun TideState.labelRes(): Int = when (this) {
    TideState.SPRING -> R.string.tide_spring
    TideState.NEAP -> R.string.tide_neap
    TideState.BUILDING -> R.string.tide_building
    TideState.EASING -> R.string.tide_easing
}

@StringRes
fun AirQualityBand.labelRes(): Int = when (this) {
    AirQualityBand.GOOD -> R.string.air_good
    AirQualityBand.FAIR -> R.string.air_fair
    AirQualityBand.MODERATE -> R.string.air_moderate
    AirQualityBand.POOR -> R.string.air_poor
    AirQualityBand.VERY_POOR -> R.string.air_very_poor
    AirQualityBand.EXTREMELY_POOR -> R.string.air_extremely_poor
}
