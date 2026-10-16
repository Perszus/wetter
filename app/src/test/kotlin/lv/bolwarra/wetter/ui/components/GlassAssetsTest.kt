package lv.bolwarra.wetter.ui.components

import java.io.File
import lv.bolwarra.wetter.domain.glass.GlassMark
import lv.bolwarra.wetter.domain.model.WeatherCondition
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pictures and the rules that choose them, held to each other.
 *
 * Both directions matter. A rule that names a picture nobody drew is a dial with
 * a hole in it the first time that weather happens; a picture no rule can reach
 * is a megabyte of APK nobody will ever see.
 */
class GlassAssetsTest {

    private val shipped: Set<String> = File("src/main/assets/glass")
        .listFiles { f -> f.extension == "webp" }
        .orEmpty()
        .map { it.nameWithoutExtension }
        .toSet()

    private val rates = listOf(null, 0.0, 0.05, 0.3, 1.0, 3.0, 5.0, 10.0, 25.0, 60.0)
    private val winds = listOf(0.2, 2.0, 4.5, 6.5, 9.0, 12.0, 15.0, 19.0, 22.5, 28.0)
    private val temperatures = listOf(-30.0, -5.0, 0.0, 1.5, 10.0, 35.0)

    /** Every combination, with the hazards switched on where they could be. */
    private fun everyInput(): List<GlassMark.Input> = WeatherCondition.entries.flatMap { raw ->
        rates.flatMap { rate ->
            winds.flatMap { wind ->
                temperatures.flatMap { t ->
                    listOf(true, false).flatMap { day ->
                        listOf(60, 97).flatMap { humidity ->
                            val base = GlassMark.Input(raw, rate, wind, t, humidity, day)
                            variantsOf(base, t)
                        }
                    }
                }
            }
        }
    }

    private fun variantsOf(base: GlassMark.Input, t: Double): List<GlassMark.Input> = buildList {
        add(base)
        if (t <= -5.0) add(base.copy(extremeCold = true))
        if (t >= 35.0) {
            add(base.copy(extremeHeat = true))
            add(base.copy(extremeHeat = true, hazyAir = true))
        }
    }

    @Test
    fun theSetIsThere() {
        assertTrue("found ${shipped.size} pictures", shipped.size == 182)
    }

    @Test
    fun everyInputLandsOnAPictureThatExists() {
        val missing = everyInput().mapNotNull {
            GlassMark.of(it)
        }.filterNot { it in shipped }.toSet()
        assertTrue("chosen but not shipped: $missing", missing.isEmpty())
    }

    @Test
    fun everyPictureCanBeReached() {
        val reached = everyInput().mapNotNull { GlassMark.of(it) }.toSet()
        val unreachable = shipped - reached
        assertTrue("shipped but unreachable: $unreachable", unreachable.isEmpty())
    }
}
