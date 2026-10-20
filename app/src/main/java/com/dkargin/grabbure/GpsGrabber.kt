package com.dkargin.grabbure

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** Publishes GPS fixes on `<phone>/gps` when the sensor screen asks for it. */
class GpsGrabber(context: Context) {
    private val appContext = context.applicationContext
    private val locations = appContext.getSystemService(LocationManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false
    private val published = AtomicInteger()

    val available: Boolean = locations.allProviders.contains(LocationManager.GPS_PROVIDER)

    fun samples(): Int = published.get()

    fun advertise(): String? {
        if (!available || !permitted()) return null
        return NativeBridge.startGps()
    }

    @SuppressLint("MissingPermission")
    fun listen() {
        if (listening || !available || !permitted()) return
        listening = true
        val callbackHandler = ensureThread()
        locations.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            0L,
            0f,
            listener,
            callbackHandler.looper,
        )
    }

    fun stop() {
        listening = false
        locations.removeUpdates(listener)
        thread?.quitSafely()
        thread = null
        handler = null
        try {
            NativeBridge.stopGps()
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publish(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
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
        )
        if (sent) published.incrementAndGet()
    }

    private fun permitted(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureThread(): Handler {
        val existing = handler
        if (existing != null) return existing
        val created = HandlerThread("grabbure-gps")
        created.start()
        thread = created
        return Handler(created.looper).also { handler = it }
    }
}
