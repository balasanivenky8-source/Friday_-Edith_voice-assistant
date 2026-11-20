package com.example.vision

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

enum class VisionSource {
    CAMERA,
    SCREEN
}

data class VisionFrame(
    val jpegBytes: ByteArray,
    val source: VisionSource,
    val timestampMs: Long = System.currentTimeMillis()
)

object VisionFrameBus {
    private val _frames = MutableSharedFlow<VisionFrame>(extraBufferCapacity = 2)
    val frames: SharedFlow<VisionFrame> = _frames.asSharedFlow()

    private val _isCameraActive = MutableStateFlow(false)
    val isCameraActive: StateFlow<Boolean> = _isCameraActive.asStateFlow()

    private val _isScreenActive = MutableStateFlow(false)
    val isScreenActive: StateFlow<Boolean> = _isScreenActive.asStateFlow()

    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    private val latestFrameRef = AtomicReference<VisionFrame?>(null)

    fun publishFrame(frame: VisionFrame) {
        latestFrameRef.set(frame)
        _frames.tryEmit(frame)
    }

    fun getLatestFrameBytes(): ByteArray? = latestFrameRef.get()?.jpegBytes

    fun setCameraActive(active: Boolean) {
        _isCameraActive.value = active
        if (!active && latestFrameRef.get()?.source == VisionSource.CAMERA) {
            latestFrameRef.set(null)
        }
    }

    fun setScreenActive(active: Boolean) {
        _isScreenActive.value = active
        if (!active && latestFrameRef.get()?.source == VisionSource.SCREEN) {
            latestFrameRef.set(null)
        }
    }

    fun setFrontCamera(front: Boolean) {
        _useFrontCamera.value = front
    }

    fun clearAll() {
        _isCameraActive.value = false
        _isScreenActive.value = false
        latestFrameRef.set(null)
    }
}
