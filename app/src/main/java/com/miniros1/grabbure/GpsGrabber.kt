package com.miniros1.grabbure

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** Publishes location fixes on `<phone>/gps` when the sensor screen asks for it. */
class GpsGrabber(context: Context) {
    private val appContext = context.applicationContext
    private val locations = appContext.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var latest: Location? = null
    private var lastPublishMs = 0L
    private val published = AtomicInteger()

    val available: Boolean = providers().isNotEmpty()

    fun samples(): Int = published.get()

    fun advertise(): String? {
        if (!available || !permitted()) return null
        return NativeBridge.startGps()
    }

    @SuppressLint("MissingPermission")
    fun listen() {
        if (listening || !available || !permitted()) return
        listening = true
        for (provider in providers()) {
            if (!enabled(provider)) continue
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val request = LocationRequest.Builder(1_000L)
                        .setMinUpdateIntervalMillis(200L)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .build()
                    locations.requestLocationUpdates(provider, request, appContext.mainExecutor, listener)
                } else {
                    locations.requestLocationUpdates(provider, 0L, 0f, listener)
                }
                locations.getLastKnownLocation(provider)?.let { update(it) }
            } catch (_: RuntimeException) {
            }
        }
        main.removeCallbacks(keepalive)
        main.post(keepalive)
    }

    fun stop() {
        listening = false
        main.removeCallbacks(keepalive)
        locations.removeUpdates(listener)
        latest = null
        try {
            NativeBridge.stopGps()
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            update(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    /** Republish the newest fix once a second when the provider itself stays quiet. */
    private val keepalive = object : Runnable {
        override fun run() {
            if (!listening) return
            val fix = latest
            if (fix != null && SystemClock.elapsedRealtime() - lastPublishMs >= 900L) {
                publish(fix)
            }
            main.postDelayed(this, 1_000L)
        }
    }

    private fun update(location: Location) {
        val current = latest
        if (current == null || location.time >= current.time) {
            latest = location
        }
        publish(latest ?: location)
    }

    private fun publish(location: Location) {
        val horizontal = if (location.hasAccuracy()) location.accuracy.toDouble() else -1.0
        val vertical = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else -1.0
        val sent = NativeBridge.publishNavSat(
            location.latitude,
            location.longitude,
            if (location.hasAltitude()) location.altitude else 0.0,
            location.hasAltitude(),
            horizontal,
            vertical,
            location.elapsedRealtimeNanos,
        )
        if (sent) {
            lastPublishMs = SystemClock.elapsedRealtime()
            published.incrementAndGet()
        }
    }

    private fun providers(): List<String> {
        val names = ArrayList<String>()
        if (locations.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            names.add(LocationManager.GPS_PROVIDER)
        }
        if (locations.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            names.add(LocationManager.NETWORK_PROVIDER)
        }
        if (Build.VERSION.SDK_INT >= 31 && locations.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
            names.add(LocationManager.FUSED_PROVIDER)
        }
        return names
    }

    private fun enabled(provider: String): Boolean {
        return try {
            locations.isProviderEnabled(provider)
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun permitted(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
