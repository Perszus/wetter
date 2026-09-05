package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.PrecipitationSpell
import lv.bolwarra.wetter.domain.nextPrecipitation
import lv.bolwarra.wetter.domain.precipitationAt

/**
 * When it next rains, read from the same timeline the chart draws.
 *
 * The screen said two different things at once. The curve is drawn from the
 * fused timeline - radar-led, ten minutes to a step - and showed rain arriving
 * at one o'clock. The line beneath it was computed from the provider's hourly
 * rows and said three, because that is where the *model* first crosses the trace
 * threshold. Both were faithfully reporting their own source, and the reader had
 * a chart disagreeing with the sentence directly under it and no way to tell
 * which to believe.
 *
 * Two things caused it and both had to go.
 *
 * **Different evidence.** The chart had moved to radar and the sentence had not.
 * Most evidence, least speculation (docs/design-principles.md, rule 7) applies
 * to the words as much as to the picture: where the two disagree about the next
 * few hours, the one that looked wins.
 *
 * **Different resolution.** Even fed identical data these two disagree, because
 * an hourly average and a ten-minute sample are not the same measurement. A
 * sharp twenty-minute shower is a third of a millimetre spread across its hour -
 * under the threshold, invisible to an hourly scan, and plainly there on a
 * ten-minute curve. Reading both from one series removes that too.
 *
 * The model still answers beyond the timeline's horizon, which is six hours out.
 * Radar has nothing to say about tonight and the hourly rows run for days, so
 * past the end of the fused series the provider's own spells are the only
 * evidence there is.
 */
object ObservedSpell {

    /**
     * @param timeline the fused series the chart is drawn from, ten-minute
     *   steps. Empty falls straight through to the model.
     * @param hourly the provider's rows, for temperature, for what falls beyond
     *   the timeline, and for closing a spell that runs off its end.
     */
    fun next(
        timeline: List<FusedPrecipitation>,
        hourly: List<HourlyWeather>,
        from: Instant,
    ): PrecipitationSpell? {
        val steps = timeline.sortedBy { it.at }.filter { !it.at.isBefore(from.minus(STEP)) }
        if (steps.isEmpty()) return hourly.nextPrecipitation(from)

        val first = steps.indexOfFirst { it.isWet }
        if (first < 0) {
            // Nothing in the covered window. Anything the model has inside it
            // has already been overruled by the radar that looked; only what
            // lies beyond the horizon is still worth asking about.
            val horizon = steps.last().at
            return hourly.nextPrecipitation(horizon)
        }

        var last = first
        while (last + 1 < steps.size && steps[last + 1].isWet) last++

        // The first wet step may sit just behind `from` - that is the step
        // covering this moment, and a start in the recent past is how "it is
        // already raining" is said.
        val start = steps[first].at
        val wet = steps.subList(first, last + 1)
        val ranToTheEnd = last == steps.lastIndex

        // A run touching the end of the timeline has not been seen to stop. The
        // model may know when it does; if it does not, the honest answer is that
        // the forecast ran out, which PrecipitationSpell carries as open-ended
        // rather than inventing a time.
        val continuation = if (ranToTheEnd) hourly.precipitationAt(steps.last().at) else null

        return PrecipitationSpell(
            start = start,
            end = when {
                !ranToTheEnd -> steps[last + 1].at
                continuation != null -> continuation.end
                else -> steps.last().at.plus(STEP)
            },
            peak = wet.maxOf { PrecipitationIntensity.ofRate(it.millimetresPerHour) },
            kind = kindAt(hourly, start),
            totalMillimetres = wet.sumOf { it.millimetresPerHour * STEP_HOURS },
            isOpenEnded = ranToTheEnd && (continuation?.isOpenEnded ?: true),
        )
    }

    /**
     * What is falling, from the temperature at the time it starts.
     *
     * Radar returns echoes and cannot say whether they are frozen, so this is
     * the model's job and always was - the same rule the rest of the app uses.
     */
    private fun kindAt(hourly: List<HourlyWeather>, at: Instant): PrecipitationKind {
        val row = hourly
            .filter { !it.timestamp.isAfter(at) }
            .maxByOrNull { it.timestamp }
            ?: hourly.minByOrNull { it.timestamp }
        return PrecipitationKind.likelyAt(row?.temperature)
    }

    private val FusedPrecipitation.isWet: Boolean
        get() = millimetresPerHour >= PrecipitationIntensity.TRACE_MM_PER_HOUR

    /** The spacing the fused timeline is produced at. */
    private val STEP: Duration = Duration.ofMinutes(10)

    private const val STEP_HOURS = 1.0 / 6.0
}
