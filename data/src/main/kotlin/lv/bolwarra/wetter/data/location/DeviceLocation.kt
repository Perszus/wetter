// SPDX-FileCopyrightText: 2026 Roberts Kains
// SPDX-License-Identifier: GPL-3.0-or-later

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
 * The framework's `LocationManager`, not a fused provider from a proprietary
 * services library (docs/decisions.md), so it also works on phones without them.
 * A town-level answer is all that is needed.
 *
 * Asked once, on demand, and the listener removed the moment it answers: nothing
 * here subscribes to updates or runs in the background.
 */
class DeviceLocation internal constructor(private val context: Context) {

    /** Whether the reader has granted a location permission. */
    val permitted: Boolean
        get() = PERMISSIONS.any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * One fix, or null for every way it can fail: in each case the map stays put and
     * the reader taps where they are.
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
         * Network first, then GPS: the network provider answers in about a second to
         * within a few hundred metres, well inside any forecast grid, while GPS can take
         * minutes indoors.
         */
        val PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
