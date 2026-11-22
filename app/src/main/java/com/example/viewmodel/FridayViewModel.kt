package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.ai.DeepThinkEngine
import com.example.ai.FridayLiveSessionManager
import com.example.ai.LiveConnectionState
import com.example.audio.AudioStreamer
import com.example.tools.FridayToolExecutor
import com.example.tools.ToolActionBridge
import com.example.vision.FridayVisionService
import com.example.vision.VisionFrameBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "FridayViewModel"

enum class VoiceOrbState {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

data class FridayUiState(
    val orbState: VoiceOrbState = VoiceOrbState.IDLE,
    val isMuted: Boolean = false,
    val isCameraActive: Boolean = false,
    val isFrontCamera: Boolean = false,
    val isScreenActive: Boolean = false,
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
    val toolTelemetry: String? = null,
    val errorMessage: String? = null
)

sealed interface FridayUiEvent {
    data object RequestMicPermission : FridayUiEvent
    data object RequestCameraPermission : FridayUiEvent
    data object RequestScreenCapture : FridayUiEvent
    data object RequestNotificationPermissionIfNeeded : FridayUiEvent
}

class FridayViewModel(application: Application) : AndroidViewModel(application), ToolActionBridge {

    private val appContext = application.applicationContext
    private val audioStreamer = AudioStreamer()
    private val deepThinkEngine = DeepThinkEngine(appContext)
    private val toolExecutor = FridayToolExecutor(appContext, deepThinkEngine, this)
    private val sessionManager = FridayLiveSessionManager(
        context = appContext,
        audioStreamer = audioStreamer,
        toolExecutor = toolExecutor,
        onClearToolTelemetry = { _toolTelemetry.value = null }
    )

    private val _uiState = MutableStateFlow(FridayUiState())
    val uiState: StateFlow<FridayUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FridayUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FridayUiEvent> = _events.asSharedFlow()

    private val _toolTelemetry = MutableStateFlow<String?>(null)
    private val _isMuted = MutableStateFlow(false)
    private val _localErrorMessage = MutableStateFlow<String?>(null)
    private val _localErrorState = MutableStateFlow(false)

    private var pendingCameraContinuation: CompletableDeferred<Boolean>? = null
    private var pendingCameraFrontTarget: Boolean? = null
    private var pendingScreenContinuation: CompletableDeferred<Pair<Int, Intent>?>? = null
    private var pendingStartAfterMicGrant = false

    init {
        if (!com.example.ai.ensureDefaultFirebaseApp(appContext)) {
            _localErrorState.value = true
            _localErrorMessage.value = FIREBASE_CONFIG_ERROR
            _uiState.update {
                it.copy(
                    orbState = VoiceOrbState.ERROR,
                    errorMessage = FIREBASE_CONFIG_ERROR
                )
            }
        }

        FridayVisionService.onStopRequestedByNotification = {
            disconnectSession()
        }

        val sessionVisionFlow = combine(
            sessionManager.connectionState,
            _isMuted,
            VisionFrameBus.isCameraActive,
            VisionFrameBus.useFrontCamera,
            VisionFrameBus.isScreenActive
        ) { conn, muted, cam, front, screen ->
            SessionVisionSnapshot(conn, muted, cam, front, screen)
        }

        val telemetryErrorFlow = combine(
            audioStreamer.inputLevel,
            audioStreamer.outputLevel,
            _toolTelemetry,
            sessionManager.errorMessage,
            _localErrorMessage
        ) { inLvl, outLvl, tool, sessionErr, localErr ->
            TelemetryErrorSnapshot(inLvl, outLvl, tool, sessionErr ?: localErr)
        }

        viewModelScope.launch {
            combine(
                sessionVisionFlow,
                telemetryErrorFlow,
                _localErrorState
            ) { sv, te, hasLocalError ->
                val orb = when (sv.connectionState) {
                    LiveConnectionState.DISCONNECTED -> {
                        if (hasLocalError) VoiceOrbState.ERROR else VoiceOrbState.IDLE
                    }
                    LiveConnectionState.CONNECTING -> VoiceOrbState.CONNECTING
                    LiveConnectionState.LISTENING -> VoiceOrbState.LISTENING
                    LiveConnectionState.SPEAKING -> VoiceOrbState.SPEAKING
                    LiveConnectionState.ERROR -> VoiceOrbState.ERROR
                }
                FridayUiState(
                    orbState = orb,
                    isMuted = sv.isMuted,
                    isCameraActive = sv.isCameraActive,
                    isFrontCamera = sv.isFrontCamera,
                    isScreenActive = sv.isScreenActive,
                    inputLevel = te.inputLevel,
                    outputLevel = te.outputLevel,
                    toolTelemetry = te.toolTelemetry,
                    errorMessage = te.errorMessage
                )
            }.collect { updated ->
                _uiState.value = updated
            }
        }
    }

