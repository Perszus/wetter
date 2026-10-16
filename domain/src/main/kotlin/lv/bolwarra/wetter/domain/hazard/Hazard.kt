// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.hazard

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.air.AirQualityBand
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.climate.DayNormal
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
 * Two levels: one to take into account, one to change the plan for. National
 * services' four or five levels mean different things in each country.
 */
enum class HazardSeverity { WARNING, DANGER }

/**
 * @param until the end of the run of hours it holds for, or null when it is
 * still going at the edge of the forecast, shown as an open end.
 */
data class Hazard(
    val kind: HazardKind,
    val severity: HazardSeverity,
    val from: Instant,
    val until: Instant?,
    /**
     * The strongest reading during the run, in the hazard's own unit (m/s, °C, mm),
     * so the label can tell a gale from a hurricane within one severity.
     */
    val peak: Double? = null,
) {
    /** Already happening, as opposed to on its way. */
    fun hasBegunBy(now: Instant): Boolean = !from.isAfter(now)
}

/**
 * Reads the forecast for the things worth interrupting somebody about.
 *
 * The absolute thresholds are physiological or physical: Beaufort for wind, the
 * NWS heat index and wind chill bands, the WHO's bands for UV and air. They do
 * not change at a border.
 *
 * The warning levels for heat, cold and wind also listen to the place, as
 * Meteoalarm and the US National Weather Service both do: where a decade of
 * archive is available, a warning sits near the worst comparable day in twenty
 * and danger near the worst in ten years, bounded by the published numbers so a
 * place can move the bar but not off the scale. That keeps a hot city from being
 * warned every night and lets a mild one hear about a cold night that is serious
 * for it.
 *
 * - Heat may not reach danger below the published Extreme Caution line.
 * - Cold may reach danger at a place's own extreme, down to freezing, because
 * cold harms through housing, clothing and roads built to local norms.
 * - Wind may only become stricter: a gale is a gale everywhere.
 * - Rain, snow and ice keep their absolute rates, which overwhelm drains and
 * close roads anywhere; thunder stays absolute because the archive cannot
 * count storm hours.
 *
 * With no archive, everything uses the absolute thresholds.
 */
object Hazards {

    /** How far ahead to look: beyond a day a warning stops being actionable. */
    val HORIZON: Duration = Duration.ofHours(24)

