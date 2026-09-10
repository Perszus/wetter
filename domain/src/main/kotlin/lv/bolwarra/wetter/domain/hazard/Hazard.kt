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
 *
 * ### And then the place gets a vote, the way the warning services do it
 *
 * The paragraph above states the problem and then hands every place on earth the
 * same number anyway, which does not work in either direction.
 *
 * The two big public warning systems both solved this the same way, and neither
 * of them solved it with a global constant:
 *
 * - **Meteoalarm**, the European system, publishes one colour scale - yellow,
 *   orange, red - and leaves the numbers behind it to each national service.
 *   Its own documentation says the thresholds "differ from country to country or
 *   sometimes even from region to region".
 * - The **US National Weather Service** rewrote its cold products in October
 *   2024 and says outright that the criteria "are based on local climatology and
 *   what temperatures actually impact each area". Its regional offices publish
 *   the numbers: a Cold Weather Advisory on the Mississippi coast is -3.9 C,
 *   and an Extreme Cold Warning there is -9.4 C.
 *
 * Minus four is a mild winter afternoon in Rīga and an advisory in Mississippi,
 * and both are correct. So the absolute numbers below are the published
 * *physiological* bands - where a body is actually at risk, which does not move
 * when you cross a border - and the *warning* level for heat, cold and wind
 * comes from what this place does.
 *
 * It is too low where the weather is routinely hard. Measured on a real Kolkata
 * forecast: twenty-seven degrees of air at high humidity is thirty-three of heat
 * index, which cleared the heat threshold for most of every day. An amber mark
 * that is up permanently says nothing, and a phone that warns nightly gets
 * turned off before the storm that mattered.
 *
 * It is too high where the weather is usually mild, which is worse, because
 * there the app simply says nothing. Minus twenty-five is where exposed skin is
 * in danger inside an hour and it is also a temperature Rīga reaches about
 * never - so the cold warning, in a country that gets genuinely dangerous
 * winters, was dead code. Minus twenty there is already a serious night.
 *
 * So where there is a decade of archive for the place - the same decade the
 * Month page keeps - the thresholds for heat, cold and wind come from it:
 *
 * - **warning** at about the worst comparable day in twenty
 * - **danger** at about the worst in ten years
 *
 * bounded at both ends by published numbers, so the place can move the bar but
 * not off the scale. Nothing fires below the band where the thing can hurt
 * anybody at all - the heat index's Caution line, freezing - and the absolute
 * danger thresholds still fire wherever they are reached, because there is no
 * climate in which Beaufort 10 is fine.
 *
 * Wind is the one that can only be made *stricter*. A gale is a gale: the NWS
 * issues a Wind Advisory at gusts of 40 mph and a High Wind Warning at 58,
 * which is 17.9 and 25.9 m/s, and Beaufort has been saying 17.2 and 24.5 for
 * two centuries - two systems, one from the age of sail, agreeing to within a
 * few per cent and neither of them adjusting for where you are. What a windy
 * place does earn is a *higher* bar, so somewhere that gets Beaufort 8 every
 * fortnight is not told about it every fortnight.
 *
 * ### Heat and cold are not clamped the same way, and that is on purpose
 *
 * Heat may not reach *danger* below the published Extreme Caution line, however
 * unusual it is locally. A body is a body: the heat index bands are
 * physiological, thirty degrees of heat index does not hospitalise anybody
 * anywhere, and a coastal town having never seen it does not change that.
 *
 * Cold may, down to freezing. Cold does not harm people directly so much as
 * through clothing, housing, heating and roads - all of which are built to what
 * the place normally does. Minus twelve is a Tuesday in Rīga and a national
 * emergency in Lisbon, and the difference is entirely in what was built there.
 *
 * The cost of getting this wrong was measured: clamping the cold danger to the
 * absolute warning gave most of Europe a level it can never reach. Reykjavík
 * warned at -17.6 and could not be in danger until -25, a temperature it has
 * essentially never seen; Ushuaia warned at -12 with the same unreachable
 * danger. Now each of them reaches danger at its own hardest day in a decade.
 *
 * Rain and snow keep their absolute numbers, because those thresholds are
 * *rates*: twenty millimetres in an hour overwhelms drainage anywhere on earth,
 * and four centimetres of snow in an hour closes a road in Sapporo the same as
 * in Kyiv. A wet climate has more such hours; it does not have them daily. Ice
 * keeps its too - freezing rain is rare and dangerous everywhere. Thunder keeps
 * its for a worse reason: it should be relative and the archive cannot measure
 * it. See the note beside it.
 *
 * With no archive, everything falls back to the absolute thresholds, which is
 * what a new place gets for its first day.
 */
