package lv.bolwarra.wetter.domain.hazard

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.air.AirQualityBand
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast

/** Something in the forecast that can hurt somebody. */
enum class HazardKind {
    EXTREME_HEAT,
    EXTREME_COLD,
    DAMAGING_WIND,
    TORRENTIAL_RAIN,
    HEAVY_SNOW,
    ICE,
    THUNDERSTORM,
    EXTREME_UV,
    UNBREATHABLE_AIR,
}

/**
 * Two levels, not five.
 *
 * National services run four or five and each level means something different in
 * each country. Two is what a person actually does something about: take it into
 * account, or change the plan.
 */
enum class HazardSeverity { WARNING, DANGER }

/**
 * @param until the end of the run of hours it holds for, or null when it is
 *   still going at the edge of the forecast - which is not the same as ending
 *   there, and is shown as an open end rather than as a time.
 */
data class Hazard(
    val kind: HazardKind,
    val severity: HazardSeverity,
    val from: Instant,
    val until: Instant?,
    /**
     * The strongest reading reached during the run, in whatever unit that
     * hazard is measured in - metres per second for wind, degrees for heat,
     * millimetres for rain.
     *
     * Severity has two levels because two is what a person acts on, but "worth
     * changing the plan for" covers a gale and a hurricane alike, and those are
     * not the same afternoon. The peak is what lets the label say which.
     */
    val peak: Double? = null,
) {
    /** Already happening, as opposed to on its way. */
    fun hasBegunBy(now: Instant): Boolean = !from.isAfter(now)
}

/**
 * Reads the forecast for the things worth interrupting somebody about.
 *
 * ### On the thresholds
 *
 * There is no global standard to defer to here. Every national service sets its
 * own, tuned to what its population is used to and built to trigger its own
 * response - minus twenty is an emergency in Athens and a Tuesday in Yakutsk,
 * and a service that shouted at both would be ignored by one of them.
 *
 * So these are set where the physics turns, not where a bureaucracy does:
 * Beaufort's own definitions for wind, the temperature at which exposed skin is
 * actually in danger, the WHO's bands for ultraviolet and for particulates. They
 * are written down as named constants with their reasons, because a threshold
 * nobody can find is a threshold nobody can argue with or correct.
 *
 * The bar is deliberately high. A mark that is up most of the week is furniture,
 * and the umbrella already learned that lesson once.
 */
object Hazards {

    /** How far ahead to look. Beyond a day, "a storm is coming" stops being actionable. */
    val HORIZON: Duration = Duration.ofHours(24)

    fun scan(forecast: WeatherForecast, air: AirQuality?, now: Instant): List<Hazard> {
        val hours = forecast.hourly
            .filter { !it.timestamp.isBefore(now) && it.timestamp.isBefore(now.plus(HORIZON)) }
            .sortedBy { it.timestamp }

        val found = HazardKind.entries.mapNotNull { kind ->
            runsOf(hours, { severityOf(kind, it) }, { readingOf(kind, it) })
                .maxByOrNull { it.severity }
                ?.let { Hazard(kind, it.severity, it.from, it.until, it.peak) }
        }.toMutableList()

        // Air quality is not in the hourly series: it comes from a different
        // service on its own cadence and describes now rather than a window.
        val airSeverity = air?.band?.let { band ->
            when {
                band >= AirQualityBand.VERY_POOR -> HazardSeverity.DANGER
                band >= AirQualityBand.POOR -> HazardSeverity.WARNING
                else -> null
            }
        }
        if (airSeverity != null) {
            // Stamped with when the air was measured, not with when this ran.
            // Stamping it "now" made it start a few seconds after the clock the
            // screen draws with, so the one hazard that is definitely happening
            // announced itself as starting shortly - the scan runs on a fresher
            // instant than the frame does, and always will.
            val measured = minOf(air.observedAt, now)
            found += Hazard(HazardKind.UNBREATHABLE_AIR, airSeverity, measured, null)
        }

        // Worst first, and among equals whatever is already happening.
        return found.sortedWith(
            compareByDescending<Hazard> { it.severity }
                .thenByDescending { it.hasBegunBy(now) }
                .thenBy { it.from },
        )
    }

    /** The severity one hour reaches for one kind of hazard, or null for none. */
    fun severityOf(kind: HazardKind, hour: HourlyWeather): HazardSeverity? = when (kind) {
        HazardKind.EXTREME_HEAT -> byThreshold(
            hour.apparentTemperature ?: hour.temperature,
            HEAT_WARNING_C,
            HEAT_DANGER_C,
        )

        // Negated so one comparison serves both ends of the thermometer.
        HazardKind.EXTREME_COLD -> byThreshold(
            (hour.apparentTemperature ?: hour.temperature)?.let { -it },
            -COLD_WARNING_C,
            -COLD_DANGER_C,
        )

        HazardKind.DAMAGING_WIND -> byThreshold(
            hour.windGust ?: hour.windSpeed,
            GALE_MS,
            STORM_MS,
        )

        HazardKind.TORRENTIAL_RAIN -> if (hour.kind == PrecipitationKind.SNOW) {
            null
        } else {
            byThreshold(hour.precipitation, TORRENT_WARNING_MM, TORRENT_DANGER_MM)
        }

        HazardKind.HEAVY_SNOW -> if (hour.kind != PrecipitationKind.SNOW) {
            null
        } else {
            byThreshold(hour.precipitation, SNOW_WARNING_MM, SNOW_DANGER_MM)
        }

        // No amount qualifies it. A road glazed by a tenth of a millimetre is as
        // dangerous as one glazed by five, and more surprising.
        HazardKind.ICE -> when (hour.condition) {
            WeatherCondition.FREEZING_RAIN -> HazardSeverity.DANGER
            WeatherCondition.FREEZING_DRIZZLE -> HazardSeverity.WARNING
            else -> null
        }

        HazardKind.THUNDERSTORM -> when (hour.condition) {
            WeatherCondition.THUNDERSTORM_WITH_HAIL -> HazardSeverity.DANGER
            WeatherCondition.THUNDERSTORM -> HazardSeverity.WARNING
            else -> null
        }

        HazardKind.EXTREME_UV -> byThreshold(hour.uvIndex, UV_WARNING, UV_DANGER)

        // Answered from the air quality service, not from an hour.
        HazardKind.UNBREATHABLE_AIR -> null
    }

