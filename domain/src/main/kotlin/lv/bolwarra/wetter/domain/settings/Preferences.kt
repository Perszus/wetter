package lv.bolwarra.wetter.domain.settings

/**
 * What the reader has chosen, and nothing else.
 *
 * docs/decisions.md keeps units canonical in the domain — Celsius, metres per
 * second, millimetres — and converts only for display. Nothing in here changes
 * that: these say how a number should be *written*, and every value that reaches
 * them is still in the unit the rest of the app computes in. A preference that
 * reached into the model would mean the same forecast produced different
 * arithmetic depending on where the reader lives.
 *
 * Settings stay minimal by policy. Everything here is something a sensible
 * default genuinely cannot answer for everybody: which units somebody reads in
 * is not a matter of taste and not something the app can infer without being
 * wrong for a third of the world.
 */
data class Preferences(
    val temperature: TemperatureUnit = TemperatureUnit.CELSIUS,
    val wind: WindUnit = WindUnit.METRES_PER_SECOND,
    val precipitation: PrecipitationUnit = PrecipitationUnit.MILLIMETRES,
    /**
     * Black by default.
     *
     * A weather app is opened at the two ends of the day far more than in the
     * middle of it — before leaving, and before going to bed — and that is when
     * a bright page is worst. It is also the plate that costs least on an OLED
     * screen, which is most of them.
     *
     * Paper is two taps away, and the whole design exists on both.
     */
    val theme: ThemeChoice = ThemeChoice.PURE_BLACK,
)

/**
 * How temperature is written.
 *
 * Two, not three. Kelvin is a real unit and nobody has ever wanted it on a
 * weather app, and an option nobody picks is a row everybody has to read past.
 */
enum class TemperatureUnit(val suffix: String) {
    CELSIUS("C"),
    FAHRENHEIT("F"),
    ;

    fun from(celsius: Double): Double = when (this) {
        CELSIUS -> celsius
        FAHRENHEIT -> celsius * NINE_FIFTHS + FREEZING_F
    }

    /**
     * A difference, converted.
     *
     * Not the same as converting a reading: a *gap* of one degree Celsius is
     * 1.8 Fahrenheit, not 33.8. The learned local correction is a difference, and
     * putting it through the reading conversion would have reported a fifth of a
     * degree of bias as a heatwave.
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
 * How wind speed is written.
 *
 * Metres per second is what both providers publish and what the domain keeps. It
 * is also, as notes.md observes, a number almost nobody has a feel for — which
 * is why the dial says the wind in words as well, and why that is a separate
 * problem from this one. Changing the unit does not make 12 m/s mean anything;
 * it lets somebody who thinks in mph read the figure at all.
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
 * How precipitation is written.
 *
 * The unit changes; the *axis* does not. Every band boundary in `RainCurveBands`
 * stays where it is, because those are thresholds in the weather rather than in
 * the notation — light rain is light rain whichever way it is written down, and
 * a chart whose bands moved with a display preference would be a different chart
 * for no reason.
 */
enum class PrecipitationUnit(val label: String) {
    MILLIMETRES("mm"),
    INCHES("in"),
    ;

    fun from(millimetres: Double): Double = when (this) {
        MILLIMETRES -> millimetres
        INCHES -> millimetres / MM_PER_INCH
    }

    /**
     * How many places to write. Inches need two where millimetres need one:
     * a millimetre of rain is 0.04 in, and at one decimal every ordinary
     * shower rounds to the same number.
     */
    val decimals: Int get() = if (this == INCHES) 2 else 1

    private companion object {
        const val MM_PER_INCH = 25.4
    }
}

/**
 * The two plates, and no third.
 *
 * There is deliberately no "follow the system". A plate here is not a light
 * switch — each one is a whole design with its own ground, its own grey ladder
 * and its own idea of where light comes from, and handing the choice to the
 * phone means the app is one of two different designs depending on a setting
 * made for something else. Both are drawn to be read at any hour.
 *
 * The cost is real and worth stating: somebody whose phone flips to dark at
 * sunset will not see this app flip with it. That is the trade for the app
 * looking like itself.
 */
enum class ThemeChoice {

    /**
     * Kenya Hara's *White*: white as a material rather than an absence.
     *
     * Off-white paper, true grayscale, ink at near-black, and elevation as a
     * subtle darkening instead of a lift.
     */
    PURE_WHITE,

    /**
     * Ansel Adams' zone system: `Zone 0 #0A → I #1A → II #2A → III #3A`, each
     * layer about sixteen hex apart, true grayscale, brightness as elevation.
     */
    PURE_BLACK,
}
