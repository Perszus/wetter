package lv.bolwarra.wetter.domain.hazard

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardsTest {

    private val now = Instant.parse("2026-07-14T09:00:00Z")

    private fun hour(
        index: Int,
        temperature: Double = 18.0,
        apparent: Double? = null,
        gust: Double? = null,
        precipitation: Double? = 0.0,
        uv: Double? = null,
        condition: WeatherCondition = WeatherCondition.OVERCAST,
    ) = HourlyWeather(
        timestamp = now.plus(Duration.ofHours(index.toLong())),
        temperature = temperature,
        apparentTemperature = apparent,
        precipitationProbability = null,
        precipitation = precipitation,
        rain = null,
        snowfall = null,
        condition = condition,
        windSpeed = null,
        windGust = gust,
        uvIndex = uv,
        cloudCover = null,
        cloudLow = null,
        cloudMedium = null,
        cloudHigh = null,
        isDay = true,
    )

    private fun forecast(hours: List<HourlyWeather>) = WeatherForecast(
        location = WeatherLocation("Riga", 56.9496, 24.1052, ZoneId.of("Europe/Riga")),
        current = CurrentWeather(
            observedAt = now,
            temperature = 18.0,
            apparentTemperature = null,
            condition = WeatherCondition.OVERCAST,
            isDay = true,
            precipitation = null,
            windSpeed = null,
            windGust = null,
            windDirection = null,
            humidity = null,
            pressure = null,
        ),
        hourly = hours,
        daily = emptyList(),
        fetchedAt = now,
        provider = ProviderMetadata(
            id = "test",
            name = "Test",
            model = null,
            resolutionKm = null,
            forecastGeneratedAt = null,
            attribution = "Test",
        ),
    )

    private fun scan(hours: List<HourlyWeather>, air: AirQuality? = null) =
        Hazards.scan(forecast(hours), air, now)

    @Test
    fun `an ordinary day raises nothing`() {
        assertTrue(scan(List(12) { hour(it) }).isEmpty())
    }

    @Test
    fun `heat is measured on what it feels like, not the air temperature`() {
        // Thirty in the shade at ninety per cent humidity is the dangerous one,
        // and the air temperature alone cannot tell you that.
        val hours = List(6) { hour(it, temperature = 30.0, apparent = 41.0) }
        val heat = scan(hours).single()
        assertEquals(HazardKind.EXTREME_HEAT, heat.kind)
        assertEquals(HazardSeverity.DANGER, heat.severity)
    }

    @Test
    fun `the window is the run of hours it holds for`() {
        val hours = List(12) { index ->
            hour(index, apparent = if (index in 3..5) 34.0 else 20.0)
        }
        val heat = scan(hours).single()
        assertEquals(now.plus(Duration.ofHours(3)), heat.from)
        assertEquals(now.plus(Duration.ofHours(6)), heat.until)
        assertEquals(HazardSeverity.WARNING, heat.severity)
    }

    @Test
    fun `a hazard still going at the edge of the forecast has no end`() {
        // Saying it stops at the last hour held would be a claim nobody made.
        val hours = List(6) { hour(it, apparent = 34.0) }
        assertNull(scan(hours).single().until)
    }

    @Test
    fun `wind is judged on the gust, on Beaufort's own numbers`() {
        assertNull(scan(List(4) { hour(it, gust = 17.0) }).firstOrNull())
        assertEquals(
            HazardSeverity.WARNING,
            scan(List(4) { hour(it, gust = Hazards.GALE_MS) }).single().severity,
        )
        assertEquals(
            HazardSeverity.DANGER,
            scan(List(4) { hour(it, gust = Hazards.STORM_MS) }).single().severity,
        )
    }

    @Test
    fun `heavy rain and heavy snow are told apart by what is falling`() {
        val rain = scan(
            List(3) {
                hour(
                    it,
                    precipitation = 25.0,
                    condition = WeatherCondition.RAIN,
                    temperature = 12.0,
                )
            },
        )
        assertEquals(HazardKind.TORRENTIAL_RAIN, rain.single().kind)

        // The same millimetres as snow are a different hazard and a lower bar,
        // because four millimetres of liquid is four centimetres of snow.
        val snow = scan(
            List(3) {
                hour(it, precipitation = 5.0, condition = WeatherCondition.SNOW, temperature = -4.0)
            },
        )
        assertEquals(HazardKind.HEAVY_SNOW, snow.single().kind)
    }

    @Test
    fun `ice needs no amount to qualify`() {
        val ice = scan(
            List(2) {
                hour(
                    it,
                    precipitation = 0.2,
                    condition = WeatherCondition.FREEZING_RAIN,
                    temperature = -1.0,
                )
            },
        )
        assertEquals(HazardKind.ICE, ice.single().kind)
        assertEquals(HazardSeverity.DANGER, ice.single().severity)
    }

    @Test
    fun `the worst comes first`() {
        val hours = List(6) { index ->
            hour(
                index,
                apparent = 34.0,
                gust = Hazards.STORM_MS,
                uv = 9.0,
            )
        }
        val found = scan(hours)
        assertEquals(HazardKind.DAMAGING_WIND, found.first().kind)
        assertEquals(HazardSeverity.DANGER, found.first().severity)
        assertTrue(
            found.map {
                it.kind
            }.containsAll(listOf(HazardKind.EXTREME_HEAT, HazardKind.EXTREME_UV)),
        )
    }

    @Test
    fun `bad air is raised from the air service, not from an hour`() {
        val filthy = AirQuality(
            // Stamped at the hour it was measured, as the service reports it.
            observedAt = now.minus(Duration.ofMinutes(40)),
            pm25 = 80.0,
            pm25Average = 80.0,
            pm10 = null,
            ozone = null,
            nitrogenDioxide = null,
        )
        val found = scan(List(4) { hour(it) }, air = filthy)
        assertEquals(HazardKind.UNBREATHABLE_AIR, found.single().kind)
        assertEquals(HazardSeverity.DANGER, found.single().severity)
        // Already happening, and it must still read that way against a clock a
        // moment behind the scan - which the screen's always is, because the
        // scan runs on a fresher instant than the frame does.
        assertTrue(found.single().hasBegunBy(now))
        assertTrue(found.single().hasBegunBy(now.minusSeconds(30)))
    }

    @Test
    fun `clean air raises nothing`() {
        val clean = AirQuality(now, 4.0, 4.0, null, null, null)
        assertTrue(scan(List(4) { hour(it) }, air = clean).isEmpty())
    }

    @Test
    fun `nothing beyond a day counts`() {
        // A gale the day after tomorrow is not something to put a mark up for.
        val hours = List(40) { index -> hour(index, gust = if (index > 30) 30.0 else 2.0) }
        assertTrue(scan(hours).isEmpty())
    }

    @Test
    fun `the peak is carried out with the hazard`() {
        val hours = List(6) { index ->
            hour(index, gust = if (index == 2) 38.0 else 20.0)
        }
        val wind = scan(hours).single()
        assertEquals(HazardKind.DAMAGING_WIND, wind.kind)
        assertEquals(HazardSeverity.DANGER, wind.severity)
        // Severity stops at "change the plan", so without the peak a gale and a
        // hurricane are the same warning. This is what tells them apart.
        assertEquals(38.0, wind.peak!!, 1e-9)
        assertTrue(wind.peak!! >= Hazards.HURRICANE_MS)
    }

    @Test
    fun `a gale does not read as a hurricane`() {
        val gale = scan(List(4) { hour(it, gust = 19.0) }).single()
        assertTrue(gale.peak!! < Hazards.HURRICANE_MS)
    }

    @Test
    fun `the stretch named is the significant one, not the first one`() {
        // Measured on a real forecast for Kolkata: apparent temperature crossed
        // the heat threshold three times over two days - a short tail that
        // evening, and two longer stretches the next day. All three were
        // warnings, and taking the first meant naming the two-hour tail and
        // never mentioning the nine-hour afternoon.
        // None of them underway: the tail began an hour out, the way it did
        // there - the app was looked at at ten past nine and the evening's
        // stretch ran from ten.
        val hours = buildList {
            add(hour(0, temperature = 20.0, apparent = 24.0))
            addAll(List(2) { hour(it + 1, temperature = 27.0, apparent = 33.0) })
            addAll(List(6) { hour(it + 3, temperature = 20.0, apparent = 24.0) })
            addAll(List(9) { hour(it + 9, temperature = 29.0, apparent = 35.4) })
        }

        val heat = scan(hours).single { it.kind == HazardKind.EXTREME_HEAT }
        assertEquals(now.plus(Duration.ofHours(9)), heat.from)
        assertEquals(35.4, heat.peak!!, 0.001)
    }

    @Test
    fun `something already happening keeps its place over something larger later`() {
        // The mark on the dial has to agree with what is out of the window. A
        // gale blowing right now is not displaced by a bigger one tomorrow.
        val hours = buildList {
            addAll(List(2) { hour(it, gust = 18.0) })
            addAll(List(4) { hour(it + 2, gust = 5.0) })
            addAll(List(9) { hour(it + 6, gust = 20.0) })
        }

        val wind = scan(hours).single { it.kind == HazardKind.DAMAGING_WIND }
        assertEquals(now, wind.from)
    }
}