    /**
     * The number a hazard is judged on, so the strongest one in a run can be
     * carried out with it. The same value the severity is taken from, which is
     * the point - a peak that disagreed with the severity beside it would be
     * two answers to one question.
     */
    fun readingOf(kind: HazardKind, hour: HourlyWeather): Double? = when (kind) {
        HazardKind.EXTREME_HEAT -> hour.apparentTemperature ?: hour.temperature
        HazardKind.EXTREME_COLD -> (hour.apparentTemperature ?: hour.temperature)?.let { -it }
        HazardKind.DAMAGING_WIND -> hour.windGust ?: hour.windSpeed
        HazardKind.TORRENTIAL_RAIN, HazardKind.HEAVY_SNOW -> hour.precipitation
        HazardKind.EXTREME_UV -> hour.uvIndex
        HazardKind.ICE, HazardKind.THUNDERSTORM, HazardKind.UNBREATHABLE_AIR -> null
    }

    private fun byThreshold(value: Double?, warning: Double, danger: Double): HazardSeverity? =
        when {
            value == null -> null
            value >= danger -> HazardSeverity.DANGER
            value >= warning -> HazardSeverity.WARNING
            else -> null
        }

    private data class Run(
        val severity: HazardSeverity,
        val from: Instant,
        val until: Instant?,
        val peak: Double?,
    )

    /**
     * The unbroken stretches where a hazard holds.
     *
     * A run reaching the last hour held is left open-ended rather than closed at
     * the edge of the forecast. A storm does not stop because the data does, and
     * saying it ends at nine tomorrow when nine tomorrow is merely where we
     * stopped looking would be a claim nobody made.
     */
    private fun runsOf(
        hours: List<HourlyWeather>,
        severity: (HourlyWeather) -> HazardSeverity?,
        reading: (HourlyWeather) -> Double?,
    ): List<Run> {
        val runs = mutableListOf<Run>()
        var start: Instant? = null
        var worst: HazardSeverity? = null
        var peak: Double? = null

        hours.forEachIndexed { index, hour ->
            val here = severity(hour)
            if (here != null) {
                if (start == null) start = hour.timestamp
                worst = maxOf(worst ?: here, here)
                reading(hour)?.let { peak = maxOf(peak ?: it, it) }
                if (index == hours.lastIndex) runs += Run(worst, start, null, peak)
            } else {
                val began = start
                if (began != null) {
                    runs += Run(
                        worst ?: HazardSeverity.WARNING,
                        began,
                        hour.timestamp,
                        peak,
                    )
                    start = null
                    worst = null
                    peak = null
                }
            }
        }
        return runs
    }

    /**
     * Apparent temperature at which heat stops being uncomfortable and becomes a
     * health event. Near the foot of every national heat scale that exists,
     * which is the most agreement available.
     */
    const val HEAT_WARNING_C = 32.0

    /** Where heat stroke becomes likely rather than possible under exertion. */
    const val HEAT_DANGER_C = 40.0

    /** Apparent temperature at which exposed skin is at risk inside an hour. */
    const val COLD_WARNING_C = -25.0

    /** Frostbite in minutes rather than in an hour. */
    const val COLD_DANGER_C = -40.0

    /** Beaufort 8: twigs break off trees and walking is difficult. */
    const val GALE_MS = 17.2

    /** Beaufort 10: trees uprooted, structural damage. */
    const val STORM_MS = 24.5

    /**
     * Beaufort 12, the top of the scale: hurricane force.
     *
     * Not a separate severity - there is nothing above "change the plan" to
     * escalate to - but it changes what the warning is called. Forty metres per
     * second described as "damaging wind" understates it to the point of being
     * misleading, and the word is the part somebody acts on.
     *
     * Naming it is all this can do. Whether a hurricane is *named*, where its
     * eye is and when it makes landfall come from cyclone track feeds that are
     * all regional - the NHC for the Atlantic, the JTWC elsewhere - and this app
     * takes global sources only. What it has is the forecast gust at your
     * coordinates, which a global model at 11 km will under-resolve near an
     * eyewall. It will say the wind is extreme. It will not say its name.
     */
    const val HURRICANE_MS = 32.7

    /** mm in the hour: standing water, and drains beginning to lose. */
    const val TORRENT_WARNING_MM = 20.0

    /** The top of the meteorological scale for rainfall rate. */
    const val TORRENT_DANGER_MM = PrecipitationIntensity.VIOLENT_MM_PER_HOUR

    /** Liquid equivalent, so roughly four centimetres of snow in the hour. */
    const val SNOW_WARNING_MM = 4.0

    /** Roughly eight centimetres in the hour, which is where transport stops. */
    const val SNOW_DANGER_MM = 8.0

    /** The WHO's "very high" band. */
    const val UV_WARNING = 8.0

    /** The WHO's "extreme" band. */
    const val UV_DANGER = 11.0
}
