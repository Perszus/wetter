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
 * One background pass, whoever woke the app to do it.
 *
 * Two things wake it - the [Heartbeat] alarm, timed to the radar, and the
 * periodic job that backs it up - and they used to be two copies of this with
 * two opinions about order. Now there is one pass, and the order is the
 * app's priorities written down:
 *
 * 1. **Radar.** The next half hour is what people open a weather app for, and
 *    radar is what carries it. A wake-up in Doze gets seconds of network, so the
 *    part that matters most goes first rather than after a forecast download.
 * 2. **The model,** when it has aged out - which the HTTP cache makes nearly free
 *    when nothing new has been published.
 * 3. **Warnings,** on whatever forecast is now on disk.
 * 4. **The widgets,** last and always, because time moves even when the network
 *    does not.
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
     * One pass at a time. The alarm and the backup job can land together, and two
     * passes would ask every service twice for the same answer.
     *
     * A second caller waits rather than standing down: the wake that arrives
     * mid-pass has to time its next alarm from the sweep that pass is fetching,
     * and standing down left it timing from nothing. Its own pass afterwards is
     * nearly free - everything it would fetch was just fetched.
     */
    private val running = Mutex()

    suspend fun run(context: Context): Outcome = running.withLock { pass(context) }

    private suspend fun pass(context: Context): Outcome {
        val container = (context.applicationContext as WetterApplication).container
        // From storage rather than the flow: this process may have started
        // seconds ago and never observed it.
        val location = container.selectedLocation.current()
        val cached = container.repository.cached(location)

        val started = System.nanoTime()
        fun lap() = (System.nanoTime() - started) / 1_000_000

        // 1. Radar, against the forecast in hand. With none yet there is nothing
        //    to fuse it into, and the model below comes first instead.
        if (cached != null) warmRadar(container, cached)
        val radarMs = lap()

        // 2. The model.
        var outcome = Outcome.DONE
        if (container.repository.needsRefresh(cached)) {
            outcome = refreshModel(container, location, radarDone = cached != null)
        }
        val modelMs = lap()

        // 3. Warnings, on every pass rather than only on the ones that fetched a
        //    forecast. A hazard is announced once and then remembered, so this is
        //    a read and a set lookup most of the time - and a storm that entered
        //    the horizon between two model runs is said when it appears.
        runCatching { announceHazards(context, container, location) }
            .onFailure { log("could not check for severe weather: ${it.message}") }

        // 4. Redraw last, and whatever happened above: the widget is a picture of
        //    the next four hours *from now*, and now moves without the network.
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
            // Fresh, so the sweep is in hand before the widgets are drawn. Handed
            // to a background scope instead, the process could be frozen with the
            // tiles half downloaded.
            container.nowcasts.timeline(forecast, Instant.now(), fresh = true)
            container.nowcasts.prune()
        }.onFailure {
            // No radar is a normal condition - most of the world has none - and
            // never a reason to call the pass a failure.
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
            // The verification loop rides on the refresh rather than keeping a
            // schedule of its own: it needs exactly a fresh forecast to write
            // down, and a run that fetched nothing new must not record the same
            // claim twice.
            runCatching {
                container.verification.record(forecast)
                val settled = container.verification.verify(location)
                container.verification.prune()
                if (settled > 0) log("verified $settled past predictions")
            }.onFailure {
                // Never fail a refresh over bookkeeping. The forecast is on disk.
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
     * Tell somebody about severe weather they have not been told about.
     *
     * Detection is `Hazards.scan`, the same call the amber mark on the dial is
     * drawn from, so the phone and the screen can never disagree. What happens
     * here is only delivery - and the record is written *before* the
     * notification is posted, so a phone that fails between the two does not
     * repeat the warning at every wake.
     */
    private suspend fun announceHazards(
        context: Context,
        container: WetterContainer,
        location: WeatherLocation,
    ) {
        if (!container.preferences.current().warnings) return

        val forecast = container.repository.cached(location) ?: return
        val now = Instant.now()
        // The same decade of archive the Month page keeps, so the thresholds are
        // what this place does. Cached for a month: a disk read on most passes.
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
 * Whether a failed refresh is worth another try before the next scheduled one.
 *
 * Being offline is: connectivity comes back. A provider rejecting the request is
 * not - it will reject the next one identically, and the ranking already routes
 * around a service that is unwell.
 */
private val WeatherError.isWorthRetrying: Boolean
    get() = when (this) {
        is WeatherError.Offline, is WeatherError.Timeout -> true
        is WeatherError.ProviderRejected -> status >= 500
        else -> false
    }
