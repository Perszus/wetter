// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import lv.bolwarra.wetter.domain.forecast.FusedPrecipitation
import lv.bolwarra.wetter.domain.hazard.Hazard
import lv.bolwarra.wetter.domain.hazard.HazardKind
import lv.bolwarra.wetter.domain.hazard.HazardSeverity
import lv.bolwarra.wetter.domain.model.CurrentWeather
import lv.bolwarra.wetter.domain.model.DailyWeather
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.WeatherCondition
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.ProviderMetadata
import lv.bolwarra.wetter.domain.settings.Preferences
import lv.bolwarra.wetter.domain.settings.TemperatureUnit
import lv.bolwarra.wetter.ui.theme.Atmosphere
import lv.bolwarra.wetter.ui.theme.WetterColors
import lv.bolwarra.wetter.ui.theme.darkPlate
import lv.bolwarra.wetter.ui.theme.lightPlate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every widget, drawn at every size its provider info lets a launcher resize it
 * to, in both themes and across weather that stresses the layout: long words,
 * three-digit and negative temperatures, warnings, rain and dry.
 *
 * Nothing placed may overlap anything else or leave the plate. Set the
 * WIDGET_GALLERY environment variable to also write every picture to
 * build/widget-gallery for review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetGalleryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gallery: File? = System.getenv("WIDGET_GALLERY")?.let {
        File("build/widget-gallery")
    }

    private val themes: Map<String, WetterColors> = mapOf(
        "light" to lightPlate(Atmosphere.Neutral),
        "dark" to darkPlate(Atmosphere.Neutral),
    )

    @Test
    fun rainFitsEverywhere() = check("rain", RainWidget(), widths(180f..420f), heights(40f..200f))

    @Test
    fun nowFitsEverywhere() = check("now", NowWidget(), widths(40f..300f), heights(40f..300f))

    @Test
    fun hoursFitEverywhere() = check("hours", HoursWidget(), widths(180f..420f), heights(40f..300f))

    @Test
    fun weekFitsEverywhere() = check("week", WeekWidget(), widths(180f..420f), heights(40f..240f))

    @Test
    fun overviewFitsEverywhere() = check(
        "overview",
        OverviewWidget(),
        widths(250f..420f),
        heights(
            110f..480f,
        ),
    )

    private fun check(
        name: String,
        widget: PictureWidget,
        widths: List<Float>,
        heights: List<Float>,
    ) {
        val faults = mutableListOf<String>()
        Scenario.entries.forEach { scenario ->
            val snapshot = scenario.snapshot()
            themes.forEach { (theme, colors) ->
                widths.forEach { w ->
                    heights.forEach { h ->
                        val case = "${scenario.name.lowercase()}-$theme-${w.toInt()}x${h.toInt()}"
                        val placed = mutableListOf<WidgetInk.Placed>()
                        val picture = widget.picture(
                            context,
                            w,
                            h,
                            snapshot,
                            colors = colors,
                            placed = placed,
                        )
                        faults += layoutFaults("$name $case", picture, w, placed)
                        gallery?.let { save(picture, File(it, "$name/$case.png")) }
                    }
                }
            }
        }
        assertTrue(faults.take(MAX_REPORTED).joinToString("\n"), faults.isEmpty())
    }

    /**
     * Leaves (text, marks, bars) may not overlap each other or leave the plate,
     * rounded corners included. Frames (the dial, a wash, the strip) contain
     * leaves: a leaf is either wholly inside a frame or clear of it, and frames
     * do not overlap.
     */
    private fun layoutFaults(
        label: String,
        picture: Bitmap,
        widthDp: Float,
        placed: List<WidgetInk.Placed>,
    ): List<String> {
        val plate = RectF(0f, 0f, picture.width.toFloat(), picture.height.toFloat())
        val scale = picture.width / widthDp
        val radius = minOf(
            PictureWidget.cornerRadiusDp(context) * scale,
            plate.width() / 2f,
            plate.height() / 2f,
        )
        val (frames, leaves) = placed.partition { it.what.startsWith("frame:") }
        val faults = mutableListOf<String>()
        placed.forEach { item ->
            if (!plate.holdsRounded(item.rect, radius)) {
                faults +=
                    "$label: ${item.what} ${item.rect} leaves the plate"
            }
        }
        for (i in leaves.indices) {
            for (j in i + 1 until leaves.size) {
                if (leaves[i].rect.overlaps(leaves[j].rect)) {
                    faults +=
                        "$label: ${leaves[i].what} ${leaves[i].rect} overlaps ${leaves[j].what} ${leaves[j].rect}"
                }
            }
        }
        for (i in frames.indices) {
            for (j in i + 1 until frames.size) {
                if (frames[i].rect.overlaps(frames[j].rect)) {
                    faults += "$label: ${frames[i].what} overlaps ${frames[j].what}"
                }
            }
        }
        leaves.forEach { leaf ->
            frames.forEach { frame ->
                if (leaf.rect.overlaps(frame.rect) && !frame.rect.containsWithin(leaf.rect)) {
                    faults +=
                        "$label: ${leaf.what} ${leaf.rect} straddles ${frame.what} ${frame.rect}"
                }
            }
        }
        return faults
    }

    private fun RectF.overlaps(other: RectF): Boolean = left < other.right - SLACK &&
        other.left < right - SLACK &&
        top < other.bottom - SLACK &&
        other.top < bottom - SLACK

    /** Whether [other] lies inside this rectangle with its corners rounded to [radius]. */
    private fun RectF.holdsRounded(other: RectF, radius: Float): Boolean {
        if (!containsWithin(other)) return false
        return listOf(
            other.left to other.top,
            other.right to other.top,
            other.left to other.bottom,
            other.right to other.bottom,
        ).all { (x, y) ->
            val cx = x.coerceIn(left + radius, right - radius)
            val cy = y.coerceIn(top + radius, bottom - radius)
            (x - cx) * (x - cx) + (y - cy) * (y - cy) <= (radius + SLACK) * (radius + SLACK)
        }
    }

    private fun RectF.containsWithin(other: RectF): Boolean = other.left >= left - SLACK &&
        other.top >= top - SLACK &&
        other.right <= right + SLACK &&
        other.bottom <= bottom + SLACK

    private fun save(picture: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun widths(range: ClosedFloatingPointRange<Float>) =
        (steps(range, WIDTH_STEP) + MEASURED_WIDTHS.filter { it in range }).distinct().sorted()

    private fun heights(range: ClosedFloatingPointRange<Float>) =
        (steps(range, HEIGHT_STEP) + MEASURED_HEIGHTS.filter { it in range }).distinct().sorted()
    private fun steps(range: ClosedFloatingPointRange<Float>, step: Float): List<Float> =
        generateSequence(range.start) { it + step }.takeWhile { it <= range.endInclusive }.toList()

    /** Weather chosen to stress the layout, not to be typical. */
    private enum class Scenario {
        /** Rain falling now and easing off, an ordinary autumn afternoon. */
        RAIN,

        /** A clear, still night below freezing: night marks, no rain at all. */
        NIGHT,

        /** Deep cold and heavy snow under a danger warning: wide negative figures. */
        COLD,

        /** Heat and hail in Fahrenheit: three-digit figures and the longest word. */
        STORM,
        ;

        fun snapshot(): WidgetSnapshot = when (this) {
            RAIN -> build(
                now = Instant.parse("2026-10-15T11:20:00Z"),
                current = WeatherCondition.RAIN,
                temperature = 11.4,
                rate = 2.4,
                wind = 7.0,
                hourly = { i -> HourSpec(10.0 + 3 * wave(i), if (i < 3) 2.0 - i * 0.6 else 0.0) },
                radar = { step -> (2.4 - step * 0.2).coerceAtLeast(0.0) },
            )
            NIGHT -> build(
                now = Instant.parse("2026-10-15T20:40:00Z"),
                current = WeatherCondition.CLEAR,
                temperature = -3.2,
                rate = 0.0,
                wind = 0.3,
                hourly = { i -> HourSpec(-4.0 + 4 * wave(i), 0.0, WeatherCondition.CLEAR) },
                radar = { 0.0 },
            )
            COLD -> build(
                now = Instant.parse("2026-01-20T09:10:00Z"),
                current = WeatherCondition.SNOW,
                temperature = -24.6,
                rate = 3.0,
                wind = 12.0,
                hourly = { i -> HourSpec(-24.0 + 4 * wave(i), 1.5, WeatherCondition.SNOW) },
                radar = { 3.0 },
                daily = { d -> -28.0 + d to -14.0 + d },
                hazard = HazardKind.EXTREME_COLD to HazardSeverity.DANGER,
            )
            STORM -> build(
                now = Instant.parse("2026-07-14T13:00:00Z"),
                current = WeatherCondition.THUNDERSTORM_WITH_HAIL,
                temperature = 38.4,
                rate = 12.0,
                wind = 19.0,
                hourly = { i ->
                    HourSpec(
                        34.0 + 4 * wave(i),
                        if (i in
                            2..4
                        ) {
                            9.0
                        } else {
                            0.0
                        },
                        WeatherCondition.THUNDERSTORM,
                    )
                },
                radar = { step -> if (step < 6) 12.0 else 0.0 },
                daily = { d -> 24.0 + d to 38.0 + d },
                hazard = HazardKind.EXTREME_HEAT to HazardSeverity.WARNING,
                unit = TemperatureUnit.FAHRENHEIT,
            )
        }
    }

    private class HourSpec(
        val temperature: Double,
        val rate: Double,
        val condition: WeatherCondition = if (rate >
            0.0
        ) {
            WeatherCondition.RAIN
        } else {
            WeatherCondition.PARTLY_CLOUDY
        },
    )

    private companion object {
        const val SLACK = 0.5f
        const val WIDTH_STEP = 35f
        const val HEIGHT_STEP = 15f
        const val MAX_REPORTED = 40

        /**
         * Sizes real launchers report, besides the grid: a Xiaomi HyperOS phone's
         * cells (2x2 at 131x148, 4x1 at 262x74, 4x4 at 262x263).
         */
        val MEASURED_WIDTHS = listOf(131f, 262f)
        val MEASURED_HEIGHTS = listOf(74f, 148f, 263f)

        val ZONE: ZoneId = ZoneId.of("Europe/Riga")
        val CONDITIONS = listOf(
            WeatherCondition.RAIN_SHOWERS,
            WeatherCondition.PARTLY_CLOUDY,
            WeatherCondition.CLEAR,
            WeatherCondition.OVERCAST,
            WeatherCondition.DRIZZLE,
            WeatherCondition.SNOW,
            WeatherCondition.THUNDERSTORM,
        )

        fun wave(hour: Int): Double = sin(hour / 24.0 * 2 * PI)

        fun build(
            now: Instant,
            current: WeatherCondition,
            temperature: Double,
            rate: Double,
            wind: Double,
            hourly: (Int) -> HourSpec,
            radar: (Int) -> Double,
            daily: (Int) -> Pair<Double, Double> = { d -> 4.0 + d % 3 to 12.0 + d % 4 },
            hazard: Pair<HazardKind, HazardSeverity>? = null,
            unit: TemperatureUnit = TemperatureUnit.CELSIUS,
        ): WidgetSnapshot {
            val startOfHour = now.minusSeconds(now.epochSecond % 3600)
            val hours = (0 until 48).map { i ->
                val at = startOfHour.plus(Duration.ofHours(i.toLong()))
                val local = at.atZone(ZONE).hour
                val spec = hourly(i)
                HourlyWeather(
                    timestamp = at,
                    temperature = spec.temperature,
                    apparentTemperature = spec.temperature,
                    precipitationProbability = if (spec.rate > 0) 80 else 5,
                    precipitation = spec.rate,
                    rain = spec.rate,
                    snowfall = null,
                    condition = spec.condition,
                    windSpeed = wind,
                    windGust = wind * 1.5,
                    cloudCover = 60,
                    cloudLow = 30,
                    cloudMedium = 30,
                    cloudHigh = 30,
                    uvIndex = null,
                    isDay = local in 7..18,
                )
            }
            val today = now.atZone(ZONE).toLocalDate()
            val days = (0 until 10).map { d ->
                val (low, high) = daily(d)
                DailyWeather(
                    date = today.plusDays(d.toLong()),
                    temperatureMin = low,
                    temperatureMax = high,
                    condition = CONDITIONS[d % CONDITIONS.size],
                    precipitationTotal = if (d % 2 == 0) 3.0 else 0.0,
                    precipitationProbabilityMax = 50,
                    precipitationHours = 2.0,
                    sunrise = null,
                    sunset = null,
                    windSpeedMax = wind,
                )
            }
            val forecast = WeatherForecast(
                location = WeatherLocation("Rīga", 56.9496, 24.1052, ZONE),
                current = CurrentWeather(
                    observedAt = now,
                    temperature = temperature,
                    apparentTemperature = temperature,
                    condition = current,
                    isDay = now.atZone(ZONE).hour in 7..18,
                    precipitation = rate,
                    windSpeed = wind,
                    windGust = wind * 1.5,
                    windDirection = 240,
                    humidity = 80,
                    pressure = 1008.0,
                ),
                hourly = hours,
                daily = days,
                fetchedAt = now,
                provider = ProviderMetadata("test", "Test", null, null, null, "Test"),
            )
            val timeline = (0 until WidgetSnapshot.STEPS).map { step ->
                FusedPrecipitation(
                    at = now.plus(WidgetSnapshot.STEP.multipliedBy(step.toLong())),
                    millimetresPerHour = radar(step),
                    confidence = 1.0,
                    radarShare = 1.0,
                    sources = 1,
                )
            }
            val hazards = hazard?.let { (kind, severity) ->
                listOf(
                    Hazard(
                        kind,
                        severity,
                        now.minus(Duration.ofHours(1)),
                        now.plus(Duration.ofHours(12)),
                    ),
                )
            }.orEmpty()
            return WidgetSnapshot(forecast, now, timeline, hazards, Preferences(temperature = unit))
        }
    }
}
