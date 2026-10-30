package com.miniros1.grabbure

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicInteger

/** Publishes gyroscope, accelerometer, and magnetometer once the node is up. */
class MotionGrabber(
    context: Context,
) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        ?: sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val temperature = pickTemperature(sensors)

    private var magPeriodUs = 10_000
    private var imuPeriodUs = 5_000
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false
    private val imuPublished = AtomicInteger()
    private val magPublished = AtomicInteger()
    private val tempPublished = AtomicInteger()

    fun imuSamples(): Int = imuPublished.get()

    fun magnetometerSamples(): Int = magPublished.get()

    fun temperatureSamples(): Int = tempPublished.get()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!listening) return
            val sensor = temperature
            if (sensor != null && event.sensor.type == sensor.type) {
                if (event.values.isNotEmpty() &&
                    NativeBridge.publishTemperature(event.values[0].toDouble(), event.timestamp)
                ) {
                    tempPublished.incrementAndGet()
                }
                return
            }
            if (event.values.size < 3) return
            val x = event.values[0].toDouble()
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    if (NativeBridge.publishGyro(x, y, z, event.timestamp)) imuPublished.incrementAndGet()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    if (NativeBridge.publishAccel(x, y, z, event.timestamp)) imuPublished.incrementAndGet()
                }
                magnetometer?.type -> {
                    if (NativeBridge.publishMagneticField(x, y, z, event.timestamp)) magPublished.incrementAndGet()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Advertise topics. The node must already be started. */
    fun advertise(): String? {
        val publishImu = gyro != null || accel != null
        val publishMag = magnetometer != null
        val imuError = if (publishImu) NativeBridge.startImu(temperature != null) else null
        val magError = if (imuError == null && publishMag) NativeBridge.startMagnetometer() else null
        if (imuError != null || magError != null) {
            NativeBridge.stopImu()
            NativeBridge.stopMagnetometer()
            return imuError ?: magError
        }
        return null
    }

    fun listen() {
        if (listening) return
        listening = true
        val callbackHandler = ensureThread()
        registerImu(callbackHandler)
        registerMagnetometer(callbackHandler)
        temperature?.let {
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, callbackHandler)
        }
    }

    fun stop() {
        listening = false
        sensors.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
        handler = null
        try {
            NativeBridge.stopImu()
            NativeBridge.stopMagnetometer()
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    val hasImu: Boolean
        get() = gyro != null || accel != null

    val hasMagnetometer: Boolean
        get() = magnetometer != null

    val hasTemperature: Boolean
        get() = temperature != null

    /** Applies on the next sample interval. A running gyroscope and accelerometer are re-registered. */
    fun setImuRateHz(hz: Int) {
        if (hz <= 0) return
        val period = 1_000_000 / hz
        if (period == imuPeriodUs) return
        imuPeriodUs = period
        if (!listening) return
        val callbackHandler = handler ?: return
        gyro?.let { sensors.unregisterListener(listener, it) }
        accel?.let { sensors.unregisterListener(listener, it) }
        registerImu(callbackHandler)
    }

    /** Applies on the next sample interval. A running magnetometer is re-registered. */
    fun setMagnetometerRateHz(hz: Int) {
        if (hz <= 0) return
        val period = 1_000_000 / hz
        if (period == magPeriodUs) return
        magPeriodUs = period
        if (!listening) return
        val callbackHandler = handler ?: return
        magnetometer?.let { sensors.unregisterListener(listener, it) }
        registerMagnetometer(callbackHandler)
    }

    private fun registerImu(callbackHandler: Handler) {
        gyro?.let { sensors.registerListener(listener, it, periodUs(it, imuPeriodUs), callbackHandler) }
        accel?.let { sensors.registerListener(listener, it, periodUs(it, imuPeriodUs), callbackHandler) }
    }

    private fun registerMagnetometer(callbackHandler: Handler) {
        val sensor = magnetometer ?: return
        sensors.registerListener(listener, sensor, periodUs(sensor, magPeriodUs), callbackHandler)
    }

    /** Android rounds the period to a supported rate and will not go faster than [Sensor.getMinDelay]. */
    private fun periodUs(sensor: Sensor, targetUs: Int): Int {
        val fastest = sensor.minDelay
        if (fastest <= 0) return targetUs
        return fastest.coerceAtLeast(targetUs)
    }

    private fun pickTemperature(manager: SensorManager): Sensor? {
        val temps = manager.getSensorList(Sensor.TYPE_ALL).filter { sensor ->
            val type = sensor.stringType.lowercase()
            type.contains("temp") ||
                sensor.type == Sensor.TYPE_AMBIENT_TEMPERATURE ||
                sensor.type == Sensor.TYPE_TEMPERATURE
        }
        return temps.firstOrNull { sensor ->
            val blob = (sensor.stringType + " " + sensor.name).lowercase()
            blob.contains("gyro") || blob.contains("imu") || blob.contains("icm") || blob.contains("bmi")
        } ?: temps.firstOrNull { it.type == Sensor.TYPE_TEMPERATURE }
            ?: temps.firstOrNull()
    }

    private fun ensureThread(): Handler {
        val existing = handler
        if (existing != null) return existing
        val created = HandlerThread("grabbure-sensors")
        created.start()
        thread = created
        return Handler(created.looper).also { handler = it }
    }
}
