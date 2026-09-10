package lv.bolwarra.wetter.domain.hazard

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import lv.bolwarra.wetter.domain.air.AirQuality
import lv.bolwarra.wetter.domain.climate.Climatology
import lv.bolwarra.wetter.domain.climate.DayNormal
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

    /**
     * The tails as they actually came back from ERA5 for these places and dates,
     * so the numbers under these tests are measured rather than invented.
     */
    private fun climatologyOf(
        warmTail: Double? = null,
        warmExtreme: Double? = null,
        coldTail: Double? = null,
        coldExtreme: Double? = null,
        gustTail: Double? = null,
        gustExtreme: Double? = null,
    ) = Climatology(
        // Every date in a leap year, so the window can cross midnight and any
        // 29 February without the lookup falling through to null.
        (1..366).map { LocalDate.ofYearDay(LEAP_YEAR, it) }
            .map { MonthDay.of(it.month, it.dayOfMonth) }
            .associateWith {
                DayNormal(
                    monthDay = it,
                    medianHigh = null,
                    medianLow = null,
                    wetShare = 0.0,
                    samples = 110,
                    warmTail = warmTail,
                    coldTail = coldTail,
                    gustTail = gustTail,
                    warmExtreme = warmExtreme,
                    coldExtreme = coldExtreme,
                    gustExtreme = gustExtreme,
                )
            },
    )

    @Test
    fun `minus twenty is a hazard in Riga and not in Yakutsk`() {
        // The measured mid-January tails: Rīga -18.3 / -25.8, Yakutsk -58.0 /
        // -59.5. One global number cannot serve both, and the one this app used
        // to carry - minus twenty-five - served neither: it is a temperature
        // Rīga reaches about never, so the warning was dead code in a country
        // that gets genuinely dangerous winters.
        val night = List(4) { hour(it, temperature = -20.0, apparent = -20.0) }

        val riga = Hazards.scan(
            forecast(night),
            air = null,
            now = now,
            climate = climatologyOf(coldTail = -18.3, coldExtreme = -25.8),
        )
        assertEquals(
            HazardSeverity.WARNING,
            riga.single { it.kind == HazardKind.EXTREME_COLD }.severity,
        )

        val yakutsk = Hazards.scan(
            forecast(night),
            air = null,
            now = now,
            climate = climatologyOf(coldTail = -58.0, coldExtreme = -59.5),
        )
        assertTrue(yakutsk.none { it.kind == HazardKind.EXTREME_COLD })
    }

    @Test
    fun `an ordinary muggy night in Kolkata is not a heat hazard`() {
        // Twenty-seven degrees of air at high humidity is thirty-three of heat
        // index, which clears the global bar and is what September does there
        // every night. The measured July tails are 42.1 / 42.9.
        val kolkata = climatologyOf(warmTail = 42.1, warmExtreme = 42.9)
        val muggy = List(4) { hour(it, temperature = 27.0, apparent = 33.0) }
        assertTrue(Hazards.scan(forecast(muggy), null, now, kolkata).isEmpty())

        // And a real heat event there still is one.
        val heatwave = List(4) { hour(it, temperature = 40.0, apparent = 43.5) }
        assertEquals(
            HazardSeverity.DANGER,
            Hazards.scan(forecast(heatwave), null, now, kolkata)
                .single { it.kind == HazardKind.EXTREME_HEAT }.severity,
        )
    }

    @Test
    fun `a mild place cannot warn about a pleasant day it has not had before`() {
        // Unusual is not the same as dangerous. A coastal town whose warmest
        // comparable day in a decade is twenty-four gets no heat hazard at
        // twenty-five, because the heat index says nothing is at risk below its
        // Caution line whatever the local record says.
        val mild = climatologyOf(warmTail = 23.0, warmExtreme = 24.0)
        val pleasant = List(4) { hour(it, temperature = 25.0, apparent = 25.0) }
        assertTrue(Hazards.scan(forecast(pleasant), null, now, mild).isEmpty())
    }

    @Test
    fun `a windy place gets a higher bar, a calm one keeps the gale`() {
        // Wind is the one that may only be tightened. Two systems two centuries
        // apart put a damaging gust in the same place and neither adjusts for
        // where you are - so somewhere that gets Beaufort 8 fortnightly earns a
        // higher bar, and somewhere calm does not earn a lower one.
        val blowing = List(4) { hour(it, gust = 18.0) }

        val faroes = climatologyOf(gustTail = 25.0, gustExtreme = 30.0)
        assertTrue(Hazards.scan(forecast(blowing), null, now, faroes).isEmpty())

        val calm = climatologyOf(gustTail = 9.1, gustExtreme = 9.5)
        assertEquals(
            HazardSeverity.WARNING,
            Hazards.scan(forecast(blowing), null, now, calm)
                .single { it.kind == HazardKind.DAMAGING_WIND }.severity,
        )
    }

    @Test
    fun `absolute danger fires wherever it is reached`() {
        // There is no climate in which Beaufort 10 is fine, so a place whose own
        // tails sit above it does not get to shrug this off.
        val hurricane = List(3) { hour(it, gust = 33.0) }
        val patagonia = climatologyOf(gustTail = 28.0, gustExtreme = 40.0)
        assertEquals(
            HazardSeverity.DANGER,
            Hazards.scan(forecast(hurricane), null, now, patagonia)
                .single { it.kind == HazardKind.DAMAGING_WIND }.severity,
        )
    }

    @Test
    fun `with no archive the published absolute thresholds still apply`() {
        // A new place on its first day, or an archive that did not answer.
        val gale = List(3) { hour(it, gust = 18.0) }
        assertEquals(
            HazardSeverity.WARNING,
            Hazards.scan(forecast(gale), null, now, climate = null)
                .single { it.kind == HazardKind.DAMAGING_WIND }.severity,
        )
    }

    @Test
    fun `a mild-winter place can reach danger at its own temperature`() {
        // Measured tails: Berlin mid-January -11.8 / -16.0, Reykjavik
        // -17.6 / -20.4. Clamping the cold danger to the absolute warning gave
        // both of them a level they can never see - Reykjavik has essentially
        // never been -25 - so a night that is the worst in a decade there read
        // as merely a warning.
        val night = List(4) { hour(it, temperature = -20.0, apparent = -20.0) }

        val berlin = climatologyOf(coldTail = -11.8, coldExtreme = -16.0)
        assertEquals(
            HazardSeverity.DANGER,
            Hazards.scan(forecast(night), null, now, berlin)
                .single { it.kind == HazardKind.EXTREME_COLD }.severity,
        )

        // And Riga, which is used to it, gets the warning rather than the danger
        // at the same temperature.
        val riga = climatologyOf(coldTail = -18.3, coldExtreme = -25.8)
        assertEquals(
            HazardSeverity.WARNING,
            Hazards.scan(forecast(night), null, now, riga)
                .single { it.kind == HazardKind.EXTREME_COLD }.severity,
        )
    }

    @Test
    fun `heat may not reach danger below the published physiological band`() {
        // The asymmetry with cold, stated as a test. A body is a body: thirty
        // degrees of heat index hospitalises nobody, however unusual it is for
        // a cool coastal town that has never had one.
        val warm = List(4) { hour(it, temperature = 29.0, apparent = 30.0) }
        val cool = climatologyOf(warmTail = 21.0, warmExtreme = 23.0)
        val found = Hazards.scan(forecast(warm), null, now, cool)
            .singleOrNull { it.kind == HazardKind.EXTREME_HEAT }
        assertEquals(HazardSeverity.WARNING, found?.severity)
    }

    private companion object {
        /** Any year with a 29 February. */
        const val LEAP_YEAR = 2024
    }
}