    fun onPowerToggle() {
        when (_uiState.value.orbState) {
            VoiceOrbState.IDLE, VoiceOrbState.ERROR -> startSessionWithPermissionCheck()
            VoiceOrbState.CONNECTING, VoiceOrbState.LISTENING, VoiceOrbState.SPEAKING -> {
                disconnectSession()
            }
        }
    }

    private fun startSessionWithPermissionCheck() {
        clearAllErrors()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingStartAfterMicGrant = true
            _events.tryEmit(FridayUiEvent.RequestMicPermission)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            _events.tryEmit(FridayUiEvent.RequestNotificationPermissionIfNeeded)
        }
        beginLiveVoiceSession()
    }

    private fun beginLiveVoiceSession() {
        if (!com.example.ai.ensureDefaultFirebaseApp(appContext)) {
            sessionManager.connect()
            return
        }
        FridayVisionService.startVoiceService(appContext)
        _isMuted.value = false
        audioStreamer.setMuted(false)
        sessionManager.connect()
    }

    fun disconnectSession() {
        sessionManager.disconnect()
        FridayVisionService.stopCamera(appContext)
        FridayVisionService.stopScreen(appContext)
        FridayVisionService.stopAll(appContext)
        _toolTelemetry.value = null
        _isMuted.value = false
    }

    fun onMuteToggle() {
        if (_uiState.value.orbState == VoiceOrbState.IDLE ||
            _uiState.value.orbState == VoiceOrbState.ERROR
        ) {
            return
        }
        val next = !_isMuted.value
        _isMuted.value = next
        audioStreamer.setMuted(next)
    }

    fun onCameraToggle() {
        viewModelScope.launch {
            val enable = !VisionFrameBus.isCameraActive.value
            requestCameraVision(enabled = enable, useFrontCamera = null)
        }
    }

    fun onFlipCamera() {
        if (!VisionFrameBus.isCameraActive.value) return
        val nextFront = !VisionFrameBus.useFrontCamera.value
        FridayVisionService.switchCamera(appContext, nextFront)
    }

