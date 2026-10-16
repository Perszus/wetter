// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.screens

import androidx.annotation.StringRes
import lv.bolwarra.wetter.R

/**
 * The three questions somebody comes to a weather app to ask, a page each: the
 * next hours and the next weeks are different questions. [Today] is the default,
 * the one asked most.
 */
enum class WeatherDomain(@param:StringRes val label: Int) {
    Today(R.string.domain_today),
    Week(R.string.domain_week),
    Month(R.string.domain_month),
}
