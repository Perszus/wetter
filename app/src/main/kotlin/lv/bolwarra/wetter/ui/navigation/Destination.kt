// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.ui.navigation

/**
 * Wetter's places to be. Plain string routes: nothing carries an argument, so
 * type-safe route generation would buy nothing.
 */
enum class Destination(val route: String) {
    Weather("weather"),
    Locations("locations"),
    Settings("settings"),
}
