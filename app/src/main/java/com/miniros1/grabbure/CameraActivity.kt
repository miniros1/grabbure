package com.miniros1.grabbure

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miniros1.grabbure.databinding.ActivityCameraBinding
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Publishes the camera and the other sensors using the choices stored by [SettingsActivity]. */
class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
    private lateinit var masterUri: String
    private lateinit var masterLabel: String
    private lateinit var phoneIp: String
    private lateinit var phoneName: String
    private var bagMode = false
    private var bagDir = ""
    private var recordingBag: File? = null

    private var captureId: String? = null
    private var captureJpeg = true
    private var captureSize: Size? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val publishExecutor = Executors.newSingleThreadExecutor()
    private val publishing = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<Any?>(null)
    private val frames = AtomicInteger()
    private val dropped = AtomicInteger()
    @Volatile
    private var running = false
    private var includeCamera = false
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

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val cameraGranted = granted(Manifest.permission.CAMERA)
        val locationGranted = locationGranted()
        if (includeCamera && !cameraGranted) {
            toast(getString(R.string.camera_permission_denied))
        }
        if (GrabSettings.load(this).publishGps && !locationGranted) {
            toast(getString(R.string.location_permission_denied))
            GrabSettings.saveGps(this, false)
        }
        begin(includeCamera && cameraGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        masterUri = intent.getStringExtra(Session.EXTRA_URI).orEmpty()
        masterLabel = intent.getStringExtra(Session.EXTRA_LABEL).orEmpty()
        phoneIp = intent.getStringExtra(Session.EXTRA_IP).orEmpty()
        phoneName = PhoneName.sanitize(intent.getStringExtra(Session.EXTRA_PHONE).orEmpty())
        bagMode = intent.getBooleanExtra(Session.EXTRA_BAG, false)
        bagDir = intent.getStringExtra(Session.EXTRA_BAG_DIR).orEmpty()
        binding.master.text = if (bagMode) {
            getString(R.string.bag_folder_line, bagDir)
        } else {
            getString(R.string.master_line, masterLabel, masterUri)
        }
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
        requestStart()
    }

    override fun onDestroy() {
        stopCapture()
        publishExecutor.shutdown()
        super.onDestroy()
    }

    private fun requestStart() {
        if (running) return
        val saved = GrabSettings.load(this)
        includeCamera = saved.cameraId != null && saved.size != null
        val missing = buildList {
            if (includeCamera && !granted(Manifest.permission.CAMERA)) {
                add(Manifest.permission.CAMERA)
            }
            if (saved.publishGps && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (saved.publishGps && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (missing.isEmpty()) {
            begin(includeCamera)
        } else {
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun locationGranted(): Boolean {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun begin(includeCamera: Boolean) {
        if (running) return
        val saved = GrabSettings.load(this)
        val cameraId = if (includeCamera) saved.cameraId else null
        val size = if (includeCamera) saved.size else null
        val jpeg = if (bagMode) true else saved.jpeg
        if (!bagMode && phoneIp.isBlank()) {
            toast(getString(R.string.no_wifi_address))
            return
        }
        if (bagMode && bagDir.isBlank()) {
            toast(getString(R.string.bag_folder_unavailable))
            return
        }
        motion.setImuRateHz(saved.imuHz)
        motion.setMagnetometerRateHz(saved.magHz)
        val publishGps = saved.publishGps && locationGranted()
        running = true
        startRates()
        binding.start.setText(R.string.stop)
        binding.status.setText(if (bagMode) R.string.recording else R.string.connecting)
        captureId = cameraId
        captureJpeg = jpeg
        captureSize = size
        val recordBag = bagMode
        val bagFile = if (recordBag) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(bagDir, "grabbure_$stamp.bag")
            recordingBag = file
            file.absolutePath
        } else {
            ""
        }
        Thread {
            val nodeError = if (recordBag) {
                NativeBridge.startBag(
                    bagFile,
                    phoneName,
                    cameraId.orEmpty(),
                    motion.hasImu,
                    motion.hasTemperature,
                    motion.hasMagnetometer,
                    publishGps,
                )
            } else {
                NativeBridge.nodeStart(masterUri, phoneIp, phoneName)
            }
            val pubError = if (!recordBag && nodeError == null && cameraId != null) {
                NativeBridge.startPublisher(cameraId, jpeg)
            } else {
                null
            }
            val motionError = if (!recordBag && nodeError == null && pubError == null) motion.advertise() else null
            val gpsError = if (!recordBag && publishGps && nodeError == null && pubError == null && motionError == null) {
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
                    closeBag()
                    binding.start.setText(R.string.start)
                    if (error != null) {
                        binding.status.text = error
                        AppLog.line(error)
                    }
                    return@runOnUiThread
                }
                motion.listen()
                if (publishGps) gps.listen()
                if (cameraId != null && size != null && binding.preview.isAvailable) {
                    openCamera(cameraId, jpeg, size)
                }
                if (recordBag) {
                    binding.status.text = getString(R.string.recording) + "\n" + bagFile
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, jpeg: Boolean, size: Size) {
        fitCapturePreview(binding.preview, size)
        val handler = ensureCameraThread()
        val texture = binding.preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(size.width, size.height)
        val preview = Surface(texture)
        val imageFormat = if (jpeg) ImageFormat.JPEG else ImageFormat.YUV_420_888
        reader?.close()
        val imageReader = ImageReader.newInstance(size.width, size.height, imageFormat, 4)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source -> onImage(source, jpeg) }, handler)
        val manager = getSystemService(CameraManager::class.java)
        val fpsRanges = repeatingFpsRanges(manager, cameraId)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val targets = listOf(preview, imageReader.surface)
                device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(capture: CameraCaptureSession) {
                        session = capture
                        try {
                            setRepeatingPreview(
                                device,
                                capture,
                                listOf(preview, imageReader.surface),
                                handler,
                                resolveCaptureTune(this@CameraActivity, manager, cameraId),
                                fpsRanges,
                            )
                        } catch (_: RuntimeException) {
                            runOnUiThread { binding.status.setText(R.string.camera_configure_failed) }
                            return
                        }
                        runOnUiThread {
                            if (!bagMode) binding.status.setText(R.string.publishing)
                        }
                    }

                    override fun onConfigureFailed(capture: CameraCaptureSession) {
                        runOnUiThread { binding.status.setText(R.string.camera_configure_failed) }
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
                if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE && running) {
                    handler.postDelayed({
                        if (running && camera == null) openCamera(cameraId, jpeg, size)
                    }, 200)
                    return
                }
                runOnUiThread { binding.status.text = getString(R.string.camera_error, error) }
            }
        }, handler)
    }

    private fun onImage(source: ImageReader, jpeg: Boolean) {
        val image = source.acquireLatestImage() ?: return
        val width = image.width
        val height = image.height
        val timestampNs = image.timestamp
        val payload = try {
            if (jpeg) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                JpegFrame(bytes, timestampNs)
            } else {
                val planes = image.planes
                YuvFrame(
                    copyPlane(planes[0]),
                    copyPlane(planes[1]),
                    copyPlane(planes[2]),
                    width,
                    height,
                    planes[0].rowStride,
                    planes[1].rowStride,
                    planes[2].rowStride,
                    planes[0].pixelStride,
                    planes[1].pixelStride,
                    planes[2].pixelStride,
                    timestampNs,
                )
            }
        } catch (_: RuntimeException) {
            dropped.incrementAndGet()
            return
        } finally {
            image.close()
        }
        frames.incrementAndGet()
        pendingFrame.set(payload)
        if (publishing.compareAndSet(false, true)) {
            publishExecutor.execute { drainFrames() }
        }
    }

    private fun drainFrames() {
        try {
            while (true) {
                when (val payload = pendingFrame.getAndSet(null)) {
                    null -> return
                    is JpegFrame -> NativeBridge.publishJpeg(payload.bytes, payload.timestampNs)
                    is YuvFrame -> NativeBridge.publishYuv(
                        payload.y,
                        payload.u,
                        payload.v,
                        payload.width,
                        payload.height,
                        payload.yRow,
                        payload.uRow,
                        payload.vRow,
                        payload.yPix,
                        payload.uPix,
                        payload.vPix,
                        payload.timestampNs,
                    )
                }
            }
        } finally {
            publishing.set(false)
            if (pendingFrame.get() != null && publishing.compareAndSet(false, true)) {
                publishExecutor.execute { drainFrames() }
            }
        }
    }

    private fun copyPlane(plane: Image.Plane): ByteBuffer {
        val src = plane.buffer.duplicate()
        val out = ByteBuffer.allocateDirect(src.remaining())
        out.put(src)
        out.flip()
        return out
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
        closeBag()
        if (::binding.isInitialized) binding.start.setText(R.string.start)
    }

    /** Close the bag, then rename it into Movies when that folder can take the file. */
    private fun closeBag() {
        val finished = recordingBag
        recordingBag = null
        try {
            NativeBridge.stopBag()
        } catch (_: UnsatisfiedLinkError) {
        }
        if (finished == null || !finished.isFile) return
        Thread {
            val published = BagFolder.publish(applicationContext, finished)
            runOnUiThread {
                if (::binding.isInitialized) {
                    binding.status.text = getString(R.string.bag_saved, published.absolutePath)
                }
            }
        }.start()
    }

    private fun ensureCameraThread(): Handler {
        val existing = cameraHandler
        if (existing != null) return existing
        val thread = HandlerThread("camera")
        thread.start()
        cameraThread = thread
        return Handler(thread.looper).also { cameraHandler = it }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (!running || camera != null) return
            val id = captureId ?: return
            val size = captureSize ?: return
            if (NativeBridge.currentTopic().isEmpty()) return
            openCamera(id, captureJpeg, size)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private class JpegFrame(val bytes: ByteArray, val timestampNs: Long)

    private class YuvFrame(
        val y: ByteBuffer,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val width: Int,
        val height: Int,
        val yRow: Int,
        val uRow: Int,
        val vRow: Int,
        val yPix: Int,
        val uPix: Int,
        val vPix: Int,
        val timestampNs: Long,
    )
}
