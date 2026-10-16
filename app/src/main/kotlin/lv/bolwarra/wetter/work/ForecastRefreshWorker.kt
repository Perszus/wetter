// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import lv.bolwarra.wetter.WetterApplication

/**
 * The net under the [Heartbeat], for the ways an alarm can be lost (an OEM battery
 * manager, a force-stop) and for catching up when the network returns. It runs the
 * same [Refresh] pass and re-arms the heartbeat every time.
 */
class ForecastRefreshWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val outcome = Refresh.run(applicationContext)
        runCatching { Heartbeat.ensure(applicationContext) }
        // The scoring reach-back, here because a job has minutes and nothing is
        // waiting on it.
        runCatching {
            val container = (applicationContext as WetterApplication).container
            container.nowcasts.settleOutstanding(container.selectedLocation.current())
        }
        // A retry costs a wake-up, so it is only worth one when the failure was
        // about this moment rather than about this request.
        return if (outcome == Refresh.Outcome.OFFLINE) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "forecast-refresh"

        private const val CATCH_UP_NAME = "forecast-catch-up"

        /**
         * A quarter of an hour, WorkManager's floor - and only a net. The
         * heartbeat keeps the real cadence; this re-arms it if it was lost.
         */
        private const val INTERVAL_MINUTES = 15L

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
                        // Not requiring a charged battery: somebody
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

        /**
         * One pass as soon as there is a network, for a wake that found none.
         *
         * KEEP, so a phone that stays offline through several wakes queues one
         * catch-up rather than a pile of them.
         */
        fun catchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<ForecastRefreshWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CATCH_UP_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
