package com.miniros1.grabbure

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Size

/** Choices made on the settings screen and read when publishing starts. */
object GrabSettings {
    private const val PREFS = "grabbure_camera"
    private const val KEY_CAMERA = "camera_id"
    private const val KEY_JPEG = "jpeg"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_IMU_HZ = "imu_hz"
    private const val KEY_MAG_HZ = "mag_hz"
    private const val KEY_GPS = "gps"
    private const val KEY_FPS_LOWER = "fps_lower"
    private const val KEY_FPS_UPPER = "fps_upper"
    private const val KEY_AE = "ae_mode"
    private const val KEY_AWB = "awb_mode"
    private const val KEY_EV = "exposure_compensation"

    val IMU_RATES = intArrayOf(200, 100)
    val MAG_RATES = intArrayOf(100, 50)

    data class Saved(
        val cameraId: String?,
        val jpeg: Boolean,
        val size: Size?,
        val imuHz: Int,
        val magHz: Int,
        val publishGps: Boolean,
        val fpsLower: Int,
        val fpsUpper: Int,
        val aeMode: Int,
        val awbMode: Int,
        val exposureCompensation: Int,
    )

    fun load(context: Context): Saved {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val width = prefs.getInt(KEY_WIDTH, -1)
        val height = prefs.getInt(KEY_HEIGHT, -1)
        val imuHz = prefs.getInt(KEY_IMU_HZ, IMU_RATES.first())
        val magHz = prefs.getInt(KEY_MAG_HZ, MAG_RATES.first())
        return Saved(
            cameraId = prefs.getString(KEY_CAMERA, null),
            jpeg = prefs.getBoolean(KEY_JPEG, true),
            size = if (width > 0 && height > 0) Size(width, height) else null,
            imuHz = if (imuHz in IMU_RATES) imuHz else IMU_RATES.first(),
            magHz = if (magHz in MAG_RATES) magHz else MAG_RATES.first(),
            publishGps = prefs.getBoolean(KEY_GPS, false),
            fpsLower = prefs.getInt(KEY_FPS_LOWER, -1),
            fpsUpper = prefs.getInt(KEY_FPS_UPPER, -1),
            aeMode = prefs.getInt(KEY_AE, CaptureRequest.CONTROL_AE_MODE_ON),
            awbMode = prefs.getInt(KEY_AWB, CaptureRequest.CONTROL_AWB_MODE_AUTO),
            exposureCompensation = prefs.getInt(KEY_EV, 0),
        )
    }

    fun saveCamera(context: Context, cameraId: String, jpeg: Boolean, size: Size) {
        prefs(context).edit()
            .putString(KEY_CAMERA, cameraId)
            .putBoolean(KEY_JPEG, jpeg)
            .putInt(KEY_WIDTH, size.width)
            .putInt(KEY_HEIGHT, size.height)
            .apply()
    }

    fun saveImuHz(context: Context, hz: Int) {
        prefs(context).edit().putInt(KEY_IMU_HZ, hz).apply()
    }

    fun saveMagHz(context: Context, hz: Int) {
        prefs(context).edit().putInt(KEY_MAG_HZ, hz).apply()
    }

    fun saveGps(context: Context, publish: Boolean) {
        prefs(context).edit().putBoolean(KEY_GPS, publish).apply()
    }

    fun saveFps(context: Context, lower: Int, upper: Int) {
        prefs(context).edit().putInt(KEY_FPS_LOWER, lower).putInt(KEY_FPS_UPPER, upper).apply()
    }

    fun saveAeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_AE, mode).apply()
    }

    fun saveAwbMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_AWB, mode).apply()
    }

    fun saveExposureCompensation(context: Context, stepIndex: Int) {
        prefs(context).edit().putInt(KEY_EV, stepIndex).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object Session {
    const val EXTRA_URI = "master_uri"
    const val EXTRA_LABEL = "master_label"
    const val EXTRA_IP = "phone_ip"
    const val EXTRA_PHONE = "phone_name"
}
