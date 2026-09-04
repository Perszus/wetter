package lv.bolwarra.wetter.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import lv.bolwarra.wetter.BuildConfig
import lv.bolwarra.wetter.WetterApplication
import lv.bolwarra.wetter.domain.model.WeatherError
import lv.bolwarra.wetter.domain.provider.asWeatherError
import lv.bolwarra.wetter.widget.RainWidget

/**
 * Keeps the cached forecast fresh while nobody is looking.
 *
 * The worker exists so that opening the app, or a home-screen widget drawing
 * itself, finds a recent forecast already on disk rather than an empty screen
 * and a spinner. It writes to the same cache the UI reads; there is no second
 * path for data to travel.
 *
 * It lives in `:app` rather than `:data` because it needs the object graph, and
 * `:data` cannot see the application. Scheduling when to refresh is an
 * application decision anyway; `:data` only knows how.
 */
class ForecastRefreshWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as WetterApplication).container

        // Read the place from storage rather than from the flow: this process may
        // have started seconds ago and never observed it.
        val location = container.selectedLocation.current()

        val outcome = container.repository.refresh(location)
        outcome.onSuccess { forecast ->
            log("refreshed ${location.name}")

            // Redraw the home screen while the new forecast is in hand. This is
            // the only thing that changes what the widget should say, which is
            // why the widget asks for no schedule of its own.
            RainWidget.refresh(applicationContext)
            // The verification loop rides along on the refresh rather than
            // having a schedule of its own. It needs exactly what this job
            // already has - a fresh forecast to write down, and a wake-up with
            // network - and a second periodic job competing for the same
            // wake-ups would cost battery to do the same work less often.
            runCatching {
                // Warm the radar while there is already a wake-up and a network.
                // Without this the projection kept on disk is only ever as fresh
                // as the last time somebody opened the app, which is exactly the
                // moment it is least useful.
                container.nowcasts.timeline(forecast, java.time.Instant.now())
                container.nowcasts.prune()

                container.verification.record(forecast)
                val settled = container.verification.verify(location)
                container.verification.prune()
                if (settled > 0) log("verified $settled past predictions")
            }.onFailure {
                // Never fail a refresh over bookkeeping. The forecast is on
                // disk and the screen is correct; an unverified record simply
                // waits for the next run.
                log("verification pass did not complete: ${it.message}")
            }
            return Result.success()
        }

        val error = outcome.exceptionOrNull()?.asWeatherError() ?: WeatherError.Unknown()
        log("could not refresh ${location.name}: $error")

        // A retry costs a wake-up, so it is only worth asking for one when the
        // failure was about this moment rather than about this request. There is
        // another scheduled run along shortly in either case.
        return if (error.isWorthRetrying) Result.retry() else Result.success()
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "ForecastRefresh"
        private const val WORK_NAME = "forecast-refresh"

        /**
         * Half an hour, which is a floor and not a promise.
         *
         * WorkManager will not run periodic work more often than every fifteen
         * minutes, and under Doze and App Standby an idle device will stretch
         * this considerably. Both weather services publish roughly hourly, so
         * asking for less than this would buy nothing anyway.
         */
        private const val INTERVAL_MINUTES = 30L

        /**
         * Starts the schedule, or updates it if the interval has changed.
         *
         * Safe to call on every launch: the work is uniquely named, so this
         * cannot stack up duplicates. WorkManager restores its own schedule
         * after a reboot, which is why there is no boot receiver here and no
         * RECEIVE_BOOT_COMPLETED permission to justify.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ForecastRefreshWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        // Waking the radio with no connection to use it is the
                        // one guaranteed way to spend battery for nothing.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        // Deliberately not requiring a charged battery: somebody
                        // on 12% still wants to know whether to take a coat, and
                        // one small request every half hour is not what is
                        // draining their phone.
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE rather than KEEP, so a change to the interval reaches
                // devices that already have the old one scheduled.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

/**
 * Whether a failed refresh is worth another wake-up before the next scheduled
 * run.
 *
 * Being offline is: connectivity comes back and the constraint will fire again.
 * A provider rejecting the request is not — it will reject the next one
 * identically, and the ranking already routes around a service that is unwell.
 */
private val WeatherError.isWorthRetrying: Boolean
    get() = when (this) {
        is WeatherError.Offline, is WeatherError.Timeout -> true
        is WeatherError.ProviderRejected -> status >= 500
        else -> false
    }
