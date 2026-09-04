package lv.bolwarra.wetter.domain.air

import java.time.Instant

/**
 * What is in the air here, now.
 *
 * Concentrations rather than an index. An air quality index is a national
 * instrument - the European AQI, the US AQI, India's NAQI and China's all take
 * the same micrograms and return different verdicts, because each encodes what
 * its own regulator decided was acceptable. This app is used everywhere, so it
 * reports the measurement and bands it against the one threshold set that is
 * not national: the World Health Organization's global air quality guidelines
 * (2021). The same air reads the same in Riga and in Delhi.
 *
 * @param pm25 fine particles, micrograms per cubic metre, this hour. The
 *   pollutant that dominates health effects nearly everywhere.
 * @param pm25Average the trailing 24-hour mean, which is what the guideline is
 *   actually defined against. Judging an instantaneous reading by a daily
 *   threshold is the usual shortcut here, and it is wrong in both directions -
 *   a single smoky hour is not a bad day, and a steady haze never spikes.
 */
data class AirQuality(
    val observedAt: Instant,
    val pm25: Double?,
    val pm25Average: Double?,
    val pm10: Double?,
    val ozone: Double?,
    val nitrogenDioxide: Double?,
) {

    /**
     * The band, judged on the daily mean where there is one.
     *
     * Null when nothing was reported - which is a real outcome, not a zero.
     * Somewhere with no coverage should say nothing rather than say "good".
     */
    val band: AirQualityBand?
        get() = (pm25Average ?: pm25)?.let { AirQualityBand.of(it) }
}

/**
 * How bad the air is, on the WHO's 2021 ladder for 24-hour PM2.5.
 *
 * The numbers are the guideline value and its four interim targets, which exist
 * precisely because most of the world is a long way above the guideline and
 * needed a scale rather than a single pass/fail line. Using them as bands means
 * the words describe distance from a health target rather than compliance with
 * whichever regulator happens to have jurisdiction.
 */
enum class AirQualityBand {
    /** At or under the WHO guideline: 5 µg/m³ annual, 15 µg/m³ over a day. */
    GOOD,
    FAIR,
    MODERATE,
    POOR,
    VERY_POOR,
    EXTREMELY_POOR,
    ;

    /** Worth saying something about, rather than merely worth reporting. */
    val isNotable: Boolean get() = this >= MODERATE

    companion object {
        /** WHO 2021 air quality guideline for 24-hour PM2.5. */
        const val GUIDELINE_PM25 = 15.0

        /** Interim target 4. */
        const val FAIR_PM25 = 25.0

        /** Interim target 3. */
        const val MODERATE_PM25 = 37.5

        /** Interim target 2. */
        const val POOR_PM25 = 50.0

        /** Interim target 1. Above this there is no target left to miss. */
        const val VERY_POOR_PM25 = 75.0

        fun of(pm25: Double): AirQualityBand = when {
            pm25 <= GUIDELINE_PM25 -> GOOD
            pm25 <= FAIR_PM25 -> FAIR
            pm25 <= MODERATE_PM25 -> MODERATE
            pm25 <= POOR_PM25 -> POOR
            pm25 <= VERY_POOR_PM25 -> VERY_POOR
            else -> EXTREMELY_POOR
        }
    }
}

/**
 * Where air quality comes from.
 *
 * Separate from [lv.bolwarra.wetter.domain.provider.WeatherProvider] for the
 * same reason radar is: it answers a different question, on its own cadence,
 * from its own models, and a weather provider failing should not take it down
 * with it (docs/providers.md).
 */
interface AirQualitySource {

    val attribution: String

    suspend fun airQuality(latitude: Double, longitude: Double): Result<AirQuality>
}
