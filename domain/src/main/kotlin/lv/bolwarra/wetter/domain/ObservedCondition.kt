package lv.bolwarra.wetter.domain

import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherCondition

/**
 * The condition, corrected by what was actually seen.
 *
 * The dial names the weather in one word, and until now that word came from the
 * provider's symbol for the hour - a summary computed before breakfast for a
 * whole sixty minutes. Everything under it on the same screen had already moved
 * to the radar: the chart, the rate beside the label, the bar that says when it
 * stops. So the most prominent word on the page was the least evidenced thing
 * on it, and it could plainly contradict the chart directly beneath it.
 *
 * Most evidence, least speculation (docs/design-principles.md). Where the two
 * disagree about *now*, the one that looked wins.
 *
 * ### The hard half
 *
 * Upgrading is easy: radar sees rain the model missed, so the screen says rain.
 *
 * Downgrading is where the rule costs something. Radar can say with confidence
 * that nothing is falling here, but it cannot see the sky - it detects
 * hydrometeors, not cloud - so it has no word to offer in place of the one it
 * just removed. Leaving "Rain" up would contradict an observation; inventing
 * "Clear" would invent an observation. So the precipitation is dropped and the
 * sky is named from the model's own cloud cover, which is the model being used
 * for the thing it does know.
 *
 * ### What is deliberately not done
 *
 * Nothing here touches thunder, fog or freezing rain. Radar sees none of them:
 * a thunderstorm is a lightning detection network, fog sits below the beam
 * entirely, and whether rain freezes on contact is a question about the ground,
 * not about the drop. On those the provider's symbol is the only evidence there
 * is, so it stands.
 */
object ObservedCondition {

    /**
     * @param reported what the provider said for this hour.
     * @param observedRate the fused rate at this moment, mm/h, or null where
     *   there is no radar at all - in which case the provider's word stands,
     *   because an absent observation is not a contradicting one.
     * @param cloudCover percent, for naming the sky when precipitation is
     *   removed.
     */
    fun of(
        reported: WeatherCondition,
        observedRate: Double?,
        temperature: Double?,
        cloudCover: Int?,
    ): WeatherCondition {
        if (observedRate == null) return reported
        if (reported in RADAR_CANNOT_SEE) return reported

        val falling = observedRate >= PrecipitationIntensity.TRACE_MM_PER_HOUR
        return when {
            falling && !reported.isPrecipitating -> observedPrecipitation(observedRate, temperature)
            !falling && reported.isPrecipitating -> skyOf(cloudCover)
            else -> reported
        }
    }

    /**
     * What to call precipitation the provider did not report.
     *
     * Radar returns echoes and cannot say whether they are frozen, so the kind
     * comes from the temperature - the same rule the rest of the app uses - and
     * the intensity comes from the measured rate.
     */
    private fun observedPrecipitation(rate: Double, temperature: Double?): WeatherCondition =
        when (PrecipitationKind.likelyAt(temperature)) {
            PrecipitationKind.SNOW ->
                if (PrecipitationIntensity.ofRate(rate) >= PrecipitationIntensity.MODERATE) {
                    WeatherCondition.SNOW
                } else {
                    WeatherCondition.SNOW_GRAINS
                }

            PrecipitationKind.MIXED -> WeatherCondition.SLEET

            else -> if (PrecipitationIntensity.ofRate(rate) >= PrecipitationIntensity.LIGHT) {
                WeatherCondition.RAIN
            } else {
                WeatherCondition.DRIZZLE
            }
        }

    /**
     * The sky, when the rain has been taken out of the word.
     *
     * From the model's cloud cover, which is a thing the model measures rather
     * than a thing being guessed at. Without it the honest answer is UNKNOWN -
     * a dash is better than a sky nobody looked at.
     */
    private fun skyOf(cloudCover: Int?): WeatherCondition = when {
        cloudCover == null -> WeatherCondition.UNKNOWN
        cloudCover >= OVERCAST_FROM -> WeatherCondition.OVERCAST
        cloudCover >= PARTLY_FROM -> WeatherCondition.PARTLY_CLOUDY
        cloudCover >= MAINLY_CLEAR_FROM -> WeatherCondition.MAINLY_CLEAR
        else -> WeatherCondition.CLEAR
    }

    /**
     * Conditions radar has nothing to say about, which therefore keep the
     * provider's word whatever the rate does.
     *
     * Thunder is a lightning network, not a reflectivity field. Fog sits under
     * the beam. Whether rain freezes on contact is a fact about the ground.
     */
    private val RADAR_CANNOT_SEE = setOf(
        WeatherCondition.THUNDERSTORM,
        WeatherCondition.THUNDERSTORM_WITH_HAIL,
        WeatherCondition.FOG,
        WeatherCondition.FREEZING_RAIN,
        WeatherCondition.FREEZING_DRIZZLE,
    )

    /** The octas the WMO calls overcast, as a percentage. */
    private const val OVERCAST_FROM = 85

    private const val PARTLY_FROM = 40

    private const val MAINLY_CLEAR_FROM = 15
}
