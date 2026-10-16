// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

package lv.bolwarra.wetter

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lv.bolwarra.wetter.work.ForecastRefreshWorker
import lv.bolwarra.wetter.work.Heartbeat

/**
 * Owns the object graph for as long as the process lives.
 *
 * The container is created lazily inside itself, so an Application constructed
 * only to run a background job does not open an HTTP client it will never use.
 */
class WetterApplication : Application() {

    val container: WetterContainer by lazy { WetterContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Uniquely named, so calling this on every launch cannot stack up
        // duplicates. It is cheap and it is the only place the schedule is
        // guaranteed to be re-asserted after the app is updated.
        ForecastRefreshWorker.schedule(this)
        // And the clock that actually keeps the app fresh. A no-op when a wake is
        // already pending, so a process started for any reason cannot postpone it
        // - and in its own scope rather than the container's, so that check does
        // not build the object graph the comment above keeps lazy.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { Heartbeat.ensure(this@WetterApplication) }
        }
    }
}
