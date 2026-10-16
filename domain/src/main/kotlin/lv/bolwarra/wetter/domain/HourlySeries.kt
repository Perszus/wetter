// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import lv.bolwarra.wetter.domain.model.HourlyWeather
import lv.bolwarra.wetter.domain.model.PrecipitationIntensity
import lv.bolwarra.wetter.domain.model.PrecipitationKind
import lv.bolwarra.wetter.domain.model.PrecipitationSpell

/** The hours belonging to one calendar date at a place. */
fun List<HourlyWeather>.onDay(date: LocalDate, zone: ZoneId): List<HourlyWeather> =
    filter { it.timestamp.atZone(zone).toLocalDate() == date }

/**
 * A rolling window of hours starting at the hour containing [from]. Providers
 * disagree on where an hourly series begins (local midnight or the current hour),
 * so a window from now is the same length whoever answered.
 */
fun List<HourlyWeather>.window(from: Instant, hours: Long): List<HourlyWeather> {
    val sorted = sortedBy { it.timestamp }
    val start = sorted.hourCovering(from)
    val end = start.plus(Duration.ofHours(hours))
    return sorted.filter { !it.timestamp.isBefore(start) && it.timestamp.isBefore(end) }
}

/**
 * When the row that covers [instant] began, read from the series rather than
 * truncated to the UTC hour: in India, Nepal, Iran, Newfoundland, central
 * Australia and other half- and quarter-hour zones, the provider's rows start at
 * :15, :30 or :45 past, and truncation would open the window on an hour that has
 * not started.
 */
fun List<HourlyWeather>.hourCovering(instant: Instant): Instant = asSequence()
    .map { it.timestamp }
    .filter { !it.isAfter(instant) }
    .maxOrNull()
    // Nothing has begun yet (a forecast entirely in the future), so the window opens
    // on what is coming.
    ?: instant.truncatedTo(ChronoUnit.HOURS)

/**
 * Every unbroken run of wet hours, in order. A single dry hour splits a run: told
 * it stops at three, somebody can leave at three.
 *
 * Wet means worth calling rain, a higher bar than the curve is drawn at
 * ([PrecipitationIntensity.isWorthNaming]).
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
        if (hour.intensity.isWorthNaming) {
            run += hour
        } else {
            close(openEnded = false)
        }
    }
    // A run still open at the end of the data is open-ended: the forecast stops, the
    // weather does not.
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

/**
 * Accumulation between two instants, pro-rating the hours the range cuts: the
 * timeline rolls by the minute while the data comes in whole hours. Linear
 * across the hour, as the curve between two samples is.
 */
fun List<HourlyWeather>.totalPrecipitation(from: Instant, to: Instant): Double? {
    if (!to.isAfter(from)) return null

    val hours = sortedBy { it.timestamp }
    var total = 0.0
    var reported = false

    hours.forEachIndexed { index, hour ->
        val millimetres = hour.precipitation ?: return@forEachIndexed
        val end = hours.getOrNull(index + 1)?.timestamp
            ?: hour.timestamp.plus(Duration.ofHours(1))
        val overlap = Duration.between(
            maxOf(hour.timestamp, from),
            minOf(end, to),
        )
        if (overlap.isNegative || overlap.isZero) return@forEachIndexed

        val span = Duration.between(hour.timestamp, end)
        reported = true
        total += millimetres * overlap.toMillis() / span.toMillis().toDouble()
    }

    return if (reported) total else null
}

/** The heaviest hour in the series. */
fun List<HourlyWeather>.peakPrecipitation(): Double? = mapNotNull { it.precipitation }.maxOrNull()

private fun List<HourlyWeather>.toSpell(openEnded: Boolean): PrecipitationSpell {
    val first = first()
    val last = last()
    return PrecipitationSpell(
        start = first.timestamp,
        // Each row describes one hour, so the spell ends when the last wet hour does.
        end = last.timestamp.plus(Duration.ofHours(1)),
        peak = maxOf { it.intensity.ordinal }.let { PrecipitationIntensity.entries[it] },
        kind = dominantKind(),
        totalMillimetres = sumOf { it.precipitation ?: 0.0 },
        isOpenEnded = openEnded,
    )
}

/**
 * What the spell is mostly made of. A shower that turns to sleet is reported as
 * sleet: the awkward part is the part worth knowing.
 */
fun List<HourlyWeather>.dominantKind(): PrecipitationKind {
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
