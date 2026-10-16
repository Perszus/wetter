// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.air

import java.time.Instant

/**
 * What is in the air here, now.
 *
 * Concentrations rather than an index: national indices turn the same micrograms
 * into different verdicts, so the measurement is banded against the WHO global air
 * quality guidelines (2021), and the same air reads the same everywhere.
 *
 * @param pm25 fine particles, micrograms per cubic metre, this hour.
 * @param pm25Average the trailing 24-hour mean, which the guideline is defined
 * against: a single smoky hour is not a bad day.
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
     * The band, judged on the daily mean where there is one. Null when nothing was
     * reported: no coverage should say nothing rather than "good".
     */
    val band: AirQualityBand?
        get() = (pm25Average ?: pm25)?.let { AirQualityBand.of(it) }
}

/**
 * How bad the air is, on the WHO 2021 ladder for 24-hour PM2.5: the guideline
 * value and its four interim targets, so the words describe distance from a health
 * target rather than any one regulator's compliance line.
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
 * Where air quality comes from. Separate from
 * [lv.bolwarra.wetter.domain.provider.WeatherProvider], so a weather provider
 * failing cannot take it down (docs/providers.md).
 */
interface AirQualitySource {

    val attribution: String

    suspend fun airQuality(latitude: Double, longitude: Double): Result<AirQuality>
}
