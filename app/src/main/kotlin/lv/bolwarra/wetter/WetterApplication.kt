package lv.bolwarra.wetter

import android.app.Application

/**
 * Owns the object graph for as long as the process lives.
 *
 * The container is created lazily inside itself, so an Application that is only
 * being constructed for a widget update or a background job does not open an
 * HTTP client it will never use.
 */
class WetterApplication : Application() {

    val container: WetterContainer by lazy { WetterContainer() }
}
