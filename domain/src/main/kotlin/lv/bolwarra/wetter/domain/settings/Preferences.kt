// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.settings

/**
 * What the reader has chosen. These say how a number is written; every value
 * stays in the domain's canonical units (docs/decisions.md), so the arithmetic is
 * the same wherever the reader lives.
 */
data class Preferences(
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val wind: WindUnit = WindUnit.METRES_PER_SECOND,
    val precipitation: PrecipitationUnit = PrecipitationUnit.MILLIMETRES,
    /**
     * Black by default: a weather app is opened most at the two ends of the day, when
     * a bright page is worst, and black costs least on an OLED screen.
     */
    val theme: ThemeChoice = ThemeChoice.PURE_BLACK,
    /**
     * Whether severe weather is worth a notification, up to a day ahead. On by
     * default: the one interruption this app makes. The bar is the dial's warning
     * mark, set high on purpose.
     */
    val warnings: Boolean = true,
)

/** How temperature is written. Two units; nobody wants Kelvin on a weather app. */
enum class TemperatureUnit(val suffix: String) {
    CELSIUS("C"),
    FAHRENHEIT("F"),
    ;

    fun from(celsius: Double): Double = when (this) {
        CELSIUS -> celsius
        FAHRENHEIT -> celsius * NINE_FIFTHS + FREEZING_F
    }

    /**
     * A difference, converted: a gap of one degree Celsius is 1.8 Fahrenheit, not
     * 33.8. The learned local correction is a difference.
     */
    fun difference(celsius: Double): Double = when (this) {
        CELSIUS -> celsius
        FAHRENHEIT -> celsius * NINE_FIFTHS
    }

    private companion object {
        const val NINE_FIFTHS = 9.0 / 5.0
        const val FREEZING_F = 32.0
    }
}

/**
 * How wind speed is written. Metres per second is what both providers publish and
 * the domain keeps; the dial also says the wind in words.
 */
enum class WindUnit(val label: String) {
    METRES_PER_SECOND("m/s"),
    KILOMETRES_PER_HOUR("km/h"),
    MILES_PER_HOUR("mph"),
    KNOTS("kn"),
    ;

    fun from(metresPerSecond: Double): Double = when (this) {
        METRES_PER_SECOND -> metresPerSecond
        KILOMETRES_PER_HOUR -> metresPerSecond * KMH
        MILES_PER_HOUR -> metresPerSecond * MPH
        KNOTS -> metresPerSecond * KNOT
    }

    private companion object {
        const val KMH = 3.6
        const val MPH = 2.236936
        const val KNOT = 1.943844
    }
}

/**
 * How precipitation is written. The axis does not change: band boundaries in
 * `RainCurveBands` are thresholds in the weather, not in the notation.
 */
enum class PrecipitationUnit(val label: String) {
    MILLIMETRES("mm"),
    INCHES("in"),
    ;

    fun from(millimetres: Double): Double = when (this) {
        MILLIMETRES -> millimetres
        INCHES -> millimetres / MM_PER_INCH
    }

    /** How many places to write: a millimetre is 0.04 in, so inches need two. */
    val decimals: Int get() = if (this == INCHES) 2 else 1

    private companion object {
        const val MM_PER_INCH = 25.4
    }
}

/**
 * The two plates, and no "follow the system": each is a whole design with its own
 * ground and grey ladder, drawn to be read at any hour.
 */
enum class ThemeChoice {

    /**
     * Kenya Hara's *White*: off-white paper, true grayscale, near-black ink, and
     * elevation as a subtle darkening.
     */
    PURE_WHITE,

    /**
     * Ansel Adams' zone system: `Zone 0 #0A → I #1A → II #2A → III #3A`, each
     * layer about sixteen hex apart, true grayscale, brightness as elevation.
     */
    PURE_BLACK,
}
