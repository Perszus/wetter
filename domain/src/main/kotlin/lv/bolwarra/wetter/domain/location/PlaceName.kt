// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.location

/**
 * What somewhere is called, once looked up: the thing itself, its town and its
 * country, the shape [lv.bolwarra.wetter.domain.model.WeatherLocation] uses. All
 * optional; a pin mid-Atlantic resolves to nothing, which is a correct answer.
 */
data class PlaceName(
    /** The most specific thing there is: a street and number, or a district. */
    val label: String,
    /** The settlement it sits in, when that is not already the label. */
    val region: String? = null,
    val country: String? = null,
)