    fun scan(
        forecast: WeatherForecast,
        air: AirQuality?,
        now: Instant,
        climate: Climatology? = null,
    ): List<Hazard> {
        val hours = forecast.hourly
            .filter { !it.timestamp.isBefore(now) && it.timestamp.isBefore(now.plus(HORIZON)) }
            .sortedBy { it.timestamp }

        // Looked up per hour, because the horizon crosses midnight and the normal changes.
        val zone = forecast.location.zone
        val normalFor: (HourlyWeather) -> DayNormal? = { hour ->
            climate?.at(hour.timestamp.atZone(zone).toLocalDate())
        }

        val found = HazardKind.entries.mapNotNull { kind ->
            runsOf(hours, { severityOf(kind, it, normalFor(it)) }, { readingOf(kind, it) })
                .bestOf(now)
                ?.let { Hazard(kind, it.severity, it.from, it.until, it.peak) }
        }.toMutableList()

        // Air quality comes from its own service and describes now, not an hour.
        val airSeverity = air?.band?.let { band ->
            when {
                band >= AirQualityBand.VERY_POOR -> HazardSeverity.DANGER
                band >= AirQualityBand.POOR -> HazardSeverity.WARNING
                else -> null
            }
        }
        if (airSeverity != null) {
            // Stamped with when the air was measured, so it reads as already happening.
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

    /**
     * The severity one hour reaches for one kind of hazard, or null for none.
     *
     * @param normal what this date usually does here, from a decade of archive;
     * null falls back to the absolute thresholds.
     */
    fun severityOf(
        kind: HazardKind,
        hour: HourlyWeather,
        normal: DayNormal? = null,
    ): HazardSeverity? = when (kind) {
        HazardKind.EXTREME_HEAT -> byLocalThreshold(
            value = (hour.apparentTemperature ?: hour.temperature).asTemperature(),
            localWarning = normal?.warmTail,
            localDanger = normal?.warmExtreme,
            absoluteWarning = HEAT_WARNING_C,
            absoluteDanger = HEAT_DANGER_C,
            leastWarning = HEAT_FLOOR_C,
            leastDanger = HEAT_WARNING_C,
        )

        // Negated, with the local tails, so one larger-is-worse comparison serves both ends.
        HazardKind.EXTREME_COLD -> byLocalThreshold(
            value = (hour.apparentTemperature ?: hour.temperature).asTemperature()?.let { -it },
            localWarning = normal?.coldTail?.let { -it },
            localDanger = normal?.coldExtreme?.let { -it },
            absoluteWarning = -COLD_WARNING_C,
            absoluteDanger = -COLD_DANGER_C,
            leastWarning = -COLD_CEILING_C,
            // Cold may reach danger at the place's own extreme, down to freezing (see the
            // note on [Hazards]).
            leastDanger = -COLD_CEILING_C,
        )

        // Wind's local bar may only rise (see the note on [Hazards]).
        HazardKind.DAMAGING_WIND -> byLocalThreshold(
            value = (hour.windGust ?: hour.windSpeed).asWind(),
            localWarning = normal?.gustTail,
            localDanger = normal?.gustExtreme,
            absoluteWarning = GALE_MS,
            absoluteDanger = STORM_MS,
            leastWarning = GALE_MS,
            leastDanger = STORM_MS,
        )

        HazardKind.TORRENTIAL_RAIN -> if (hour.kind == PrecipitationKind.SNOW) {
            null
        } else {
            byThreshold(hour.precipitation.asRate(), TORRENT_WARNING_MM, TORRENT_DANGER_MM)
        }

        HazardKind.HEAVY_SNOW -> if (hour.kind != PrecipitationKind.SNOW) {
            null
        } else {
            byThreshold(hour.precipitation.asRate(), SNOW_WARNING_MM, SNOW_DANGER_MM)
        }

        // Any amount: a thin glaze is as dangerous as a thick one.
        HazardKind.ICE -> when (hour.condition) {
            WeatherCondition.FREEZING_RAIN -> HazardSeverity.DANGER
            WeatherCondition.FREEZING_DRIZZLE -> HazardSeverity.WARNING
            else -> null
        }

        // Absolute: this should be relative to how often it thunders here, but the
        // archive's daily weather code cannot count storm hours, and the hourly codes
        // would multiply the archive fetch for one value.
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
     * The number a hazard is judged on, so the run's peak is the same value its
     * severity was taken from.
     */
    fun readingOf(kind: HazardKind, hour: HourlyWeather): Double? = when (kind) {
        HazardKind.EXTREME_HEAT -> (hour.apparentTemperature ?: hour.temperature).asTemperature()
        HazardKind.EXTREME_COLD ->
            (hour.apparentTemperature ?: hour.temperature).asTemperature()?.let { -it }

        HazardKind.DAMAGING_WIND -> (hour.windGust ?: hour.windSpeed).asWind()
        HazardKind.TORRENTIAL_RAIN, HazardKind.HEAVY_SNOW -> hour.precipitation.asRate()
        HazardKind.EXTREME_UV -> hour.uvIndex
        HazardKind.ICE, HazardKind.THUNDERSTORM, HazardKind.UNBREATHABLE_AIR -> null
    }

    /**
     * A threshold the place gets a vote in, oriented so larger is worse (cold
     * arrives negated).
     *
     * 1. Absolute danger always wins.
     * 2. With no local knowledge, the absolute thresholds.
     * 3. Otherwise the place's own tails, held at [leastWarning] and [leastDanger] so
     * a mild place cannot warn about a pleasant day or call one dangerous.
     *
     * @param leastWarning the loosest a warning may be for this kind. Passing the
     * absolute warning makes the local bar only able to tighten, as for wind.
     */
    private fun byLocalThreshold(
        value: Double?,
        localWarning: Double?,
        localDanger: Double?,
        absoluteWarning: Double,
        absoluteDanger: Double,
        leastWarning: Double,
        leastDanger: Double,
    ): HazardSeverity? {
        if (value == null) return null
        if (value >= absoluteDanger) return HazardSeverity.DANGER
        if (localWarning == null) return byThreshold(value, absoluteWarning, absoluteDanger)

        if (value >= maxOf(localDanger ?: absoluteDanger, leastDanger)) return HazardSeverity.DANGER
        return if (value >= maxOf(localWarning, leastWarning)) HazardSeverity.WARNING else null
    }

    /**
     * A number that could have been measured, or nothing. Sentinels such as -9999
     * survive JSON as ordinary numbers and would otherwise raise a danger, so a
     * reading outside anything the planet has done is treated as missing.
     */
    private fun Double?.asTemperature(): Double? = plausible(COLDEST_EVER_C, HOTTEST_EVER_C)

    private fun Double?.asWind(): Double? = plausible(0.0, FASTEST_GUST_EVER_MS)

    private fun Double?.asRate(): Double? = plausible(0.0, HEAVIEST_RAIN_EVER_MM_PER_HOUR)

    private fun Double?.plausible(lowest: Double, highest: Double): Double? {
        val value = this ?: return null
        if (!value.isFinite()) return null
        return value.takeIf { it in lowest..highest }
    }

    /** Vostok 1983, with room beneath it for apparent temperature in wind. */
    const val COLDEST_EVER_C = -120.0

    /** Furnace Creek 1913, with room above it for heat index. */
    const val HOTTEST_EVER_C = 70.0

    /** Barrow Island 1996, 113 m/s, rounded up. */
    const val FASTEST_GUST_EVER_MS = 130.0

    /** Unionville 1956 held 305 mm in an hour. */
    const val HEAVIEST_RAIN_EVER_MM_PER_HOUR = 400.0

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
     * The stretch of a kind worth naming when there are several: worst first, then
     * already underway, then longest from now, then furthest.
     */
    private fun List<Run>.bestOf(now: Instant): Run? = maxWithOrNull(
        compareBy<Run> { it.severity }
            .thenBy { !it.from.isAfter(now) }
            .thenBy { it.lengthFrom(now) }
            .thenBy { it.peak ?: Double.NEGATIVE_INFINITY },
    )

    /**
     * How long a stretch runs from now, so time already spent does not count. An
     * open end counts as running to the horizon.
     */
    private fun Run.lengthFrom(now: Instant): Duration {
        val begins = maxOf(from, now)
        val ends = until ?: now.plus(HORIZON)
        return Duration.between(begins, ends).coerceAtLeast(Duration.ZERO)
    }

    /**
     * The unbroken stretches where a hazard holds. A run reaching the last hour is
     * left open-ended: the data stopping is not the weather stopping.
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
     * The NWS heat index bands, in apparent temperature.
     *
     * 80 °F, where fatigue is possible with prolonged exposure: the lowest a local
     * threshold may go.
     */
    const val HEAT_FLOOR_C = 26.7

    /** 90 °F, the start of Extreme Caution: heat exhaustion possible with exposure. */
    const val HEAT_WARNING_C = 32.2

    /** 105 °F, the start of Danger: heat exhaustion likely, heatstroke possible. */
    const val HEAT_DANGER_C = 40.6

    /**
     * Freezing, above which cold is not a hazard however unusual: the highest a
     * local threshold may go.
     */
    const val COLD_CEILING_C = 0.0

    /**
     * Apparent temperature at which exposed skin is at risk within the hour (the NWS
     * wind chill chart). The floor under the danger level: a place may warn far
     * milder, but nowhere is told it is in danger at a temperature that is merely
     * cold.
     */
    const val COLD_WARNING_C = -25.0

    /** -30 °F, where the same chart puts frostbite at ten minutes. */
    const val COLD_DANGER_C = -34.4

    /**
     * Beaufort 8: twigs break and walking is difficult. The NWS Wind Advisory gust,
     * 40 mph (17.9 m/s), agrees. A local threshold may sit above this, never below.
     */
    const val GALE_MS = 17.2

    /**
     * Beaufort 10: trees uprooted and structural damage. The NWS High Wind Warning
     * gust, 58 mph (25.9 m/s), agrees.
     */
    const val STORM_MS = 24.5

    /**
     * Beaufort 12: hurricane force. Not another severity, but it changes what the
     * warning is called. Cyclone names and tracks come from regional feeds, which
     * this app does not use.
     */
    const val HURRICANE_MS = 32.7

    /** Millimetres in the hour: standing water, drains beginning to lose. */
    const val TORRENT_WARNING_MM = 20.0

    /** The top of the meteorological scale for rainfall rate. */
    const val TORRENT_DANGER_MM = PrecipitationIntensity.VIOLENT_MM_PER_HOUR

    /** Liquid equivalent: roughly four centimetres of snow in the hour. */
    const val SNOW_WARNING_MM = 4.0

    /** Roughly eight centimetres in the hour, where transport stops. */
    const val SNOW_DANGER_MM = 8.0

    /** The WHO's "very high" band. */
    const val UV_WARNING = 8.0

    /** The WHO's "extreme" band. */
    const val UV_DANGER = 11.0
}
