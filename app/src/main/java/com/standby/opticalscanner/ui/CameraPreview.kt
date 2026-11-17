package com.standby.opticalscanner.ui

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    isScanning: Boolean,
    onRoiFrame: (timestampNs: Long, rgb: ByteArray, width: Int, height: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val reticleSizePx = with(LocalDensity.current) { RETICLE_SIZE.toPx() }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val isScanningRef = remember { AtomicBoolean(isScanning) }
    val lockedCropSide = remember { AtomicInteger(0) }
    val onRoiFrameRef = remember { AtomicReference(onRoiFrame) }
    val viewSizeRef = remember { AtomicReference(0 to 0) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    SideEffect {
        val wasScanning = isScanningRef.getAndSet(isScanning)
        if (!isScanning || (!wasScanning && isScanning)) {
            lockedCropSide.set(0)
        }
        onRoiFrameRef.set(onRoiFrame)
    }

    // Lock auto-exposure during active scanning so light pulses are not suppressed
    LaunchedEffect(boundCamera, isScanning) {
        val camera = boundCamera ?: return@LaunchedEffect
        try {
            val control = Camera2CameraControl.from(camera.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, isScanning)
                .build()
            control.setCaptureRequestOptions(options)
        } catch (_: Exception) {
            // Safe fallback on devices/emulators that do not support AE lock
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        viewSizeRef.set(v.width to v.height)
                    }
                }

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preferredFpsRange = findPreferredHighFpsRange(ctx)

                    val previewBuilder = Preview.Builder()
                    if (preferredFpsRange != null) {
                        Camera2Interop.Extender(previewBuilder)
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                preferredFpsRange
                            )
                    }
                    val preview = previewBuilder.build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val analysisBuilder = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    if (preferredFpsRange != null) {
                        Camera2Interop.Extender(analysisBuilder)
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                preferredFpsRange
                            )
                    }
                    val imageAnalysis = analysisBuilder.build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            if (isScanningRef.get()) {
                                val (viewW, viewH) = viewSizeRef.get()
                                val cropSide = computeOrGetCropSide(
                                    lockedCropSide = lockedCropSide,
                                    imageProxy = imageProxy,
                                    viewWidth = viewW,
                                    viewHeight = viewH,
                                    reticleSizePx = reticleSizePx
                                )
                                val rgb = extractCenteredRgbCrop(imageProxy, cropSide)
                                onRoiFrameRef.get().invoke(
                                    imageProxy.imageInfo.timestamp,
                                    rgb,
                                    cropSide,
                                    cropSide
                                )
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    try {
                        cameraProvider.unbindAll()
                        boundCamera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun findPreferredHighFpsRange(context: Context): Range<Int>? {
    return try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return null
        val backCameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        val ranges = manager.getCameraCharacteristics(backCameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        ranges.firstOrNull { it.lower == 60 && it.upper == 60 }
            ?: ranges.filter { it.upper >= 60 }.maxByOrNull { it.lower }
    } catch (_: Exception) {
        null
    }
}

private fun computeOrGetCropSide(
    lockedCropSide: AtomicInteger,
    imageProxy: ImageProxy,
    viewWidth: Int,
    viewHeight: Int,
    reticleSizePx: Float
): Int {
    val minDim = min(imageProxy.width, imageProxy.height)
    val existing = lockedCropSide.get()
    if (existing in 24..minDim) {
        return existing
    }

    val computed = if (viewWidth > 0 && viewHeight > 0 && reticleSizePx > 0f) {
        val rot90 = imageProxy.imageInfo.rotationDegrees % 180 != 0
        val rotW = if (rot90) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val rotH = if (rot90) imageProxy.width.toFloat() else imageProxy.height.toFloat()
        val fillCenterScale = max(viewWidth / rotW, viewHeight / rotH)
        (reticleSizePx / fillCenterScale).roundToInt()
    } else {
        (minDim * 0.18f).roundToInt()
    }.coerceIn(24, minDim)

    lockedCropSide.compareAndSet(0, computed)
    return lockedCropSide.get().coerceIn(24, minDim)
}

private fun extractCenteredRgbCrop(imageProxy: ImageProxy, cropSide: Int): ByteArray {
    val plane = imageProxy.planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride

    val startX = (imageProxy.width - cropSide) / 2
    val startY = (imageProxy.height - cropSide) / 2

    val rgb = ByteArray(cropSide * cropSide * 3)
    var outIdx = 0
    for (row in 0 until cropSide) {
        val rowOffset = (startY + row) * rowStride + startX * pixelStride
        var srcIdx = rowOffset
        for (col in 0 until cropSide) {
            rgb[outIdx] = buffer.get(srcIdx)
            rgb[outIdx + 1] = buffer.get(srcIdx + 1)
            rgb[outIdx + 2] = buffer.get(srcIdx + 2)
            outIdx += 3
            srcIdx += pixelStride
        }
    }
    return rgb
}
