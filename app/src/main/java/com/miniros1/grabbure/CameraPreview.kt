package com.miniros1.grabbure

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View

/**
 * TextureView draws the capture buffer with width and height exchanged.
 * The view uses that shape at the full content width, so a 1280×720 frame is not
 * stretched and the sides are not cut off. A square size is unchanged.
 */
fun fitCapturePreview(preview: TextureView, size: Size) {
    if (size.width <= 0 || size.height <= 0) return
    val parent = preview.parent as? View ?: return
    val available = parent.width - parent.paddingLeft - parent.paddingRight
    if (available <= 0) return
    val aspect = size.height.toFloat() / size.width.toFloat()
    val targetWidth = available
    val targetHeight = (targetWidth / aspect).toInt().coerceAtLeast(1)
    val params = preview.layoutParams
    if (params.width != targetWidth || params.height != targetHeight) {
        params.width = targetWidth
        params.height = targetHeight
        preview.layoutParams = params
    }
}

/** Frames per second this size can repeat. Zero means the camera did not advertise a limit. */
fun sizeFps(manager: CameraManager, cameraId: String, imageFormat: Int, size: Size): Int {
    return try {
        val minFrameNs = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputMinFrameDuration(imageFormat, size) ?: 0L
        if (minFrameNs > 0L) (1_000_000_000L / minFrameNs).toInt() else 0
    } catch (_: RuntimeException) {
        0
    }
}

/**
 * Ranges to try, best first. A preview template left on 15–30 lets auto-exposure sit near 18 fps.
 * A fixed 30 fps range is preferred. If the camera can reach 30 but only lists a wider range,
 * 30–30 is still tried before that wider range.
 */
fun repeatingFpsRanges(manager: CameraManager, cameraId: String): List<Range<Int>> {
    val advertised = manager.getCameraCharacteristics(cameraId)
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?.toList()
        .orEmpty()
    if (advertised.isEmpty()) return emptyList()
    val exact30 = advertised.filter { it.lower == 30 && it.upper == 30 }
    val otherFixed = advertised
        .filter { it.lower >= 30 && it.lower == it.upper && !(it.lower == 30 && it.upper == 30) }
        .sortedBy { it.lower }
    val floored = advertised
        .filter { it.lower >= 30 && it.upper > it.lower }
        .sortedWith(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { it.lower })
    val forced = if (exact30.isEmpty() && advertised.any { it.upper >= 30 }) listOf(Range(30, 30)) else emptyList()
    val wider = advertised
        .filter { it.lower < 30 }
        .sortedByDescending { it.lower }
    return (exact30 + otherFixed + floored + forced + wider).distinct()
}

fun selectableAeModes(manager: CameraManager, cameraId: String): List<Int> {
    return selectableModes(manager, cameraId, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES, CaptureRequest.CONTROL_AE_MODE_OFF)
}

fun selectableAwbModes(manager: CameraManager, cameraId: String): List<Int> {
    return selectableModes(manager, cameraId, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES, CaptureRequest.CONTROL_AWB_MODE_OFF)
}

fun exposureSteps(manager: CameraManager, cameraId: String): List<Int> {
    val range = try {
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    } catch (_: RuntimeException) {
        null
    } ?: return listOf(0)
    if (range.lower > range.upper) return listOf(0)
    return (range.lower..range.upper).toList()
}

fun exposureEv(manager: CameraManager, cameraId: String, stepIndex: Int): Double {
    val step = try {
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
    } catch (_: RuntimeException) {
        null
    }
    if (step == null || step.denominator == 0) return stepIndex.toDouble()
    return stepIndex * step.numerator.toDouble() / step.denominator
}

/** Frame rate, autoexposure, exposure compensation, and white balance applied to a repeating request. */
data class CaptureTune(
    val fps: Range<Int>?,
    val aeMode: Int,
    val awbMode: Int,
    val exposureCompensation: Int,
)

fun resolveCaptureTune(context: Context, manager: CameraManager, cameraId: String): CaptureTune {
    val saved = GrabSettings.load(context)
    val ranges = repeatingFpsRanges(manager, cameraId)
    val fps = ranges.firstOrNull { it.lower == saved.fpsLower && it.upper == saved.fpsUpper } ?: ranges.firstOrNull()
    val aeModes = selectableAeModes(manager, cameraId)
    val awbModes = selectableAwbModes(manager, cameraId)
    val ae = if (saved.aeMode in aeModes) saved.aeMode else aeModes.firstOrNull() ?: CaptureRequest.CONTROL_AE_MODE_ON
    val awb = if (saved.awbMode in awbModes) saved.awbMode else awbModes.firstOrNull() ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
    val steps = exposureSteps(manager, cameraId)
    val exposure = if (saved.exposureCompensation in steps) saved.exposureCompensation else 0.coerceIn(steps.first(), steps.last())
    return CaptureTune(fps, ae, awb, exposure)
}

fun setRepeatingPreview(
    device: CameraDevice,
    session: CameraCaptureSession,
    surfaces: List<Surface>,
    handler: Handler,
    tune: CaptureTune,
    fallbackRanges: List<Range<Int>>,
) {
    val fpsAttempts = buildList<Range<Int>?> {
        tune.fps?.let { add(it) }
        for (range in fallbackRanges) {
            if (range != tune.fps) add(range)
        }
        add(null)
    }
    var lastError: RuntimeException? = null
    for (fps in fpsAttempts) {
        try {
            session.setRepeatingRequest(previewRequest(device, surfaces, fps, tune), null, handler)
            return
        } catch (ex: RuntimeException) {
            lastError = ex
        }
    }
    try {
        val bare = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            for (surface in surfaces) addTarget(surface)
        }.build()
        session.setRepeatingRequest(bare, null, handler)
    } catch (ex: RuntimeException) {
        throw lastError ?: ex
    }
}

private fun previewRequest(
    device: CameraDevice,
    surfaces: List<Surface>,
    fps: Range<Int>?,
    tune: CaptureTune,
): CaptureRequest {
    return device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        for (surface in surfaces) addTarget(surface)
        if (fps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
        set(CaptureRequest.CONTROL_AE_MODE, tune.aeMode)
        set(CaptureRequest.CONTROL_AWB_MODE, tune.awbMode)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, tune.exposureCompensation)
    }.build()
}

private fun selectableModes(
    manager: CameraManager,
    cameraId: String,
    key: CameraCharacteristics.Key<IntArray>,
    manualOff: Int,
): List<Int> {
    val modes = try {
        manager.getCameraCharacteristics(cameraId).get(key)?.toList().orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }
    val automatic = modes.filter { it != manualOff }
    return automatic.ifEmpty { modes }
}
