package lv.bolwarra.wetter.domain.forecast

import java.time.Duration
import java.time.Instant
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart and the sentence under it, held to the same story.
 *
 * The case that started this: radar showed rain arriving at one o'clock while
 * the line beneath said three, because one was drawn from the fused timeline and
 * the other computed from the provider's hourly rows.
 */
class ObservedSpellTest {

    private val now: Instant = Instant.parse("2026-09-05T12:00:00Z")

    private fun at(minutes: Long) = now.plus(Duration.ofMinutes(minutes))

    /** A fused step every ten minutes, from a list of rates in mm/h. */
    private fun timeline(vararg rates: Double) = rates.mapIndexed { index, rate ->
        FusedPrecipitation(
            at = at(index * 10L),
            millimetresPerHour = rate,
            confidence = 0.9,
            radarShare = 0.9,
            sources = 2,
        )
    }

    /** Hourly rows, dry unless a rate is given for that hour. */
    private fun hourly(vararg ratePerHour: Double, temperature: Double = 12.0) =
        ratePerHour.mapIndexed { index, rate ->
            HourlyWeather(
                timestamp = at(index * 60L),
                temperature = temperature,
                precipitationProbability = null,
                precipitation = rate,
                rain = null,
                snowfall = null,
                condition = WeatherCondition.CLEAR,
                windSpeed = null,
                windGust = null,
                apparentTemperature = temperature,
                uvIndex = null,
                cloudCover = null,
                cloudLow = null,
                cloudMedium = null,
                cloudHigh = null,
                isDay = true,
            )
        }

    @Test
    fun `rain the radar sees is announced when the radar sees it`() {
        // Dry for half an hour, then rain. The model, averaging over its hour,
        // has nothing until much later - and used to be the only thing asked.
        val fused = timeline(0.0, 0.0, 0.0, 1.2, 1.4, 1.1, 0.0)
        val model = hourly(0.0, 0.0, 0.0, 2.0)

        val spell = ObservedSpell.next(fused, model, now)!!

        assertEquals(at(30), spell.start)
        assertEquals(at(60), spell.end)
        assertTrue("the model's later spell must not win", spell.start.isBefore(at(180)))
    }

    @Test
    fun `a shower too short to show in an hourly average still counts`() {
        // Twenty minutes of real rain is a third of a millimetre spread across
        // its hour, which is under the threshold an hourly scan uses. The whole
        // reason the two disagreed even when fed the same weather.
        val fused = timeline(0.0, 2.0, 2.0, 0.0, 0.0, 0.0)
        val model = hourly(0.3, 0.0)

        val spell = ObservedSpell.next(fused, model, now)!!

        assertEquals(at(10), spell.start)
        assertEquals(at(30), spell.end)
        assertEquals(PrecipitationIntensity.LIGHT, spell.peak)
    }

    @Test
    fun `already raining reads as already raining`() {
        val fused = timeline(1.5, 1.5, 0.0)
        val spell = ObservedSpell.next(fused, hourly(1.5, 0.0), now)!!

        assertTrue("a spell in progress starts at or before now", !spell.start.isAfter(now))
        assertEquals(at(20), spell.end)
    }

    @Test
    fun `a dry window does not borrow the model's rain inside it`() {
        // Radar looked across the whole window and saw nothing. A model that
        // still expects rain in that window has already been overruled by the
        // thing that looked; only what lies past the horizon is still open.
        // Two hours of radar, all of it dry, against a model expecting rain an
        // hour in - well inside what the radar has already looked at.
        val fused = timeline(*DoubleArray(13) { 0.0 })
        val model = hourly(0.0, 3.0, 0.0)

        assertNull(
            "rain inside the radar's window must not come back from the model",
            ObservedSpell.next(fused, model, now),
        )
    }

    @Test
    fun `beyond the horizon the model is the only evidence there is`() {
        // Radar says nothing about tonight, and the hourly rows run for days.
        val fused = timeline(0.0, 0.0, 0.0)
        val model = hourly(0.0, 0.0, 0.0, 0.0, 4.0)

        val spell = ObservedSpell.next(fused, model, now)!!

        assertEquals(at(240), spell.start)
    }

    @Test
    fun `rain still falling at the end of the timeline is not given a stop time`() {
        val fused = timeline(0.0, 1.0, 1.0, 1.0)
        val spell = ObservedSpell.next(fused, hourly(0.0), now)!!

        assertTrue("a spell that ran off the end is open-ended", spell.isOpenEnded)
    }

    @Test
    fun `the model closes a spell that runs off the end when it knows how`() {
        val fused = timeline(0.0, 1.0, 1.0, 1.0)
        // The model has this hour wet and the next dry, so it knows where it stops.
        val model = hourly(1.0, 0.0)

        val spell = ObservedSpell.next(fused, model, now)!!

        assertEquals(at(60), spell.end)
        assertTrue("a closed spell is not open-ended", !spell.isOpenEnded)
    }

    @Test
    fun `with no timeline at all the model answers`() {
        val spell = ObservedSpell.next(emptyList(), hourly(0.0, 2.0), now)!!
        assertEquals(at(60), spell.start)
    }

    @Test
    fun `below freezing the same echo is snow`() {
        val fused = timeline(0.0, 1.5, 0.0)
        val cold = hourly(0.0, 1.5, temperature = -4.0)

        assertEquals(PrecipitationKind.SNOW, ObservedSpell.next(fused, cold, now)!!.kind)
    }
}
