// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.location

import java.util.Locale
import kotlin.math.abs

/** A point on the earth, as typed. */
data class Coordinates(val latitude: Double, val longitude: Double) {

    /**
     * A name for a point that has none, such as a pin dropped on a field. Four
     * decimals, about eleven metres, with hemisphere letters, in the form
     * [CoordinateQuery] reads back, so it can be searched for again.
     */
    fun format(): String {
        val ns = if (latitude >= 0) 'N' else 'S'
        val ew = if (longitude >= 0) 'E' else 'W'
        return "%.4f°%s, %.4f°%s".format(
            Locale.ROOT,
            abs(latitude),
            ns,
            abs(longitude),
            ew,
        )
    }
}

/**
 * Recognising a coordinate pair in the search box, for points a gazetteer of
 * settlements cannot answer. Decimal, with or without hemisphere letters, and
 * degrees-minutes-seconds:
 *
 * ```
 * 56.9496, 24.1052        56.9496 24.1052        -33.87, 151.21
 * 56.9496N 24.1052E       N 56.9496, E 24.1052
 * 56°56'58"N 24°06'18"E
 * ```
 *
 * Text with no letters but N, S, E and W is a point; anything else is a name.
 */
object CoordinateQuery {

    fun parse(text: String): Coordinates? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isLetter() && it.uppercaseChar() !in HEMISPHERES }) return null

        val halves = split(trimmed) ?: return null
        val first = parsePart(halves.first) ?: return null
        val second = parsePart(halves.second) ?: return null

        // Latitude first unless the letters say otherwise, which is the
        // convention every map and every phone hands out.
        val (lat, lon) = when {
            first.axis == Axis.LONGITUDE && second.axis == Axis.LATITUDE -> second to first
            else -> first to second
        }
        if (lat.axis == Axis.LONGITUDE || lon.axis == Axis.LATITUDE) return null

        if (lat.value !in -90.0..90.0 || lon.value !in -180.0..180.0) return null
        return Coordinates(lat.value, lon.value)
    }

    /**
     * Cut the text in two: at a single comma, else whitespace, else after a
     * hemisphere letter, so "56.9496N24.1052E" divides where a person would read it.
     */
    private fun split(text: String): Halves? {
        val comma = text.indexOf(',')
        if (comma > 0 && text.indexOf(',', comma + 1) < 0) {
            return Halves(text.take(comma), text.drop(comma + 1))
        }

        val parts = text.split(WHITESPACE).filter { it.isNotBlank() }
        when (parts.size) {
            2 -> return Halves(parts[0], parts[1])
            // "N 56.9496 E 24.1052" - a letter standing apart from its number.
            4 -> return Halves(parts[0] + parts[1], parts[2] + parts[3])
            // DMS with spaces between the components: two halves of three each.
            6 -> return Halves(parts.take(3).joinToString(""), parts.drop(3).joinToString(""))
        }

        // Last resort, for text run together: cut after a hemisphere letter followed by a
        // number. Tried after the whitespace split, which would otherwise cut
        // "S33.87 E151.21" after the S. The match is zero-width, so its index is already
        // past the letter.
        val seam = HEMISPHERE_SEAM.find(text) ?: return null
        return Halves(text.take(seam.range.first), text.drop(seam.range.first))
    }

    private data class Halves(val first: String, val second: String)

    private enum class Axis { LATITUDE, LONGITUDE, EITHER }

    private data class Part(val value: Double, val axis: Axis)

    private fun parsePart(raw: String): Part? {
        var text = raw.trim().replace(" ", "")
        if (text.isEmpty()) return null

        var sign = 1.0
        var axis = Axis.EITHER

        // A hemisphere letter can lead or trail, and carries the sign.
        val letters = text.filter { it.uppercaseChar() in HEMISPHERES }
        if (letters.length > 1) return null
        if (letters.isNotEmpty()) {
            when (letters.first().uppercaseChar()) {
                'N' -> axis = Axis.LATITUDE
                'S' -> {
                    axis = Axis.LATITUDE
                    sign = -1.0
                }
                'E' -> axis = Axis.LONGITUDE
                'W' -> {
                    axis = Axis.LONGITUDE
                    sign = -1.0
                }
            }
            text = text.filterNot { it.uppercaseChar() in HEMISPHERES }
        }

        if (text.startsWith('-')) {
            // Both a minus and a southern hemisphere letter is somebody saying
            // the same thing twice, or contradicting themselves. Neither is
            // worth guessing at.
            if (sign < 0) return null
            sign = -1.0
            text = text.drop(1)
        } else if (text.startsWith('+')) {
            text = text.drop(1)
        }

        val magnitude = parseMagnitude(text) ?: return null
        return Part(sign * magnitude, axis)
    }

    /** A plain decimal, or degrees-minutes-seconds in any of the usual marks. */
    private fun parseMagnitude(text: String): Double? {
        if (text.isEmpty()) return null

        if (text.none { it in DMS_MARKS }) return text.toDoubleOrNull()

        val parts = text.split(*DMS_MARKS.map { it.toString() }.toTypedArray())
            .filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 3) return null

        val degrees = parts[0].toDoubleOrNull() ?: return null
        val minutes = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        val seconds = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        if (minutes < 0 || minutes >= 60 || seconds < 0 || seconds >= 60) return null

        return degrees + minutes / 60.0 + seconds / 3600.0
    }

    private val HEMISPHERES = setOf('N', 'S', 'E', 'W')

    private val WHITESPACE = Regex("\\s+")

    /** A hemisphere letter with a number straight after it. */
    private val HEMISPHERE_SEAM = Regex("(?<=[NSEWnsew])(?=[-+0-9])")

    /** Degree, minute and second marks, in the forms maps actually emit. */
    private val DMS_MARKS = charArrayOf('°', '\'', '"', '′', '″', 'º')
}
