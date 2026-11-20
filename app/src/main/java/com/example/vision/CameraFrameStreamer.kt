package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val TAG = "CameraFrameStreamer"
private const val MAX_DIMENSION = 768
private const val FRAME_INTERVAL_MS = 1000L

class CameraFrameStreamer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lastFrameTimestamp = AtomicLong(0L)
    private val isStarted = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var useFrontCamera: Boolean = false

    fun start(useFront: Boolean, previewSurfaceProvider: Preview.SurfaceProvider? = null) {
        useFrontCamera = useFront
        surfaceProvider = previewSurfaceProvider
        isStarted.set(true)

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                if (!isStarted.get()) return@addListener
                val provider = providerFuture.get()
                cameraProvider = provider
                bindUseCases(provider)
                VisionFrameBus.setCameraActive(true)
                VisionFrameBus.setFrontCamera(useFrontCamera)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX use cases", e)
                VisionFrameBus.setCameraActive(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun attachPreview(provider: Preview.SurfaceProvider?) {
        surfaceProvider = provider
        if (isStarted.get()) {
            ContextCompat.getMainExecutor(context).execute {
                cameraProvider?.let { bindUseCases(it) }
            }
        }
    }

    fun switchCamera(useFront: Boolean) {
        if (useFrontCamera == useFront && isStarted.get()) return
        useFrontCamera = useFront
        VisionFrameBus.setFrontCamera(useFront)
        if (isStarted.get()) {
            ContextCompat.getMainExecutor(context).execute {
                cameraProvider?.let { bindUseCases(it) }
            }
        }
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        provider.unbindAll()

        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        val activeSurfaceProvider = surfaceProvider
        if (activeSurfaceProvider != null) {
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(activeSurfaceProvider)
            }
            previewUseCase = preview
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
        } else {
            previewUseCase = null
            provider.bindToLifecycle(lifecycleOwner, selector, imageAnalysis)
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameTimestamp.get() < FRAME_INTERVAL_MS) {
                return
            }
            lastFrameTimestamp.set(now)

            val rawBitmap = imageProxy.toBitmap()
            val rotated = rotateAndMirrorIfNeeded(
                bitmap = rawBitmap,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                mirror = useFrontCamera
            )
            if (rotated !== rawBitmap) {
                rawBitmap.recycle()
            }

            val scaled = scaleBitmap(rotated, MAX_DIMENSION)
            if (scaled !== rotated) {
                rotated.recycle()
            }

            val outStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, outStream)
            scaled.recycle()

            VisionFrameBus.publishFrame(
                VisionFrame(
                    jpegBytes = outStream.toByteArray(),
                    source = VisionSource.CAMERA,
                    timestampMs = now
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding camera frame", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateAndMirrorIfNeeded(
        bitmap: Bitmap,
        rotationDegrees: Int,
        mirror: Boolean
    ): Bitmap {
        if (rotationDegrees == 0 && !mirror) return bitmap
        val matrix = Matrix().apply {
            if (rotationDegrees != 0) {
                postRotate(rotationDegrees.toFloat())
            }
            if (mirror) {
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / largest.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    fun stop() {
        isStarted.set(false)
        surfaceProvider = null
        previewUseCase = null
        ContextCompat.getMainExecutor(context).execute {
            runCatching { cameraProvider?.unbindAll() }
        }
        VisionFrameBus.setCameraActive(false)
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }
}