    fun onScreenToggle() {
        viewModelScope.launch {
            val enable = !VisionFrameBus.isScreenActive.value
            requestScreenVision(enabled = enable)
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        val shouldStart = pendingStartAfterMicGrant
        pendingStartAfterMicGrant = false
        if (granted) {
            if (shouldStart) {
                beginLiveVoiceSession()
            }
        } else {
            val message = appContext.getString(R.string.error_mic_permission)
            _localErrorState.value = true
            _localErrorMessage.value = message
            _uiState.update {
                it.copy(
                    orbState = VoiceOrbState.ERROR,
                    errorMessage = message
                )
            }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        val deferred = pendingCameraContinuation
        pendingCameraContinuation = null
        if (granted) {
            val useFront = pendingCameraFrontTarget ?: VisionFrameBus.useFrontCamera.value
            FridayVisionService.startCamera(appContext, useFront)
            deferred?.complete(true)
        } else {
            val message = appContext.getString(R.string.error_camera_permission)
            _localErrorMessage.value = message
            _uiState.update {
                it.copy(errorMessage = message)
            }
            deferred?.complete(false)
        }
        pendingCameraFrontTarget = null
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        val deferred = pendingScreenContinuation
        pendingScreenContinuation = null
        if (data != null) {
            FridayVisionService.startScreen(appContext, resultCode, data)
            deferred?.complete(resultCode to data)
        } else {
            val message = appContext.getString(R.string.error_screen_denied)
            _localErrorMessage.value = message
            _uiState.update {
                it.copy(errorMessage = message)
            }
            deferred?.complete(null)
        }
    }

    fun dismissError() {
        clearAllErrors()
    }

    private fun clearAllErrors() {
        sessionManager.clearError()
        _localErrorState.value = false
        _localErrorMessage.value = null
        _uiState.update { current ->
            current.copy(
                orbState = if (current.orbState == VoiceOrbState.ERROR) VoiceOrbState.IDLE else current.orbState,
                errorMessage = null
            )
        }
    }

    override fun onToolTelemetry(label: String?) {
        _toolTelemetry.value = label
    }

    override fun requestOpenWebsite(url: String): Boolean {
        return FridayToolExecutor.launchBrowser(appContext, url)
    }

    override suspend fun requestCameraVision(enabled: Boolean, useFrontCamera: Boolean?): String {
        if (!enabled) {
            FridayVisionService.stopCamera(appContext)
            return "Camera vision eyes turned off."
        }

        val targetFront = useFrontCamera ?: VisionFrameBus.useFrontCamera.value
        if (VisionFrameBus.isCameraActive.value) {
            if (useFrontCamera != null && useFrontCamera != VisionFrameBus.useFrontCamera.value) {
                FridayVisionService.switchCamera(appContext, targetFront)
                return if (targetFront) {
                    "Switched to front selfie camera."
                } else {
                    "Switched to rear main camera."
                }
            }
            return "Camera vision eyes are already active."
        }

        if (hasPermission(Manifest.permission.CAMERA)) {
            FridayVisionService.startCamera(appContext, targetFront)
            return if (targetFront) {
                "Front camera vision eyes activated."
            } else {
                "Rear camera vision eyes activated."
            }
        }

        val deferred = CompletableDeferred<Boolean>()
        pendingCameraContinuation = deferred
        pendingCameraFrontTarget = targetFront
        _events.tryEmit(FridayUiEvent.RequestCameraPermission)
        val granted = deferred.await()
        return if (granted) {
            "Camera permission granted and camera eyes are now active."
        } else {
            "Camera permission was denied by the user."
        }
    }

    override suspend fun requestScreenVision(enabled: Boolean): String {
        if (!enabled) {
            FridayVisionService.stopScreen(appContext)
            return "Screen vision eyes turned off."
        }

        if (VisionFrameBus.isScreenActive.value) {
            return "Screen vision eyes are already active."
        }

        val deferred = CompletableDeferred<Pair<Int, Intent>?>()
        pendingScreenContinuation = deferred
        _events.tryEmit(FridayUiEvent.RequestScreenCapture)
        val result = deferred.await()
        return if (result != null) {
            "Screen vision eyes activated. You can now see what is on the user's screen."
        } else {
            "Screen capture permission was declined by the user."
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onCleared() {
        Log.d(TAG, "ViewModel cleared, shutting down session")
        FridayVisionService.onStopRequestedByNotification = null
        disconnectSession()
        super.onCleared()
    }

    private data class SessionVisionSnapshot(
        val connectionState: LiveConnectionState,
        val isMuted: Boolean,
        val isCameraActive: Boolean,
        val isFrontCamera: Boolean,
        val isScreenActive: Boolean
    )

    private data class TelemetryErrorSnapshot(
        val inputLevel: Float,
        val outputLevel: Float,
        val toolTelemetry: String?,
        val errorMessage: String?
    )

    companion object {
        private const val FIREBASE_CONFIG_ERROR =
            "Firebase is not configured in this APK. Ensure app/google-services.json is included when building."
    }
}
