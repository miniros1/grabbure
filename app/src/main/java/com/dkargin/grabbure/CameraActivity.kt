package com.dkargin.grabbure

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dkargin.grabbure.databinding.ActivityCameraBinding
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
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
    private var reader: ImageReader? = null
    private val publishExecutor = Executors.newSingleThreadExecutor()
    private val publishing = AtomicBoolean(false)
    private val frames = AtomicInteger()
    private val dropped = AtomicInteger()
    @Volatile
    private var running = false
    private var pendingAutoStart = true
    private var includeCameraOnGrant = false
    private var permissionStartsSession = false
    private lateinit var motion: MotionGrabber
    private lateinit var gps: GpsGrabber
    private val rateHandler = Handler(Looper.getMainLooper())
    private var lastRateTick = 0L
    private var lastFrames = 0
    private var lastImu = 0
    private var lastMag = 0
    private var lastTemp = 0
    private var lastGps = 0
    private val rateTick = object : Runnable {
        override fun run() {
            if (!running) return
            updateRates()
            rateHandler.postDelayed(this, 500)
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cameraGranted = granted(Manifest.permission.CAMERA)
        val locationGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissionStartsSession && includeCameraOnGrant && !cameraGranted) {
            toast(getString(R.string.camera_permission_denied))
        }
        if (publishGpsChecked() && !locationGranted) {
            toast(getString(R.string.location_permission_denied))
            binding.publishGps.isChecked = false
        }
        if (permissionStartsSession) {
            begin(includeCameraOnGrant && cameraGranted)
        } else if (locationGranted && running && publishGpsChecked()) {
            startGpsPublishing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        masterUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        masterLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        phoneIp = intent.getStringExtra(EXTRA_IP).orEmpty()
        phoneName = PhoneName.sanitize(intent.getStringExtra(EXTRA_PHONE).orEmpty())
        binding.master.text = getString(R.string.master_line, masterLabel, masterUri)
        binding.preview.surfaceTextureListener = surfaceListener
        motion = MotionGrabber(this)
        gps = GpsGrabber(this)
        binding.details.setOnClickListener {
            val show = binding.topics.visibility != View.VISIBLE
            binding.topics.visibility = if (show) View.VISIBLE else View.GONE
            binding.details.setText(if (show) R.string.hide_details else R.string.details)
            if (show) refreshTopics()
        }
        binding.start.setOnClickListener {
            if (running) stopCapture() else requestStart()
        }
        loadMagRate()
        loadGpsOption()
        loadCameras()
    }

    override fun onDestroy() {
        stopCapture()
        publishExecutor.shutdown()
        super.onDestroy()
    }

    private fun loadGpsOption() {
        binding.publishGps.isEnabled = gps.available
        binding.publishGps.isChecked = gps.available && prefs().getBoolean(KEY_GPS, false)
        binding.publishGps.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_GPS, checked).apply()
            if (!checked) {
                gps.stop()
                return@setOnCheckedChangeListener
            }
            if (!locationGranted()) {
                permissionStartsSession = false
                permissions.launch(locationPermissions())
                return@setOnCheckedChangeListener
            }
            if (running) startGpsPublishing()
        }
    }

    private fun publishGpsChecked(): Boolean = binding.publishGps.isChecked

    private fun locationGranted(): Boolean {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun locationPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private fun startGpsPublishing() {
        val error = gps.advertise()
        if (error != null) {
            binding.status.text = error
            AppLog.line(error)
            return
        }
        gps.listen()
    }

    private fun loadMagRate() {
        binding.magRate.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            MAG_RATES.map { getString(R.string.mag_rate_hz, it) },
        )
        val saved = prefs().getInt(KEY_MAG_HZ, MAG_RATES.first())
        val index = MAG_RATES.indexOf(saved).let { if (it >= 0) it else 0 }
        binding.magRate.setSelection(index)
        binding.magRate.isEnabled = motion.hasMagnetometer
        motion.setMagnetometerRateHz(MAG_RATES[index])
        binding.magRate.onItemSelectedListener = selected {
            val hz = MAG_RATES.getOrNull(binding.magRate.selectedItemPosition) ?: return@selected
            prefs().edit().putInt(KEY_MAG_HZ, hz).apply()
            motion.setMagnetometerRateHz(hz)
        }
    }

    private fun loadCameras() {
        val manager = getSystemService(CameraManager::class.java)
        cameras.clear()
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val jpeg = map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty()
            val yuv = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList().orEmpty()
            if (jpeg.isEmpty() && yuv.isEmpty()) continue
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> getString(R.string.camera_front)
                CameraCharacteristics.LENS_FACING_BACK -> getString(R.string.camera_back)
                else -> getString(R.string.camera_external)
            }
            cameras.add(CameraChoice(id, "$facing ($id)", jpeg, yuv))
        }
        if (cameras.isEmpty()) {
            binding.status.text = getString(R.string.no_cameras)
            scheduleAutoStart()
            return
        }
        binding.camera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameras.map { it.label })
        binding.camera.setSelection(savedCameraIndex())
        binding.camera.onItemSelectedListener = selected { refreshFormats() }
        refreshFormats()
    }

    private fun refreshFormats() {
        val camera = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return
        formats = buildList {
            if (camera.jpeg.isNotEmpty()) add(FormatChoice(getString(R.string.format_jpeg), true, camera.jpeg))
            if (camera.yuv.isNotEmpty()) add(FormatChoice(getString(R.string.format_yuv), false, camera.yuv))
        }
        binding.format.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formats.map { it.label })
        binding.format.setSelection(savedFormatIndex())
        binding.format.onItemSelectedListener = selected { refreshSizes() }
        refreshSizes()
    }

    private fun refreshSizes() {
        val format = formats.getOrNull(binding.format.selectedItemPosition) ?: return
        sizes = format.sizes.sortedByDescending { it.width.toLong() * it.height }
        binding.resolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sizes.map { "${it.width} x ${it.height}" },
        )
        binding.resolution.setSelection(savedSizeIndex())
        binding.resolution.onItemSelectedListener = selected { fitPreview(selectedSize()) }
        fitPreview(selectedSize())
        scheduleAutoStart()
    }

    private fun savedCameraIndex(): Int {
        val id = prefs().getString(KEY_CAMERA, null) ?: return 0
        val index = cameras.indexOfFirst { it.id == id }
        return if (index >= 0) index else 0
    }

    private fun savedFormatIndex(): Int {
        val jpeg = prefs().getBoolean(KEY_JPEG, true)
        val index = formats.indexOfFirst { it.jpeg == jpeg }
        return if (index >= 0) index else 0
    }

    private fun savedSizeIndex(): Int {
        val width = prefs().getInt(KEY_WIDTH, -1)
        val height = prefs().getInt(KEY_HEIGHT, -1)
        val index = sizes.indexOfFirst { it.width == width && it.height == height }
        return if (index >= 0) index else 0
    }

    private fun saveSettings(cameraId: String, jpeg: Boolean, size: Size) {
        prefs().edit()
            .putString(KEY_CAMERA, cameraId)
            .putBoolean(KEY_JPEG, jpeg)
            .putInt(KEY_WIDTH, size.width)
            .putInt(KEY_HEIGHT, size.height)
            .apply()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun selectedSize(): Size? = sizes.getOrNull(binding.resolution.selectedItemPosition)

    /**
     * TextureView draws the capture buffer with width and height exchanged.
     * The view uses that shape so the picture is scaled evenly. It is not rotated upright.
     */
    private fun fitPreview(size: Size?) {
        if (size == null || size.width <= 0 || size.height <= 0) return
        val parent = binding.preview.parent as? View ?: return
        val available = parent.width - parent.paddingLeft - parent.paddingRight
        if (available <= 0) return
        val aspect = size.height.toFloat() / size.width.toFloat()
        var targetWidth = available
        var targetHeight = (targetWidth / aspect).toInt().coerceAtLeast(1)
        val maxHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        if (targetHeight > maxHeight && maxHeight > 0) {
            targetHeight = maxHeight
            targetWidth = (targetHeight * aspect).toInt().coerceAtLeast(1)
        }
        val params = binding.preview.layoutParams
        if (params.width != targetWidth || params.height != targetHeight) {
            params.width = targetWidth
            params.height = targetHeight
            binding.preview.layoutParams = params
        }
    }

    private fun scheduleAutoStart() {
        if (!pendingAutoStart) return
        pendingAutoStart = false
        requestStart()
    }

    private fun requestStart() {
        if (running) return
        includeCameraOnGrant = cameras.isNotEmpty()
        permissionStartsSession = true
        val missing = buildList {
            if (includeCameraOnGrant && !granted(Manifest.permission.CAMERA)) {
                add(Manifest.permission.CAMERA)
            }
            if (publishGpsChecked() && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (publishGpsChecked() && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (missing.isEmpty()) {
            begin(includeCameraOnGrant)
        } else {
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun begin(includeCamera: Boolean) {
        if (running) return
        val cameraId = cameras.getOrNull(binding.camera.selectedItemPosition)?.id
        val jpeg = formats.getOrNull(binding.format.selectedItemPosition)?.jpeg
        val size = sizes.getOrNull(binding.resolution.selectedItemPosition)
        if (includeCamera && cameraId != null && jpeg != null && size != null) {
            saveSettings(cameraId, jpeg, size)
        }
        if (phoneIp.isBlank()) {
            toast(getString(R.string.no_wifi_address))
            return
        }
        running = true
        startRates()
        setControlsEnabled(false)
        binding.start.setText(R.string.stop)
        binding.status.text = getString(R.string.connecting)
        val publishGps = publishGpsChecked()
        Thread {
            val nodeError = NativeBridge.nodeStart(masterUri, phoneIp, phoneName)
            val jpegMode = jpeg
            val idForTopic = cameraId
            val pubError = if (nodeError == null && includeCamera && jpegMode != null && idForTopic != null) {
                NativeBridge.startPublisher(idForTopic, jpegMode)
            } else {
                null
            }
            val motionError = if (nodeError == null && pubError == null) motion.advertise() else null
            val gpsError = if (publishGps && nodeError == null && pubError == null && motionError == null) {
                gps.advertise()
            } else {
                null
            }
            runOnUiThread {
                val error = nodeError ?: pubError ?: motionError ?: gpsError
                if (!running || error != null) {
                    running = false
                    stopRates()
                    motion.stop()
                    gps.stop()
                    try {
                        NativeBridge.stopPublisher()
                    } catch (_: UnsatisfiedLinkError) {
                    }
                    setControlsEnabled(true)
                    binding.start.setText(R.string.start)
                    if (error != null) {
                        binding.status.text = error
                        AppLog.line(error)
                    }
                    return@runOnUiThread
                }
                motion.listen()
                if (publishGps) gps.listen()
                val id = cameraId
                val mode = jpegMode
                val frame = size
                if (includeCamera && id != null && mode != null && frame != null && binding.preview.isAvailable) {
                    openCamera(id, mode, frame)
                }
            }
        }.start()
    }

    private fun openCamera(cameraId: String, jpeg: Boolean, size: Size) {
        fitPreview(size)
        val handler = ensureCameraThread()
        val texture = binding.preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val preview = Surface(texture)
        val imageFormat = if (jpeg) android.graphics.ImageFormat.JPEG else android.graphics.ImageFormat.YUV_420_888
        reader?.close()
        val imageReader = ImageReader.newInstance(size.width, size.height, imageFormat, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source -> onImage(source, jpeg) }, handler)
        val manager = getSystemService(CameraManager::class.java)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val targets = listOf(preview, imageReader.surface)
                device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(capture: CameraCaptureSession) {
                        session = capture
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            addTarget(imageReader.surface)
                        }.build()
                        capture.setRepeatingRequest(request, null, handler)
                        runOnUiThread {
                            binding.status.text = getString(R.string.publishing)
                        }
                    }

                    override fun onConfigureFailed(capture: CameraCaptureSession) {
                        runOnUiThread { binding.status.text = getString(R.string.camera_configure_failed) }
                    }
                }, handler)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (camera == device) camera = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (camera == device) camera = null
                runOnUiThread { binding.status.text = getString(R.string.camera_error, error) }
            }
        }, handler)
    }

    private fun onImage(source: ImageReader, jpeg: Boolean) {
        val image = source.acquireLatestImage() ?: return
        if (!publishing.compareAndSet(false, true)) {
            image.close()
            dropped.incrementAndGet()
            return
        }
        publishExecutor.execute {
            var sent = false
            try {
                sent = if (jpeg) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    NativeBridge.publishJpeg(bytes)
                } else {
                    val planes = image.planes
                    NativeBridge.publishYuv(
                        planes[0].buffer,
                        planes[1].buffer,
                        planes[2].buffer,
                        image.width,
                        image.height,
                        planes[0].rowStride,
                        planes[1].rowStride,
                        planes[2].rowStride,
                        planes[0].pixelStride,
                        planes[1].pixelStride,
                        planes[2].pixelStride,
                    )
                }
            } finally {
                image.close()
                publishing.set(false)
            }
            if (sent) frames.incrementAndGet() else dropped.incrementAndGet()
        }
    }

    private fun startRates() {
        lastRateTick = 0L
        rateHandler.removeCallbacks(rateTick)
        rateHandler.post(rateTick)
    }

    private fun stopRates() {
        rateHandler.removeCallbacks(rateTick)
        if (::binding.isInitialized) binding.rates.text = ""
    }

    private fun updateRates() {
        val now = SystemClock.elapsedRealtime()
        val framesNow = frames.get()
        val imuNow = motion.imuSamples()
        val magNow = motion.magnetometerSamples()
        val tempNow = motion.temperatureSamples()
        val gpsNow = gps.samples()
        if (lastRateTick > 0L) {
            val seconds = (now - lastRateTick) / 1000.0
            if (seconds > 0.0) {
                val lines = mutableListOf<String>()
                if (NativeBridge.currentTopic().isNotEmpty()) {
                    lines.add(rateLine("camera", (framesNow - lastFrames) / seconds))
                }
                if (NativeBridge.imuTopic().isNotEmpty()) {
                    lines.add(rateLine("imu", (imuNow - lastImu) / seconds))
                }
                if (NativeBridge.magnetometerTopic().isNotEmpty()) {
                    lines.add(rateLine("mag", (magNow - lastMag) / seconds))
                }
                if (NativeBridge.temperatureTopic().isNotEmpty()) {
                    lines.add(rateLine("temp", (tempNow - lastTemp) / seconds))
                }
                if (NativeBridge.gpsTopic().isNotEmpty()) {
                    lines.add(rateLine("gps", (gpsNow - lastGps) / seconds))
                }
                binding.rates.text = lines.joinToString("\n")
            }
        }
        lastRateTick = now
        lastFrames = framesNow
        lastImu = imuNow
        lastMag = magNow
        lastTemp = tempNow
        lastGps = gpsNow
        if (binding.topics.visibility == View.VISIBLE) refreshTopics()
    }

    private fun rateLine(name: String, fps: Double): String {
        return "%-8s %6.1f fps".format(Locale.US, name, fps)
    }

    private fun refreshTopics() {
        val topics = try {
            NativeBridge.publishedTopics().toList()
        } catch (_: UnsatisfiedLinkError) {
            emptyList()
        }
        binding.topics.text = if (topics.isEmpty()) getString(R.string.no_topics) else topics.joinToString("\n")
    }

    private fun stopCapture() {
        running = false
        stopRates()
        session?.close()
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        if (::motion.isInitialized) motion.stop()
        if (::gps.isInitialized) gps.stop()
        try {
            NativeBridge.stopPublisher()
        } catch (_: UnsatisfiedLinkError) {
        }
        if (::binding.isInitialized) {
            binding.start.setText(R.string.start)
            setControlsEnabled(true)
        }
    }

    private fun ensureCameraThread(): Handler {
        val existing = cameraHandler
        if (existing != null) return existing
        val thread = HandlerThread("camera")
        thread.start()
        cameraThread = thread
        return Handler(thread.looper).also { cameraHandler = it }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.camera.isEnabled = enabled
        binding.format.isEnabled = enabled
        binding.resolution.isEnabled = enabled
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun selected(block: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
            block()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (!running || camera != null) return
            val choice = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return
            val format = formats.getOrNull(binding.format.selectedItemPosition) ?: return
            val size = sizes.getOrNull(binding.resolution.selectedItemPosition) ?: return
            if (NativeBridge.currentTopic().isEmpty()) return
            openCamera(choice.id, format.jpeg, size)
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

    companion object {
        const val EXTRA_URI = "master_uri"
        const val EXTRA_LABEL = "master_label"
        const val EXTRA_IP = "phone_ip"
        const val EXTRA_PHONE = "phone_name"
        private const val PREFS = "grabbure_camera"
        private const val KEY_CAMERA = "camera_id"
        private const val KEY_JPEG = "jpeg"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_MAG_HZ = "mag_hz"
        private const val KEY_GPS = "gps"
        private val MAG_RATES = intArrayOf(100, 50)
    }
}
