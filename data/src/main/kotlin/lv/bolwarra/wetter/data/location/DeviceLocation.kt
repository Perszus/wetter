package lv.bolwarra.wetter.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import lv.bolwarra.wetter.domain.location.Coordinates

/**
 * Where the phone thinks it is.
 *
 * ### The framework's own location, not Google's
 *
 * `LocationManager`, not the fused provider. docs/decisions.md rules out Play
 * Services entirely, and this is the one place that rule costs something real:
 * the fused provider blends sensors, is quicker to a first answer, and is what
 * every other weather app uses. The framework manager is what is left, and it is
 * enough — the question being asked is which town this is, not which side of the
 * street.
 *
 * It is also the only version that works at all on a phone with no Google
 * services, which is a large share of anybody installing from F-Droid.
 *
 * ### It is asked once and never watched
 *
 * A single fix, on demand, when somebody presses a button, and the listener is
 * removed the moment it answers. Nothing here subscribes to updates and nothing
 * runs in the background: the app has no use for where you are except at the
 * instant you ask it to put a pin there, and a weather app that quietly follows
 * you around is the thing this one is written not to be.
 *
 * ### No `LocationManagerCompat`
 *
 * The compat class would collapse the two branches below into one call, and it
 * lives in `androidx.core` — which `:data` does not otherwise depend on, and
 * which brings a profile installer with it. Fifteen lines of version check is
 * the cheaper of the two.
 */
class DeviceLocation internal constructor(private val context: Context) {

    /** Whether the reader has granted a location permission. */
    val permitted: Boolean
        get() = PERMISSIONS.any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * One fix, or null.
     *
     * Null covers every way this can fail — permission refused, location
     * switched off, no provider enabled, a phone that has not managed a fix yet
     * — and they are deliberately not distinguished. Every one of them leads to
     * the same place: the map stays where it was and the reader taps where they
     * know they are.
     */
    suspend fun current(): Coordinates? {
        if (!permitted) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        val provider = PROVIDERS.firstOrNull { candidate ->
            runCatching { manager.isProviderEnabled(candidate) }.getOrDefault(false)
        } ?: return null

        return runCatching { fix(manager, provider) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fix(manager: LocationManager, provider: String): Coordinates? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, { it.run() }) { location ->
                    continuation.resume(location?.toCoordinates())
                }
                return@suspendCancellableCoroutine
            }

            // Before API 30 there is no single-shot request that reports failure,
            // so this is an ordinary subscription that unsubscribes itself on the
            // first answer.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location.toCoordinates())
                }

                /** Required on the older platform, and nothing to do here. */
                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(null)
                }

                @Deprecated("Required by the pre-30 interface", ReplaceWith(""))
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: android.os.Bundle?,
                ) = Unit
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }

    private fun Location.toCoordinates() = Coordinates(latitude, longitude)

    private companion object {
        val PERMISSIONS = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        /**
         * Network first, then GPS.
         *
         * The usual order is the other way round and it is wrong for this. GPS is
         * precise and can take half a minute outdoors and forever indoors; the
         * network provider answers in about a second to within a few hundred
         * metres, which is well inside the grid any forecast model resolves. The
         * reader can nudge the pin if they care, and they cannot nudge a spinner.
         */
        val PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
