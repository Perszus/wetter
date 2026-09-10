package lv.bolwarra.wetter.domain.forecast

import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * Whether today is a day to take something with you.
 *
 * Moderate, not merely wet. The mark used to appear for anything at all, which
 * meant it was up on most days in a maritime climate and stopped being read: a
 * warning that is always on is furniture. Moderate is where rain stops being
 * something you walk through and starts being something you take a coat for.
 *
 * ### The whole day, from midnight, and it does not go out again
 *
 * This used to run from *now* to the end of the day, on the reasoning that a
 * shower which finished this morning is not a reason to carry an umbrella this
 * afternoon. That is the right reasoning about a readout and this is not a
 * readout — it is a thing somebody decides once, on the way out of the door,
 * and cannot revisit at three in the afternoon from the other side of town.
 *
 * A property of the day is stable; a live answer is not. Somebody who looks at
 * seven, sees the mark and takes a coat is well served whatever the sky does
 * afterwards. Somebody who looks at eleven and finds it has quietly gone out has
 * been told the day changed its mind, which is not something the day did.
 *
 * So the question is asked about the calendar day rather than the hours left in
 * it. If any part of today reaches moderate, the mark is up from midnight to
 * midnight, and it moves only when the forecast itself moves.
 *
 * ### It is an offer, not an instruction
 *
 * Which is also why the bar is moderate rather than the higher one the rain
 * *claims* use. Saying "it is raining" when it is not is a wrong reading and
 * costs the app its credit. Suggesting an umbrella on a day that stays dry costs
 * somebody carrying one, and the reverse — leaving it at home before a storm —
 * costs a great deal more. The asymmetry is the whole argument, and it runs the
 * other way from every other threshold in this app.
 */
object UmbrellaDay {

    /**
     * Consecutive projected steps that must be moderate before the mark goes up.
     *
     * Two, not one. The threshold is a rate; the model applies it to a whole
     * hour and the radar projection applies it to ten minutes, so taking a
     * single radar step at face value raises the mark for a burst the model
     * would have averaged away — and puts it back to being up most days. Twenty
     * minutes of moderate rain is a shower either way.
     */
    const val SUSTAINED_STEPS = 2

    /** The bar. Where rain stops being something you would walk through. */
    val WORTH_CARRYING: PrecipitationIntensity = PrecipitationIntensity.MODERATE

    /**
     * @param timeline the fused projection. Radar counts and has to: the model
     *   publishes one figure for a whole hour, so a ten-minute downpour arrives
     *   as a mild average and never reaches moderate — which once left the curve
     *   drawn plainly above the moderate guide with no umbrella beside it, the
     *   chart and the mark disagreeing about the same rain.
     */
    fun isUmbrellaDay(
        forecast: WeatherForecast,
        timeline: List<FusedPrecipitation>,
        now: Instant,
    ): Boolean {
        val zone: ZoneId = forecast.location.zone
        val today = now.atZone(zone).toLocalDate()

        fun isToday(at: Instant) = at.atZone(zone).toLocalDate() == today

        val projected = timeline
            .filter { isToday(it.at) }
            .windowed(SUSTAINED_STEPS, partialWindows = false)
            .any { window ->
                window.all {
                    PrecipitationIntensity.ofRate(it.millimetresPerHour) >= WORTH_CARRYING
                }
            }

        return projected ||
            forecast.hourly.any { isToday(it.timestamp) && it.intensity >= WORTH_CARRYING }
    }
}