object Hazards {

    /** How far ahead to look. Beyond a day, "a storm is coming" stops being actionable. */
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

        // Looked up per hour rather than once, because the horizon crosses
        // midnight and the night either side of it is not the same normal - most
        // visibly at the turn of a season, which is exactly when an unusual day
        // is most likely.
        val zone = forecast.location.zone
        val normalFor: (HourlyWeather) -> DayNormal? = { hour ->
            climate?.at(hour.timestamp.atZone(zone).toLocalDate())
        }

        val found = HazardKind.entries.mapNotNull { kind ->
            runsOf(hours, { severityOf(kind, it, normalFor(it)) }, { readingOf(kind, it) })
                .bestOf(now)
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

    /**
     * The severity one hour reaches for one kind of hazard, or null for none.
     *
     * @param normal what this date usually does here, when there is a decade of
     *   archive to say so. Null falls back to the absolute thresholds.
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

        // Negated so one comparison serves both ends of the thermometer - the
        // local tails with it, so they stay in the same frame as the reading.
        HazardKind.EXTREME_COLD -> byLocalThreshold(
            value = (hour.apparentTemperature ?: hour.temperature).asTemperature()?.let { -it },
            localWarning = normal?.coldTail?.let { -it },
            localDanger = normal?.coldExtreme?.let { -it },
            absoluteWarning = -COLD_WARNING_C,
            absoluteDanger = -COLD_DANGER_C,
            leastWarning = -COLD_CEILING_C,
            // Freezing, the same floor the warning gets, and deliberately not
            // the absolute warning the way heat has it. See the note on the
            // object: cold harms through clothing, housing and infrastructure,
            // all of which are built to local norms, so a place may reach
            // danger at its own temperature. Clamping this to -25 gave most of
            // Europe a danger level it will never see - Reykjavík warns at
            // -17.6 and could not be in danger until -25, which it has
            // essentially never been.
            leastDanger = -COLD_CEILING_C,
        )

        // The only one whose local bar may only go up. See the note on the
        // object: two independent systems put a damaging gust in the same place
        // and neither adjusts for where you are.
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

        // No amount qualifies it. A road glazed by a tenth of a millimetre is as
        // dangerous as one glazed by five, and more surprising.
        HazardKind.ICE -> when (hour.condition) {
            WeatherCondition.FREEZING_RAIN -> HazardSeverity.DANGER
            WeatherCondition.FREEZING_DRIZZLE -> HazardSeverity.WARNING
            else -> null
        }

        // Absolute, and knowingly so. A storm is an event in most of the world
        // and the season in a monsoon, so this ought to be relative like the
        // temperature and the wind - but the archive cannot say how often it
        // thunders here. Its daily weather code is one representative code for
        // the whole day, and a few storm hours always lose to the prevailing
        // rain: measured across eight cities including Kolkata in July and Riga
        // in July, that field says a thunderstorm share of zero everywhere.
        // Counting real storm hours needs the hourly codes, which is about four
        // times the payload of the whole archive fetch for one boolean. Left as
        // it is, and written down in notes.md, rather than shipping a check that
        // silently never fires.
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
        HazardKind.EXTREME_HEAT -> (hour.apparentTemperature ?: hour.temperature).asTemperature()
        HazardKind.EXTREME_COLD ->
            (hour.apparentTemperature ?: hour.temperature).asTemperature()?.let { -it }

        HazardKind.DAMAGING_WIND -> (hour.windGust ?: hour.windSpeed).asWind()
        HazardKind.TORRENTIAL_RAIN, HazardKind.HEAVY_SNOW -> hour.precipitation.asRate()
        HazardKind.EXTREME_UV -> hour.uvIndex
        HazardKind.ICE, HazardKind.THUNDERSTORM, HazardKind.UNBREATHABLE_AIR -> null
    }

    /**
     * A threshold the place gets a vote in.
     *
     * Everything is oriented so that larger is worse, the cold included - it
     * arrives negated - so this reads in one direction instead of being two
     * mirrored sets of comparisons to keep in step.
     *
     * The order is the argument:
     *
     * 1. Absolute danger always wins. There is no climate in which Beaufort 10
     *    is fine, and a place whose own worst day is worse than that still has a
     *    dangerous day when it arrives.
     * 2. With no local knowledge, the absolute thresholds, exactly as before.
     * 3. Otherwise the place's own tails, each held at whatever floor its kind
     *    was given - [leastWarning] so a mild place cannot warn about a pleasant
     *    day it merely has not had before, [leastDanger] so it cannot call one
     *    dangerous.
     *
     * Note what step 3 does *not* do: it does not fall back to the absolute
     * warning when the local tails sit above it. That is the point. Thirty-three
     * degrees of heat index clears the global bar and is an ordinary September
     * night in Kolkata, and calling that a hazard is how an amber mark stops
     * meaning anything.
     *
     * @param leastWarning the loosest a warning may be for this kind. Passing
     *   the absolute warning here makes the local bar able only to tighten,
     *   which is what the wind does.
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
     * A number that could have been measured, or nothing.
     *
     * Weather data is full of sentinels. `-9999` and `999` mean "missing" across
     * a good deal of meteorology, they survive JSON perfectly well because they
     * are ordinary numbers, and arithmetic on a bad field produces NaN and
     * infinity without anything failing. Any of them reaching a threshold
     * comparison raises a *danger* - the highest thing this app can say - and
     * pushes a notification about it.
     *
     * So a reading outside what the planet has ever done is treated as absent
     * rather than as extreme. The bounds are the records with room to spare, and
     * they are deliberately wide: the job here is to reject -9999, not to
     * second-guess a forecast.
     *
     * This is the app's own rule applied at the point a number becomes a claim -
     * a wrong number is worse than a blank.
     */
    private fun Double?.asTemperature(): Double? = plausible(COLDEST_EVER_C, HOTTEST_EVER_C)

    private fun Double?.asWind(): Double? = plausible(0.0, FASTEST_GUST_EVER_MS)

    private fun Double?.asRate(): Double? = plausible(0.0, HEAVIEST_RAIN_EVER_MM_PER_HOUR)

    private fun Double?.plausible(lowest: Double, highest: Double): Double? {
        val value = this ?: return null
        if (!value.isFinite()) return null
        return value.takeIf { it in lowest..highest }
    }

    /**
     * Vostok 1983, with room beneath it. Apparent temperature can sit below the
     * air temperature by twenty degrees or more in wind, hence the margin.
     */
    const val COLDEST_EVER_C = -120.0

    /** Furnace Creek 1913, with the same kind of margin above for heat index. */
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
     * Which stretch of a kind is *the* one, when there are several.
     *
     * This used to be `maxByOrNull { it.severity }`, which quietly meant "the
     * earliest, among equals" - Kotlin returns the first maximum. Measured on a
     * real forecast for Kolkata, apparent temperature crossed the heat
     * threshold in three separate stretches: a two-hour tail of that evening
     * peaking at 33, four hours the next morning peaking at 36.2, and nine
     * hours the next afternoon peaking at 35.4. All three were warnings, so it
     * took the two-hour tail - the shortest, weakest and most nearly finished
     * of them - and never mentioned the nine-hour one.
     *
     * That is tolerable on a dial and wrong in a warning, which exists to say
     * what the day is going to be like.
     *
     * So: worst first, then whatever is already happening, then whatever lasts
     * longest, then whatever goes furthest. Something underway keeps its place
     * at the front because the mark on the dial has to agree with what is out
     * of the window; among stretches that have not started, the one that runs
     * for nine hours is the one worth naming.
     */
    private fun List<Run>.bestOf(now: Instant): Run? = maxWithOrNull(
        compareBy<Run> { it.severity }
            .thenBy { !it.from.isAfter(now) }
            .thenBy { it.lengthFrom(now) }
            .thenBy { it.peak ?: Double.NEGATIVE_INFINITY },
    )

    /**
     * How long a stretch runs for, from now rather than from its start, so a
     * stretch that is mostly behind us does not outrank one still to come on
     * the strength of the part already spent.
     *
     * An open end - a stretch still going where the forecast stops - counts as
     * running to the horizon, because that is the least it can be.
     */
    private fun Run.lengthFrom(now: Instant): Duration {
        val begins = maxOf(from, now)
        val ends = until ?: now.plus(HORIZON)
        return Duration.between(begins, ends).coerceAtLeast(Duration.ZERO)
    }

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
     * The NWS heat index bands, which are the published ones and are in apparent
     * temperature because that is what the chart is.
     *
     * 80 F, where "fatigue is possible with prolonged exposure". Below it,
     * standing outside is not the weather's fault, however unusual the day is
     * for the place - so this is the lowest a local threshold may go.
     */
    const val HEAT_FLOOR_C = 26.7

    /**
     * 90 F, the foot of "extreme caution": sunstroke, cramps and heat exhaustion
     * become possible with prolonged exposure.
     */
    const val HEAT_WARNING_C = 32.2

    /**
     * 105 F, the foot of "danger": heat exhaustion becomes likely and heatstroke
     * possible.
     */
    const val HEAT_DANGER_C = 40.6

    /**
     * Freezing, above which cold is not a hazard however unusual it is here.
     *
     * Where the road ices and the pipe bursts, and the NWS's own point that skin
     * cannot freeze until the air is below this. It is the highest a local
     * threshold may go: a place whose coldest day in a decade is four degrees
     * has no cold hazard to warn about, whatever its climatology says.
     *
     * That still leaves a great deal of room - the mildest published criterion
     * found anywhere is a US Cold Weather Advisory at -3.9 C.
     */
    const val COLD_CEILING_C = 0.0

    /**
     * Apparent temperature at which exposed skin is at risk inside an hour.
     *
     * The NWS wind chill chart puts frostbite at thirty minutes around -28 C, so
     * this sits just inside that. It is a floor under the *danger* level rather
     * than a warning threshold in its own right now: a place may set its own
     * warning far milder - Rīga's own coldest-in-twenty January night is
     * -18.3 C - but nowhere gets told it is in danger from a temperature that is
     * merely cold.
     */
    const val COLD_WARNING_C = -25.0

    /**
     * -30 F, where the same chart puts frostbite at ten minutes rather than
     * thirty. Was -40, which is past the chart's five-minute line and further
     * than any national service waits.
     */
    const val COLD_DANGER_C = -34.4

    /**
     * Beaufort 8: twigs break off trees and walking is difficult.
     *
     * Corroborated by the NWS Wind Advisory criterion of gusts at 40 mph, which
     * is 17.9 m/s. A local threshold may sit above this and never below it.
     */
    const val GALE_MS = 17.2

    /**
     * Beaufort 10: trees uprooted, structural damage.
     *
     * Corroborated by the NWS High Wind Warning criterion of gusts at 58 mph,
     * which is 25.9 m/s.
     */
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
