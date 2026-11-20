package com.example.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

private const val TAG = "ScreenFrameStreamer"
private const val MAX_DIMENSION = 768
private const val FRAME_INTERVAL_MS = 1000L

class ScreenFrameStreamer(
    private val context: Context
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val lastFrameTimestamp = AtomicLong(0L)
    private val isActive = AtomicBoolean(false)
    private var onSystemStoppedCallback: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system or user")
            stop()
            onSystemStoppedCallback?.invoke()
        }
    }

    fun start(
        projection: MediaProjection,
        onStoppedBySystem: () -> Unit
    ) {
        stop()
        isActive.set(true)
        onSystemStoppedCallback = onStoppedBySystem
        mediaProjection = projection

        val metrics = context.resources.displayMetrics
        val densityDpi = metrics.densityDpi
        val screenWidth = metrics.widthPixels.coerceAtLeast(360)
        val screenHeight = metrics.heightPixels.coerceAtLeast(640)

        // Capture at scaled-down dimensions directly for low memory & CPU overhead
        val largest = max(screenWidth, screenHeight)
        val scale = if (largest > MAX_DIMENSION) MAX_DIMENSION.toFloat() / largest else 1f
        val targetWidth = (screenWidth * scale).toInt().coerceAtLeast(240)
        val targetHeight = (screenHeight * scale).toInt().coerceAtLeast(240)

        val thread = HandlerThread("FridayScreenCapture").apply { start() }
        handlerThread = thread
        val captureHandler = Handler(thread.looper)
        handler = captureHandler

        projection.registerCallback(projectionCallback, captureHandler)

        val reader = ImageReader.newInstance(
            targetWidth,
            targetHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (!isActive.get()) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameTimestamp.get() < FRAME_INTERVAL_MS) {
                    return@setOnImageAvailableListener
                }
                lastFrameTimestamp.set(now)

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * targetWidth

                val paddedBitmap = Bitmap.createBitmap(
                    targetWidth + rowPadding / pixelStride,
                    targetHeight,
                    Bitmap.Config.ARGB_8888
                )
                paddedBitmap.copyPixelsFromBuffer(buffer)

                val croppedBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(paddedBitmap, 0, 0, targetWidth, targetHeight).also {
                        paddedBitmap.recycle()
                    }
                } else {
                    paddedBitmap
                }

                val outStream = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 72, outStream)
                croppedBitmap.recycle()

                VisionFrameBus.publishFrame(
                    VisionFrame(
                        jpegBytes = outStream.toByteArray(),
                        source = VisionSource.SCREEN,
                        timestampMs = now
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen frame", e)
            } finally {
                image.close()
            }
        }, captureHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "FridayScreenVision",
            targetWidth,
            targetHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        VisionFrameBus.setScreenActive(true)
    }

    fun stop() {
        isActive.set(false)
        onSystemStoppedCallback = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.close() }
        imageReader = null

        mediaProjection?.let { proj ->
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
        mediaProjection = null

        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
        handler = null

        VisionFrameBus.setScreenActive(false)
    }
}
