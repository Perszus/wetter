// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.glass

import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherCondition.CLEAR
import lv.bolwarra.wetter.domain.model.WeatherCondition.DRIZZLE
import lv.bolwarra.wetter.domain.model.WeatherCondition.OVERCAST
import lv.bolwarra.wetter.domain.model.WeatherCondition.RAIN
import lv.bolwarra.wetter.domain.model.WeatherCondition.SNOW
import lv.bolwarra.wetter.domain.model.WeatherCondition.THUNDERSTORM_WITH_HAIL
import lv.bolwarra.wetter.domain.model.WeatherCondition.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassMarkTest {

    private fun mark(
        raw: WeatherCondition,
        rate: Double? = null,
        wind: Double? = 2.0,
        temperature: Double? = 12.0,
        humidity: Int? = 60,
        day: Boolean = true,
        cold: Boolean = false,
        heat: Boolean = false,
        hazy: Boolean = false,
    ) = GlassMark.of(GlassMark.Input(raw, rate, wind, temperature, humidity, day, cold, heat, hazy))

    @Test
    fun rainClimbsTheAppsOwnScale() {
        assertEquals("light_rain_day", mark(RAIN, rate = 1.0))
        assertEquals("rain_day", mark(RAIN, rate = 4.0))
        assertEquals("heavy_rain_day", mark(RAIN, rate = 10.0))
        // the torrential-rain warning and danger thresholds
        assertEquals("very_heavy_rain_day", mark(RAIN, rate = 25.0))
        assertEquals("torrential_rain_night", mark(RAIN, rate = 60.0, day = false))
    }

    @Test
    fun windOverRainRoundsDownToADrawnRung() {
        assertEquals("windy_rain_day", mark(RAIN, rate = 3.0, wind = 9.0))
        // Beaufort 7 has no picture over rain; it is shown as 6, never as 8
        assertEquals("strong_wind_rain_day", mark(RAIN, rate = 3.0, wind = 15.0))
        assertEquals("gale_rain_day", mark(RAIN, rate = 3.0, wind = 30.0))
    }

    @Test
    fun whereProviderAndMeasurementDisagreeTheGlassShowsBoth() {
        // rain said, drizzle measured
        assertEquals("drizzle_rain_day", mark(RAIN, rate = 0.3))
        // drizzle said, rain measured
        assertEquals("heavy_drizzle_day", mark(DRIZZLE, rate = 1.0))
        // drizzle said, below freezing
        assertEquals("drizzle_snow_grains_day", mark(DRIZZLE, rate = 0.3, temperature = -2.0))
        // rain at a temperature where either can fall
        assertEquals("rain_snow_day", mark(RAIN, rate = 4.0, temperature = 1.5))
    }

    @Test
    fun aDrySkyIsWindThenDampThenSky() {
        assertEquals("gale_day", mark(CLEAR, wind = 19.0))
        assertEquals("storm_force_wind_day", mark(CLEAR, wind = 26.0))
        assertEquals("mist_day", mark(OVERCAST, humidity = 97))
        assertEquals("calm_night", mark(CLEAR, wind = 0.2, day = false))
        assertEquals("clear_day", mark(CLEAR))
    }

    @Test
    fun extremesWinOnlyWhileLittleIsFalling() {
        assertEquals(
            "extreme_cold_snow_day",
            mark(SNOW, rate = 1.0, temperature = -30.0, cold = true),
        )
        assertEquals("heavy_snow_day", mark(SNOW, rate = 5.0, temperature = -30.0, cold = true))
        assertEquals(
            "extreme_heat_hazy_day",
            mark(CLEAR, temperature = 38.0, heat = true, hazy = true),
        )
    }

    @Test
    fun hailFollowsTheTemperatureAndTheRate() {
        assertEquals(
            "thunderstorm_snow_hail_day",
            mark(THUNDERSTORM_WITH_HAIL, rate = 3.0, temperature = -1.0),
        )
        assertEquals("thunderstorm_rain_hail_day", mark(THUNDERSTORM_WITH_HAIL, rate = 3.0))
        assertEquals("thunderstorm_heavy_hail_day", mark(THUNDERSTORM_WITH_HAIL, rate = 12.0))
    }

    @Test
    fun anUnknownCodeLeavesTheGlassBare() {
        assertNull(mark(UNKNOWN, rate = 3.0))
    }
}
