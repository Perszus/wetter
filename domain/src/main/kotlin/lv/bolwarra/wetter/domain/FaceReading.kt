package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.glass.GlassMark
import lv.bolwarra.wetter.domain.hazard.Hazard
import lv.bolwarra.wetter.domain.hazard.HazardKind
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast

/**
 * What the dial says right now: the reading, the word under it, and the glass
 * picture. Shared by the app's dial and the widgets so they always agree.
 */
data class FaceReading(
    /** The forecast resolved against the clock, not taken from the fetch. */
    val current: CurrentWeather,
    /** The word, taken from whatever actually looked: the radar where it can see. */
    val seen: WeatherCondition,
    /** What the radar measures falling at this moment, or null where it has nothing. */
    val observedRate: Double?,
    /** The glass picture's name, or null for bare glass. */
    val glass: String?,
) {
    companion object {

        /**
         * @param timeline the fused precipitation series the chart is drawn
         *   from; empty when there is no radar, and the model speaks alone.
         * @param hazards whatever in the next day could hurt somebody - only
         *   the extremes in force at [now] reach the glass.
         * @param hazyAir notably poor air, which makes a heat hazard hazy.
         */
        fun of(
            forecast: WeatherForecast,
            timeline: List<FusedPrecipitation>,
            hazards: List<Hazard>,
            now: Instant,
            hazyAir: Boolean = false,
        ): FaceReading {
            val current = forecast.conditionsAt(now)
            val hour = forecast.hourly.at(now)
            val observedRate = radarRateAt(timeline, now)

            // Starts from the reconciled word so the radar correction matches the rest of the app.
            val seen = ObservedCondition.of(
                reported = current.appearance,
                observedRate = observedRate,
                temperature = current.temperature,
                cloudCover = hour?.cloudCover,
            )

            // From the same word and rate the face shows, so glass and word agree.
            val glass = GlassMark.of(
                GlassMark.Input(
                    raw = current.condition,
                    rate = observedRate ?: current.precipitation,
                    windSpeed = current.windSpeed,
                    temperature = current.temperature,
                    humidity = current.humidity,
                    isDay = current.isDay,
                    extremeCold = hazards.any {
                        it.kind == HazardKind.EXTREME_COLD &&
                            it.coversNow(now)
                    },
                    extremeHeat = hazards.any {
                        it.kind == HazardKind.EXTREME_HEAT &&
                            it.coversNow(now)
                    },
                    hazyAir = hazyAir,
                    shown = seen,
                ),
            )
            return FaceReading(current, seen, observedRate, glass)
        }

        /**
         * The radar's rate at [now], or null when it has no observation near
         * enough. Zero is an observation that nothing falls; null is no
         * observation, and only zero may contradict the provider.
         */
        fun radarRateAt(timeline: List<FusedPrecipitation>, now: Instant): Double? {
            val nearest = timeline
                .filter { it.radarShare > 0.0 }
                .minByOrNull { Duration.between(it.at, now).abs() }
                ?: return null
            return nearest.takeIf { Duration.between(it.at, now).abs() <= RADAR_IS_NOW }
                ?.millimetresPerHour
        }

        /** How far from this moment a projected step can sit and still describe it. */
        private val RADAR_IS_NOW: Duration = Duration.ofMinutes(4)
    }
}

/** Whether a hazard is in force at this moment, not merely somewhere in the next day. */
fun Hazard.coversNow(now: Instant): Boolean = !from.isAfter(now) && until?.isAfter(now) != false
