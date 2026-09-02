package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.PrecipitationSpell

/**
 * Reading an hourly forecast.
 *
 * Everything here is a pure function of a list of hours, so the questions the
 * screens ask — what does today look like, when does the rain start, when does
 * it stop — are answered in one place and tested without a device.
 */

/** The hours belonging to one calendar date at a place. */
fun List<HourlyWeather>.onDay(date: LocalDate, zone: ZoneId): List<HourlyWeather> =
    filter { it.timestamp.atZone(zone).toLocalDate() == date }

/**
 * Every unbroken run of wet hours, in order.
 *
 * A single dry hour splits a run rather than being bridged. That is the literal
 * reading of the data, and the literal reading is what somebody deciding whether
 * to leave now actually wants: told that it stops at three, they can go at
 * three. Smoothing a gap away to make the chart tidier would be the app
 * inventing a shower that the forecast does not contain.
 */
fun List<HourlyWeather>.precipitationSpells(): List<PrecipitationSpell> {
    if (isEmpty()) return emptyList()

    val hours = sortedBy { it.timestamp }
    val spells = mutableListOf<PrecipitationSpell>()
    var run = mutableListOf<HourlyWeather>()

    fun close(openEnded: Boolean) {
        if (run.isEmpty()) return
        spells += run.toSpell(openEnded)
        run = mutableListOf()
    }

    for (hour in hours) {
        if (hour.intensity.isWet) {
            run += hour
        } else {
            close(openEnded = false)
        }
    }
    // A run still open at the end of the data is open-ended: the forecast stops,
    // the weather does not.
    close(openEnded = true)

    return spells
}

/**
 * The next spell that has not finished by [instant] — the one in progress if it
 * is raining, otherwise the next to begin.
 */
fun List<HourlyWeather>.nextPrecipitation(instant: Instant): PrecipitationSpell? =
    precipitationSpells().firstOrNull { it.end.isAfter(instant) }

/** The spell in progress at [instant], if it is precipitating right now. */
fun List<HourlyWeather>.precipitationAt(instant: Instant): PrecipitationSpell? =
    precipitationSpells().firstOrNull {
        !it.start.isAfter(instant) && it.end.isAfter(instant)
    }

/** Total accumulation across these hours, in millimetres. Null when none is reported. */
fun List<HourlyWeather>.totalPrecipitation(): Double? {
    val reported = mapNotNull { it.precipitation }
    return if (reported.isEmpty()) null else reported.sum()
}

/** The heaviest hour in the series. */
fun List<HourlyWeather>.peakPrecipitation(): Double? = mapNotNull { it.precipitation }.maxOrNull()

private fun List<HourlyWeather>.toSpell(openEnded: Boolean): PrecipitationSpell {
    val first = first()
    val last = last()
    return PrecipitationSpell(
        start = first.timestamp,
        // Each row describes one hour, so the spell ends when the last wet hour
        // does, not when it began.
        end = last.timestamp.plus(Duration.ofHours(1)),
        peak = maxOf { it.intensity.ordinal }.let { PrecipitationIntensity.entries[it] },
        kind = dominantKind(),
        totalMillimetres = sumOf { it.precipitation ?: 0.0 },
        isOpenEnded = openEnded,
    )
}

/**
 * What the spell is mostly made of.
 *
 * A shower that turns to sleet is reported as sleet: the awkward part of the
 * spell is the part worth knowing about, and mixed is already the more cautious
 * of the two readings.
 */
private fun List<HourlyWeather>.dominantKind(): PrecipitationKind {
    val kinds = map { it.kind }.filter { it != PrecipitationKind.NONE }
    val hasSnow = PrecipitationKind.SNOW in kinds
    val hasRain = PrecipitationKind.RAIN in kinds
    val hasMixed = PrecipitationKind.MIXED in kinds

    return when {
        kinds.isEmpty() -> PrecipitationKind.NONE
        hasMixed || (hasSnow && hasRain) -> PrecipitationKind.MIXED
        hasSnow -> PrecipitationKind.SNOW
        else -> PrecipitationKind.RAIN
    }
}
