package lv.bolwarra.wetter.domain

import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Test

class ObservedConditionTest {

    private fun observed(
        reported: WeatherCondition,
        rate: Double?,
        temperature: Double? = 12.0,
        cloud: Int? = 90,
    ) = ObservedCondition.of(reported, rate, temperature, cloud)

    @Test
    fun `rain the model missed is reported as rain`() {
        // The case that started this: the chart drawn from radar showed rain
        // falling while the word above it said the sky was merely overcast.
        assertEquals(
            WeatherCondition.RAIN,
            observed(WeatherCondition.OVERCAST, rate = 2.0),
        )
    }

    @Test
    fun `a trace is drizzle, not rain`() {
        assertEquals(
            WeatherCondition.DRIZZLE,
            observed(WeatherCondition.OVERCAST, rate = 0.2),
        )
    }

    @Test
    fun `below freezing the same echo is snow`() {
        assertEquals(
            WeatherCondition.SNOW,
            observed(WeatherCondition.OVERCAST, rate = 3.0, temperature = -5.0),
        )
        assertEquals(
            WeatherCondition.SLEET,
            observed(WeatherCondition.OVERCAST, rate = 3.0, temperature = 1.5),
        )
    }

    @Test
    fun `rain nobody can see is not rain`() {
        // The hard half. Radar says nothing is falling, so the screen may not
        // say it is raining - and radar cannot see the sky, so the word comes
        // from the model's cloud cover rather than from an invented observation.
        assertEquals(
            WeatherCondition.OVERCAST,
            observed(WeatherCondition.RAIN, rate = 0.0, cloud = 95),
        )
        assertEquals(
            WeatherCondition.PARTLY_CLOUDY,
            observed(WeatherCondition.RAIN, rate = 0.0, cloud = 55),
        )
        assertEquals(
            WeatherCondition.CLEAR,
            observed(WeatherCondition.RAIN, rate = 0.0, cloud = 3),
        )
    }

    @Test
    fun `with no cloud reading there is no sky to name`() {
        // A dash is better than a sky nobody looked at.
        assertEquals(
            WeatherCondition.UNKNOWN,
            observed(WeatherCondition.RAIN, rate = 0.0, cloud = null),
        )
    }

    @Test
    fun `no radar means no contradiction`() {
        // An absent observation is not a disagreeing one. Outside radar
        // coverage the provider's word is the only evidence there is.
        assertEquals(
            WeatherCondition.RAIN,
            observed(WeatherCondition.RAIN, rate = null),
        )
        assertEquals(
            WeatherCondition.OVERCAST,
            observed(WeatherCondition.OVERCAST, rate = null),
        )
    }

    @Test
    fun `agreement changes nothing`() {
        assertEquals(
            WeatherCondition.RAIN,
            observed(WeatherCondition.RAIN, rate = 2.0),
        )
        assertEquals(
            WeatherCondition.OVERCAST,
            observed(WeatherCondition.OVERCAST, rate = 0.0),
        )
    }

    @Test
    fun `what radar cannot see keeps the provider's word`() {
        // A thunderstorm is a lightning network, fog sits below the beam, and
        // whether rain freezes on contact is a fact about the ground. On these
        // the symbol is the only evidence in existence.
        assertEquals(
            WeatherCondition.THUNDERSTORM,
            observed(WeatherCondition.THUNDERSTORM, rate = 0.0),
        )
        assertEquals(
            WeatherCondition.FOG,
            observed(WeatherCondition.FOG, rate = 0.0),
        )
        assertEquals(
            WeatherCondition.FREEZING_RAIN,
            observed(WeatherCondition.FREEZING_RAIN, rate = 0.0, temperature = -2.0),
        )
    }
}
