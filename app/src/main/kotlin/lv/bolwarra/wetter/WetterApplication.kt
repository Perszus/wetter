package lv.bolwarra.wetter

import android.app.Application
import lv.bolwarra.wetter.work.ForecastRefreshWorker

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
    }
}
