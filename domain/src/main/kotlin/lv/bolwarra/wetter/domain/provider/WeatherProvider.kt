// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.domain.provider

import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation

/**
 * One source of weather, behind a single door. Everything above sees only
 * Wetter's own models; nothing outside `data/provider/` imports an implementation
 * (docs/providers.md).
 *
 * [getForecast] returns a `Result`, since a failure is the router's cue to try the
 * next candidate. Its failure is always a
 * [lv.bolwarra.wetter.domain.model.WeatherError].
 */
interface WeatherProvider {

    /** Stable identifier, persisted with cached forecasts. Never changes. */
    val id: String

    /** The provider's name as its terms require it to be written. */
    val displayName: String

    /**
     * The credit this provider's terms require, shown verbatim in About.
     * On the interface rather than only inside [ProviderMetadata] so that
     * attribution can be listed without a forecast having been fetched
     * (docs/providers.md).
     */
    val attribution: String

    val capabilities: ProviderCapabilities

    val coverage: ProviderCoverage

    suspend fun getForecast(location: WeatherLocation): Result<WeatherForecast>
}

/**
 * Carries a [WeatherError] inside a failed `Result`.
 *
 * `Result` needs a `Throwable`, and the router needs to branch on the kind of
 * failure without inspecting exception classes from three different libraries.
 * This is the one place those two requirements meet.
 */
class WeatherFailure(val error: WeatherError) : Exception(error.toString())

/** The error behind a failed provider call, whatever the throwable turned out to be. */
fun Throwable.asWeatherError(): WeatherError =
    (this as? WeatherFailure)?.error ?: WeatherError.Unknown(this)
