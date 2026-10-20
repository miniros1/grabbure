package com.miniros1.grabbure

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.view.View
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miniros1.grabbure.databinding.ActivitySettingsBinding

/** Camera preview and sensor choices. Publishing starts from the next screen. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var masterUri: String
    private lateinit var masterLabel: String
    private lateinit var phoneIp: String
    private lateinit var phoneName: String

    private val cameras = mutableListOf<CameraChoice>()
    private var formats = listOf<FormatChoice>()
    private var sizes = listOf<Size>()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewWanted = false
    private var opening = false
    private var reopenAfterClose = false
    private var sessionGeneration = 0

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showPreview() else binding.status.setText(R.string.camera_permission_denied)
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = locationGranted()
        if (!granted) {
            binding.publishGps.isChecked = false
            toast(getString(R.string.location_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        masterUri = intent.getStringExtra(Session.EXTRA_URI).orEmpty()
        masterLabel = intent.getStringExtra(Session.EXTRA_LABEL).orEmpty()
        phoneIp = intent.getStringExtra(Session.EXTRA_IP).orEmpty()
        phoneName = PhoneName.sanitize(intent.getStringExtra(Session.EXTRA_PHONE).orEmpty())
        binding.master.text = getString(R.string.master_line, masterLabel, masterUri)
        binding.preview.surfaceTextureListener = surfaceListener
        binding.start.setOnClickListener { startPublishing() }
        loadImuRate()
        loadMagRate()
        loadGpsOption()
        loadCameras()
    }

    override fun onStart() {
        super.onStart()
        previewWanted = true
        if (granted(Manifest.permission.CAMERA)) {
            showPreview()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        releasePreview()
        super.onStop()
    }

    private fun loadImuRate() {
        val sensors = getSystemService(SensorManager::class.java)
        val hasImu = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null ||
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        binding.imuRate.adapter = rateAdapter(GrabSettings.IMU_RATES)
        val saved = GrabSettings.load(this).imuHz
        selectWithoutCallback(binding.imuRate, GrabSettings.IMU_RATES.indexOf(saved).coerceAtLeast(0))
        binding.imuRate.isEnabled = hasImu
        binding.imuRate.onItemSelectedListener = selected {
            val hz = GrabSettings.IMU_RATES.getOrNull(binding.imuRate.selectedItemPosition) ?: return@selected
            GrabSettings.saveImuHz(this, hz)
        }
    }

    private fun loadMagRate() {
        val sensors = getSystemService(SensorManager::class.java)
        val hasMag = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) != null ||
            sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null
        binding.magRate.adapter = rateAdapter(GrabSettings.MAG_RATES)
        val saved = GrabSettings.load(this).magHz
        selectWithoutCallback(binding.magRate, GrabSettings.MAG_RATES.indexOf(saved).coerceAtLeast(0))
        binding.magRate.isEnabled = hasMag
        binding.magRate.onItemSelectedListener = selected {
            val hz = GrabSettings.MAG_RATES.getOrNull(binding.magRate.selectedItemPosition) ?: return@selected
            GrabSettings.saveMagHz(this, hz)
        }
    }

    private fun loadGpsOption() {
        val gps = GpsGrabber(this)
        val saved = GrabSettings.load(this)
        binding.publishGps.isEnabled = gps.available
        binding.publishGps.isChecked = gps.available && saved.publishGps
        binding.publishGps.setOnCheckedChangeListener { _, checked ->
            GrabSettings.saveGps(this, checked)
            if (checked && !locationGranted()) {
                locationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }

    private fun loadCameras() {
        val manager = getSystemService(CameraManager::class.java)
        cameras.clear()
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
            if (jpeg.isEmpty() && yuv.isEmpty()) continue
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> getString(R.string.camera_front)
                CameraCharacteristics.LENS_FACING_BACK -> getString(R.string.camera_back)
                else -> getString(R.string.camera_external)
            }
            cameras.add(CameraChoice(id, "$facing ($id)", jpeg, yuv))
        }
        if (cameras.isEmpty()) {
            binding.status.setText(R.string.no_cameras)
            return
        }
        val savedId = GrabSettings.load(this).cameraId
        val index = cameras.indexOfFirst { it.id == savedId }.let { if (it >= 0) it else 0 }
        binding.camera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameras.map { it.label })
        selectWithoutCallback(binding.camera, index)
        binding.camera.onItemSelectedListener = selected { refreshFormats() }
        refreshFormats()
    }

    private fun refreshFormats() {
        val camera = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return
        formats = buildList {
            if (camera.jpeg.isNotEmpty()) add(FormatChoice(getString(R.string.format_jpeg), true, camera.jpeg))
            if (camera.yuv.isNotEmpty()) add(FormatChoice(getString(R.string.format_yuv), false, camera.yuv))
        }
        val savedJpeg = GrabSettings.load(this).jpeg
        val index = formats.indexOfFirst { it.jpeg == savedJpeg }.let { if (it >= 0) it else 0 }
        binding.format.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats.map { it.label })
        selectWithoutCallback(binding.format, index)
        binding.format.onItemSelectedListener = selected { refreshSizes() }
        loadCaptureControls()
        refreshSizes()
    }

    private fun loadCaptureControls() {
        val choice = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return
        val manager = getSystemService(CameraManager::class.java)
        val saved = GrabSettings.load(this)
        val ranges = repeatingFpsRanges(manager, choice.id)
        val showFps = ranges.isNotEmpty()
        binding.fpsLabel.visibility = if (showFps) View.VISIBLE else View.GONE
        binding.fps.visibility = binding.fpsLabel.visibility
        binding.fps.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ranges.map { fpsLabel(it.lower, it.upper) },
        )
        val fpsIndex = ranges.indexOfFirst { it.lower == saved.fpsLower && it.upper == saved.fpsUpper }
            .let { if (it >= 0) it else 0 }
        selectWithoutCallback(binding.fps, fpsIndex)
        binding.fps.isEnabled = ranges.size > 1
        binding.fps.onItemSelectedListener = selected {
            val range = ranges.getOrNull(binding.fps.selectedItemPosition) ?: return@selected
            GrabSettings.saveFps(this, range.lower, range.upper)
            showPreview()
        }
        ranges.getOrNull(fpsIndex)?.let { GrabSettings.saveFps(this, it.lower, it.upper) }

        val aeModes = selectableAeModes(manager, choice.id)
        bindChoice(binding.aeLabel, binding.aeMode, aeModes.map { aeLabel(it) }, aeModes.indexOf(saved.aeMode)) {
            val mode = aeModes.getOrNull(binding.aeMode.selectedItemPosition) ?: return@bindChoice
            GrabSettings.saveAeMode(this, mode)
            showPreview()
        }
        aeModes.getOrNull(binding.aeMode.selectedItemPosition)?.let { GrabSettings.saveAeMode(this, it) }

        val steps = exposureSteps(manager, choice.id)
        val showEv = steps.size > 1
        binding.evLabel.visibility = if (showEv) View.VISIBLE else View.GONE
        binding.exposure.visibility = binding.evLabel.visibility
        binding.exposure.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            steps.map { getString(R.string.ev_step, exposureEv(manager, choice.id, it)) },
        )
        val evIndex = steps.indexOf(saved.exposureCompensation).let { if (it >= 0) it else steps.indexOf(0).coerceAtLeast(0) }
        selectWithoutCallback(binding.exposure, evIndex)
        binding.exposure.onItemSelectedListener = selected {
            val step = steps.getOrNull(binding.exposure.selectedItemPosition) ?: return@selected
            GrabSettings.saveExposureCompensation(this, step)
            showPreview()
        }
        steps.getOrNull(evIndex)?.let { GrabSettings.saveExposureCompensation(this, it) }

        val awbModes = selectableAwbModes(manager, choice.id)
        bindChoice(binding.awbLabel, binding.awbMode, awbModes.map { awbLabel(it) }, awbModes.indexOf(saved.awbMode)) {
            val mode = awbModes.getOrNull(binding.awbMode.selectedItemPosition) ?: return@bindChoice
            GrabSettings.saveAwbMode(this, mode)
            showPreview()
        }
        awbModes.getOrNull(binding.awbMode.selectedItemPosition)?.let { GrabSettings.saveAwbMode(this, it) }
    }

    private fun bindChoice(
        label: View,
        spinner: android.widget.Spinner,
        titles: List<String>,
        savedIndex: Int,
        onPick: () -> Unit,
    ) {
        val show = titles.isNotEmpty()
        label.visibility = if (show) View.VISIBLE else View.GONE
        spinner.visibility = label.visibility
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
        selectWithoutCallback(spinner, if (savedIndex >= 0) savedIndex else 0)
        spinner.isEnabled = titles.size > 1
        spinner.onItemSelectedListener = selected { onPick() }
    }

    private fun fpsLabel(lower: Int, upper: Int): String {
        return if (lower == upper) getString(R.string.fps_fixed, lower) else getString(R.string.fps_range, lower, upper)
    }

    private fun aeLabel(mode: Int): String {
        val name = when (mode) {
            CaptureRequest.CONTROL_AE_MODE_ON -> R.string.ae_on
            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH -> R.string.ae_auto_flash
            CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> R.string.ae_always_flash
            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> R.string.ae_redeye
            CaptureRequest.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> R.string.ae_external_flash
            CaptureRequest.CONTROL_AE_MODE_OFF -> R.string.ae_off
            else -> 0
        }
        return if (name != 0) getString(name) else getString(R.string.mode_code, mode)
    }

    private fun awbLabel(mode: Int): String {
        val name = when (mode) {
            CaptureRequest.CONTROL_AWB_MODE_AUTO -> R.string.awb_auto
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> R.string.awb_incandescent
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> R.string.awb_fluorescent
            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> R.string.awb_warm_fluorescent
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> R.string.awb_daylight
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> R.string.awb_cloudy
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> R.string.awb_twilight
            CaptureRequest.CONTROL_AWB_MODE_SHADE -> R.string.awb_shade
            CaptureRequest.CONTROL_AWB_MODE_OFF -> R.string.awb_off
            else -> 0
        }
        return if (name != 0) getString(name) else getString(R.string.mode_code, mode)
    }

    private fun refreshSizes() {
        val format = formats.getOrNull(binding.format.selectedItemPosition) ?: return
        sizes = format.sizes.sortedByDescending { it.width.toLong() * it.height }
        binding.resolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sizes.map { "${it.width} x ${it.height}" },
        )
        selectWithoutCallback(binding.resolution, preferredSizeIndex())
        binding.resolution.onItemSelectedListener = selected { applyCameraChoice() }
        applyCameraChoice()
    }

    private fun preferredSizeIndex(): Int {
        val saved = GrabSettings.load(this).size
        val savedIndex = sizes.indexOfFirst { it.width == saved?.width && it.height == saved.height }
        if (savedIndex >= 0 && sizeFps(sizes[savedIndex]) >= 30) return savedIndex
        val fast = sizes.indices
            .filter { sizeFps(sizes[it]) >= 30 }
            .maxWithOrNull(compareBy { sizes[it].width.toLong() * sizes[it].height })
        if (fast != null) return fast
        val video = sizes.indices
            .filter { maxOf(sizes[it].width, sizes[it].height) <= 1920 }
            .maxWithOrNull(compareBy { sizes[it].width.toLong() * sizes[it].height })
        if (video != null) return video
        return if (savedIndex >= 0) savedIndex else 0
    }

    private fun sizeFps(size: Size): Int {
        val camera = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return 0
        val format = formats.getOrNull(binding.format.selectedItemPosition) ?: return 0
        val imageFormat = if (format.jpeg) ImageFormat.JPEG else ImageFormat.YUV_420_888
        return sizeFps(getSystemService(CameraManager::class.java), camera.id, imageFormat, size)
    }

    private fun applyCameraChoice() {
        val camera = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return
        val format = formats.getOrNull(binding.format.selectedItemPosition) ?: return
        val size = sizes.getOrNull(binding.resolution.selectedItemPosition) ?: return
        GrabSettings.saveCamera(this, camera.id, format.jpeg, size)
        showPreview()
    }

    private fun showPreview() {
        if (!previewWanted) return
        val choice = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return
        val size = sizes.getOrNull(binding.resolution.selectedItemPosition) ?: return
        fitCapturePreview(binding.preview, size)
        if (!granted(Manifest.permission.CAMERA)) return
        val texture = binding.preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val device = camera
        if (device == null) {
            openDevice(choice.id)
            return
        }
        if (device.id != choice.id) {
            reopenAfterClose = true
            device.close()
            return
        }
        configureSession(device, size)
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(cameraId: String) {
        if (camera != null || opening || !previewWanted) return
        opening = true
        val handler = ensureCameraThread()
        val manager = getSystemService(CameraManager::class.java)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                opening = false
                val wanted = cameras.getOrNull(binding.camera.selectedItemPosition)?.id
                if (!previewWanted || wanted != device.id) {
                    reopenAfterClose = previewWanted && wanted != null
                    device.close()
                    return
                }
                camera = device
                val size = sizes.getOrNull(binding.resolution.selectedItemPosition) ?: return
                configureSession(device, size)
            }

            override fun onDisconnected(device: CameraDevice) {
                opening = false
                reopenAfterClose = false
                device.close()
                if (camera == device) camera = null
            }

            override fun onClosed(device: CameraDevice) {
                if (camera == device) camera = null
                if (!reopenAfterClose || !previewWanted) return
                reopenAfterClose = false
                val id = cameras.getOrNull(binding.camera.selectedItemPosition)?.id ?: return
                openDevice(id)
            }

            override fun onError(device: CameraDevice, error: Int) {
                opening = false
                reopenAfterClose = false
                device.close()
                if (camera == device) camera = null
                runOnUiThread { binding.status.text = getString(R.string.camera_error, error) }
            }
        }, handler)
    }

    private fun configureSession(device: CameraDevice, size: Size) {
        val handler = ensureCameraThread()
        val texture = binding.preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val preview = Surface(texture)
        val manager = getSystemService(CameraManager::class.java)
        val fpsRanges = repeatingFpsRanges(manager, device.id)
        val generation = ++sessionGeneration
        session?.close()
        try {
            device.createCaptureSession(listOf(preview), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(capture: CameraCaptureSession) {
                    if (generation != sessionGeneration || camera != device || !previewWanted) {
                        capture.close()
                        return
                    }
                    session = capture
                    try {
                        setRepeatingPreview(
                            device,
                            capture,
                            listOf(preview),
                            handler,
                            resolveCaptureTune(this@SettingsActivity, manager, device.id),
                            fpsRanges,
                        )
                    } catch (_: RuntimeException) {
                        runOnUiThread { binding.status.setText(R.string.camera_configure_failed) }
                        return
                    }
                    runOnUiThread { binding.status.text = "" }
                }

                override fun onConfigureFailed(capture: CameraCaptureSession) {
                    runOnUiThread { binding.status.setText(R.string.camera_configure_failed) }
                }
            }, handler)
        } catch (_: RuntimeException) {
            runOnUiThread { binding.status.setText(R.string.camera_configure_failed) }
        }
    }

    private fun releasePreview() {
        previewWanted = false
        opening = false
        reopenAfterClose = false
        session?.close()
        session = null
        camera?.close()
        camera = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun startPublishing() {
        if (phoneIp.isBlank()) {
            toast(getString(R.string.no_wifi_address))
            return
        }
        releasePreview()
        startActivity(
            Intent(this, CameraActivity::class.java)
                .putExtra(Session.EXTRA_URI, masterUri)
                .putExtra(Session.EXTRA_LABEL, masterLabel)
                .putExtra(Session.EXTRA_IP, phoneIp)
                .putExtra(Session.EXTRA_PHONE, phoneName),
        )
    }

    private fun ensureCameraThread(): Handler {
        val existing = cameraHandler
        if (existing != null) return existing
        val thread = HandlerThread("settings-preview")
        thread.start()
        cameraThread = thread
        return Handler(thread.looper).also { cameraHandler = it }
    }

    private fun rateAdapter(rates: IntArray): ArrayAdapter<String> {
        return ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            rates.map { getString(R.string.mag_rate_hz, it) },
        )
    }

    private fun selectWithoutCallback(spinner: android.widget.Spinner, index: Int) {
        spinner.onItemSelectedListener = null
        spinner.setSelection(index)
    }

    private fun locationGranted(): Boolean {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun selected(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
            block()
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            showPreview()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private data class CameraChoice(
        val id: String,
        val label: String,
        val jpeg: List<Size>,
        val yuv: List<Size>,
    )

    private data class FormatChoice(
        val label: String,
        val jpeg: Boolean,
        val sizes: List<Size>,
    )
}
