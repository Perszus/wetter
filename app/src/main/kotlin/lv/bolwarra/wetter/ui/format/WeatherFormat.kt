package lv.bolwarra.wetter.ui.format

import androidx.annotation.StringRes
import lv.bolwarra.wetter.R
import lv.bolwarra.wetter.domain.model.WeatherCondition
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The one place where domain values become text.
 *
 * Keeping this out of the composables means a unit change or a wording change is
 * a single edit, and it is testable without a device.
 */

/**
 * Temperatures are rounded, never truncated, and always carry the degree sign
 * without a space — "18°". The unit letter is shown once in the header rather
 * than after every number.
 */
fun formatTemperature(celsius: Double): String = "${celsius.roundToInt()}°"

/** Millimetres, to one decimal below 10 and whole above it. */
fun formatMillimetres(mm: Double): String =
    if (mm < 10.0) String.format(Locale.getDefault(), "%.1f", mm) else mm.roundToInt().toString()

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
