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
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dkargin.grabbure.databinding.ActivityCameraBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
    private lateinit var masterUri: String
    private lateinit var masterLabel: String
    private lateinit var phoneIp: String

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

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            begin()
        } else {
            toast(getString(R.string.camera_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        masterUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        masterLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        phoneIp = intent.getStringExtra(EXTRA_IP).orEmpty()
        binding.master.text = getString(R.string.master_line, masterLabel, masterUri)
        binding.preview.surfaceTextureListener = surfaceListener
        binding.start.setOnClickListener {
            if (running) stopCapture() else requestStart()
        }
        loadCameras()
    }

    override fun onDestroy() {
        stopCapture()
        publishExecutor.shutdown()
        super.onDestroy()
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
            binding.start.isEnabled = false
            return
        }
        binding.camera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameras.map { it.label })
        binding.camera.setSelection(0)
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
    }

    private fun requestStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun begin() {
        val choice = cameras.getOrNull(binding.camera.selectedItemPosition) ?: return
        val format = formats.getOrNull(binding.format.selectedItemPosition) ?: return
        val size = sizes.getOrNull(binding.resolution.selectedItemPosition) ?: return
        if (phoneIp.isBlank()) {
            toast(getString(R.string.no_wifi_address))
            return
        }
        running = true
        setControlsEnabled(false)
        binding.start.setText(R.string.stop)
        binding.status.text = getString(R.string.connecting)
        val filesDir = filesDir.absolutePath
        Thread {
            val nodeError = NativeBridge.nodeStart(masterUri, phoneIp, filesDir)
            val pubError = if (nodeError == null) NativeBridge.startPublisher(format.jpeg) else null
            runOnUiThread {
                val error = nodeError ?: pubError
                if (!running || error != null) {
                    running = false
                    setControlsEnabled(true)
                    binding.start.setText(R.string.start)
                    if (error != null) binding.status.text = error
                    return@runOnUiThread
                }
                if (binding.preview.isAvailable) {
                    openCamera(choice.id, format.jpeg, size)
                }
            }
        }.start()
    }

    private fun openCamera(cameraId: String, jpeg: Boolean, size: Size) {
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
                        val topic = NativeBridge.currentTopic()
                        runOnUiThread {
                            binding.status.text = getString(R.string.publishing, topic, frames.get(), dropped.get())
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
            val topic = NativeBridge.currentTopic()
            val published = frames.get()
            val skipped = dropped.get()
            runOnUiThread {
                if (running) {
                    binding.status.text = getString(R.string.publishing, topic, published, skipped)
                }
            }
        }
    }

    private fun stopCapture() {
        running = false
        session?.close()
        session = null
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
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
    }
}
