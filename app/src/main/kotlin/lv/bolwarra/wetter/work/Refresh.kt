// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.work

import android.content.Context
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.bolwarra.wetter.BuildConfig
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.WetterContainer
import lv.bolwarra.wetter.domain.hazard.HazardAnnouncements
import lv.bolwarra.wetter.domain.hazard.Hazards
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.model.WeatherForecast
import lv.bolwarra.wetter.domain.model.WeatherLocation
import lv.bolwarra.wetter.domain.provider.asWeatherError
import lv.bolwarra.wetter.notify.HazardNotifier
import lv.bolwarra.wetter.widget.Widgets

/**
 * One background pass, whichever wake started it (the [Heartbeat] alarm or the
 * periodic job behind it). The order is the app's priorities:
 *
 * 1. **Radar.** It carries the next half hour, and a wake in Doze gets only
 * seconds of network.
 * 2. **The model,** when it has aged out; the HTTP cache makes this nearly free
 * when nothing new is published.
 * 3. **Warnings,** on whatever forecast is now on disk.
 * 4. **The widgets,** last and always, because time moves even without network.
 */
internal object Refresh {

    enum class Outcome {
        /** Everything that was due was fetched. */
        DONE,

        /** Something was due and the network was not there; worth trying again soon. */
        OFFLINE,

        /** A service refused; asking again at once would be refused again. */
        FAILED,
    }

    /**
     * One pass at a time, since the alarm and the backup job can land together. A
     * second caller waits rather than standing down, so it times its next alarm from
     * the sweep the first pass fetched; its own pass is then nearly free.
     */
    private val running = Mutex()

    suspend fun run(context: Context): Outcome = running.withLock { pass(context) }

    private suspend fun pass(context: Context): Outcome {
        val container = (context.applicationContext as WetterApplication).container
        // From storage rather than the flow: this process may have started seconds ago.
        val location = container.selectedLocation.current()
        val cached = container.repository.cached(location)

        val started = System.nanoTime()
        fun lap() = (System.nanoTime() - started) / 1_000_000

        // 1. Radar, against the forecast in hand. With none yet there is nothing to fuse
        // it into, and the model comes first instead.
        if (cached != null) warmRadar(container, cached)
        val radarMs = lap()

        // 2. The model.
        var outcome = Outcome.DONE
        if (container.repository.needsRefresh(cached)) {
            outcome = refreshModel(container, location, radarDone = cached != null)
        }
        val modelMs = lap()

        // 3. Warnings, on every pass: a hazard is remembered once announced, so this is
        // usually a read and a lookup, and a storm entering the horizon between model
        // runs is said when it appears.
        runCatching { announceHazards(context, container, location) }
            .onFailure { log("could not check for severe weather: ${it.message}") }

        // 4. Redraw last, whatever happened above: the widget shows the next hours from
        // now.
        runCatching { Widgets.refresh(context) }
            .onFailure { log("could not redraw the widget: ${it.message}") }

        log(
            "pass $outcome: radar ${radarMs}ms, model +${modelMs - radarMs}ms, rest +${lap() - modelMs}ms",
        )
        return outcome
    }

    /** The newest sweep, collected before this pass returns. */
    private suspend fun warmRadar(container: WetterContainer, forecast: WeatherForecast) {
        runCatching {
            // Fresh, so the sweep is in hand before the widgets are drawn; a background job
            // could be frozen with the process mid-download.
            container.nowcasts.timeline(forecast, Instant.now(), fresh = true)
            container.nowcasts.prune()
        }.onFailure {
            // No radar is normal (most of the world has none), never a failed pass.
            log("radar did not refresh: ${it.message}")
        }
    }

    private suspend fun refreshModel(
        container: WetterContainer,
        location: WeatherLocation,
        radarDone: Boolean,
    ): Outcome = container.repository.refresh(location).fold(
        onSuccess = { forecast ->
            log("refreshed ${location.name}")
            if (!radarDone) warmRadar(container, forecast)
            // Verification rides on the refresh: it needs a fresh forecast to write down,
            // and a run that fetched nothing new must not record the same claim twice.
            runCatching {
                container.verification.record(forecast)
                val settled = container.verification.verify(location)
                container.verification.prune()
                if (settled > 0) log("verified $settled past predictions")
            }.onFailure {
                // Bookkeeping never fails a refresh.
                log("verification pass did not complete: ${it.message}")
            }
            Outcome.DONE
        },
        onFailure = { thrown ->
            val error = thrown.asWeatherError()
            log("could not refresh ${location.name}: $error")
            if (error.isWorthRetrying) Outcome.OFFLINE else Outcome.FAILED
        },
    )

    /**
     * Tell somebody about severe weather they have not been told about. Detection is
     * `Hazards.scan`, as for the dial's mark; this is only delivery. The record is
     * written before the notification is posted, so a failure between the two does not
     * repeat the warning.
     */
    private suspend fun announceHazards(
        context: Context,
        container: WetterContainer,
        location: WeatherLocation,
    ) {
        if (!container.preferences.current().warnings) return

        val forecast = container.repository.cached(location) ?: return
        val now = Instant.now()
        // The decade of archive the Month page keeps, so thresholds are this place's.
        // Cached for a month.
        val climate = runCatching { container.climatology.normals(location) }.getOrNull()
        val hazards = Hazards.scan(forecast, air = null, now = now, climate = climate)
        if (hazards.isEmpty()) return

        val said = container.announcedHazards.said(location)
        val due = HazardAnnouncements.due(hazards, said, forecast.location.zone, climate)
        if (due.isEmpty()) return

        container.announcedHazards.remember(location, due, now)
        HazardNotifier(context).post(due, forecast.location.zone, now)
        container.announcedHazards.prune(now)
        log("warned about " + due.joinToString { it.key })
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    private const val TAG = "Refresh"
}

/**
 * Whether a failed refresh is worth another try before the next scheduled one:
 * offline is, a provider rejecting the request is not.
 */
private val WeatherError.isWorthRetrying: Boolean
    get() = when (this) {
        is WeatherError.Offline, is WeatherError.Timeout -> true
        is WeatherError.ProviderRejected -> status >= 500
        else -> false
    }
