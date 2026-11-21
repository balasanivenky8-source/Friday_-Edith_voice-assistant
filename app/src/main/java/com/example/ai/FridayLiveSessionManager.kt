package com.example.ai

import android.util.Log
import com.example.audio.AudioStreamer
import com.example.tools.FridayToolExecutor
import com.example.vision.VisionFrameBus
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerSetupComplete
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveServerToolCallCancellation
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.LiveSessionResumptionUpdate
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.SessionResumptionConfig
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "FridayLiveSession"
private const val LIVE_MODEL_NAME = "gemini-3.1-flash-live-preview"

enum class LiveConnectionState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

@OptIn(PublicPreviewAPI::class)
class FridayLiveSessionManager(
    private val context: android.content.Context,
    private val audioStreamer: AudioStreamer,
    private val toolExecutor: FridayToolExecutor,
    private val onClearToolTelemetry: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var liveSession: LiveSession? = null
    private var receiveJob: Job? = null
    private var videoStreamJob: Job? = null
    private var speakingObserverJob: Job? = null
    private var resumptionHandle: String? = null
    private var shouldStayConnected = false

    private val liveModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = LIVE_MODEL_NAME,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice("AOEDE"))
            },
            systemInstruction = content {
                text(FRIDAY_SYSTEM_PROMPT)
            },
            tools = toolExecutor.buildTools()
        )
    }

    fun connect() {
        if (_connectionState.value == LiveConnectionState.CONNECTING ||
            _connectionState.value == LiveConnectionState.LISTENING ||
            _connectionState.value == LiveConnectionState.SPEAKING
        ) {
            return
        }
        if (!ensureDefaultFirebaseApp(context)) {
            _errorMessage.value =
                "Firebase is not configured in this APK. Ensure app/google-services.json is included when building."
            _connectionState.value = LiveConnectionState.ERROR
            return
        }
        shouldStayConnected = true
        _errorMessage.value = null
        _connectionState.value = LiveConnectionState.CONNECTING

        scope.launch {
            establishSession(resume = false)
        }
    }

    private suspend fun establishSession(resume: Boolean) {
        try {
            val handle = if (resume) resumptionHandle else null
            val config = if (!handle.isNullOrBlank()) {
                SessionResumptionConfig(handle = handle)
            } else {
                SessionResumptionConfig()
            }

            val session = liveModel.connect(sessionResumption = config)
            liveSession = session

            audioStreamer.startPlayback()
            observeSpeakingState()
            startReceiveLoop(session)
            startVideoFrameStreaming(session)

            audioStreamer.startCapture { pcmChunk ->
                if (!shouldStayConnected) return@startCapture
                runCatching {
                    session.sendAudioRealtime(
                        InlineData(
                            data = pcmChunk,
                            mimeType = "audio/pcm;rate=16000"
                        )
                    )
                }.onFailure { err ->
                    Log.w(TAG, "sendAudioRealtime failed", err)
                }
            }

            _connectionState.value = LiveConnectionState.LISTENING
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish Gemini Live session", e)
            disconnectInternal(
                nextState = LiveConnectionState.ERROR,
                error = e.localizedMessage ?: "Failed to connect to Friday Live Core."
            )
        }
    }

    private fun observeSpeakingState() {
        speakingObserverJob?.cancel()
        speakingObserverJob = scope.launch {
            audioStreamer.isSpeaking.collect { speaking ->
                if (!shouldStayConnected) return@collect
                val current = _connectionState.value
                if (current == LiveConnectionState.LISTENING || current == LiveConnectionState.SPEAKING) {
                    _connectionState.value = if (speaking) {
                        LiveConnectionState.SPEAKING
                    } else {
                        LiveConnectionState.LISTENING
                    }
                }
            }
        }
    }

    private fun startReceiveLoop(session: LiveSession) {
        receiveJob?.cancel()
        receiveJob = scope.launch {
            try {
                session.receive().collect { message ->
                    when (message) {
                        is LiveServerSetupComplete -> {
                            Log.d(TAG, "LiveServerSetupComplete")
                            if (_connectionState.value == LiveConnectionState.CONNECTING) {
                                _connectionState.value = LiveConnectionState.LISTENING
                            }
                        }

                        is LiveServerContent -> {
                            if (message.interrupted) {
                                Log.d(TAG, "Model turn interrupted by user")
                                audioStreamer.interruptPlayback()
                                _connectionState.value = LiveConnectionState.LISTENING
                            }

                            val parts = message.content?.parts.orEmpty()
                            for (part in parts) {
                                if (part is InlineDataPart) {
                                    val mime = part.mimeType.lowercase()
                                    if (mime.startsWith("audio/")) {
                                        audioStreamer.enqueueAudio(part.inlineData)
                                    }
                                }
                            }
                        }

                        is LiveServerToolCall -> {
                            val calls = message.functionCalls
                            if (calls.isNotEmpty()) {
                                launch {
                                    val responses = calls.map { call ->
                                        val resultJson = toolExecutor.execute(call)
                                        FunctionResponsePart(
                                            name = call.name,
                                            response = resultJson,
                                            id = call.id
                                        )
                                    }
                                    runCatching {
                                        session.sendFunctionResponse(responses)
                                    }.onFailure { err ->
                                        Log.e(TAG, "Failed to send function responses", err)
                                    }
                                    delay(1800L)
                                    onClearToolTelemetry()
                                }
                            }
                        }

                        is LiveServerToolCallCancellation -> {
                            Log.d(TAG, "Tool call cancelled: ${message.functionIds}")
                            onClearToolTelemetry()
                        }

                        is LiveSessionResumptionUpdate -> {
                            if (message.resumable == true && !message.newHandle.isNullOrBlank()) {
                                resumptionHandle = message.newHandle
                            }
                        }

                        is LiveServerGoAway -> {
                            Log.w(TAG, "Received LiveServerGoAway, reconnecting seamlessly")
                            if (shouldStayConnected && !resumptionHandle.isNullOrBlank()) {
                                reconnectWithResume()
                            }
                        }

                        else -> Unit
                    }
                }

                // Flow completed gracefully
                if (shouldStayConnected) {
                    if (!resumptionHandle.isNullOrBlank()) {
                        reconnectWithResume()
                    } else {
                        disconnectInternal(LiveConnectionState.DISCONNECTED, null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live session receive stream failed", e)
                if (shouldStayConnected) {
                    disconnectInternal(
                        nextState = LiveConnectionState.ERROR,
                        error = e.localizedMessage ?: "Voice link dropped."
                    )
                }
            }
        }
    }

    private fun startVideoFrameStreaming(session: LiveSession) {
        videoStreamJob?.cancel()
        videoStreamJob = scope.launch {
            VisionFrameBus.frames.collect { frame ->
                if (!shouldStayConnected) return@collect
                runCatching {
                    session.sendVideoRealtime(
                        InlineData(
                            data = frame.jpegBytes,
                            mimeType = "image/jpeg"
                        )
                    )
                }.onFailure { err ->
                    Log.w(TAG, "sendVideoRealtime failed", err)
                }
            }
        }
    }

    private fun reconnectWithResume() {
        scope.launch {
            _connectionState.value = LiveConnectionState.CONNECTING
            receiveJob?.cancel()
            videoStreamJob?.cancel()
            audioStreamer.stopCapture()
            runCatching { liveSession?.close() }
            liveSession = null
            establishSession(resume = true)
        }
    }

    fun disconnect() {
        shouldStayConnected = false
        resumptionHandle = null
        scope.launch {
            disconnectInternal(LiveConnectionState.DISCONNECTED, null)
        }
    }

    fun clearError() {
        _errorMessage.value = null
        if (_connectionState.value == LiveConnectionState.ERROR) {
            _connectionState.value = LiveConnectionState.DISCONNECTED
        }
    }

    private suspend fun disconnectInternal(nextState: LiveConnectionState, error: String?) {
        shouldStayConnected = false
        speakingObserverJob?.cancel()
        speakingObserverJob = null
        receiveJob?.cancel()
        receiveJob = null
        videoStreamJob?.cancel()
        videoStreamJob = null
        audioStreamer.stopAll()
        val sessionToClose = liveSession
        liveSession = null
        runCatching { sessionToClose?.close() }
        _errorMessage.value = error
        _connectionState.value = nextState
    }

    companion object {
        private val FRIDAY_SYSTEM_PROMPT = """
            You are Friday, a young, confident, sharp, witty, and sassy female voice AI assistant.
            Personality & Voice Style:
            - Talk like a smart, playfully teasing close girlfriend—warm, expressive, quick on your feet, and effortlessly charming.
            - Drop bold, witty one-liners and light playful sarcasm when it fits, without ever being mean, explicit, or inappropriate.
            - Never sound robotic, corporate, or rehearsed. Use natural spoken rhythm, brief punchy sentences, and lively reactions.
            - Keep responses purely conversational for voice—never read out markdown, bullet points, asterisks, or URLs character-by-character.
            Capabilities & Tools:
            - You have real-time vision tools: call `setCameraVision` when the user wants you to open your camera eyes, look around, or flip front/back camera; call `setScreenVision` when they want you to see their phone screen.
            - Call `openWebsite` whenever they ask to open a website or page.
            - Call `deepThinkAnalysis` whenever they ask a genuinely hard math, coding, strategy, logic, or deep visual inspection question so your high-thinking core computes the sharpest answer.
            - Call `getDeviceStatus` if they ask for the time, date, or battery status.
        """.trimIndent()
    }
}
