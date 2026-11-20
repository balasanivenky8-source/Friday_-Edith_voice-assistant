package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

private const val TAG = "AudioStreamer"
private const val INPUT_SAMPLE_RATE = 16_000
private const val OUTPUT_SAMPLE_RATE = 24_000
private const val CAPTURE_CHUNK_BYTES = 1280 // 40ms of 16kHz 16-bit mono PCM

class AudioStreamer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val isMuted = AtomicBoolean(false)
    private val isCapturing = AtomicBoolean(false)
    private val playbackEpoch = AtomicLong(0L)

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var playbackChannel = Channel<Pair<Long, ByteArray>>(Channel.UNLIMITED)
    private var idleResetJob: Job? = null

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        if (muted) {
            _inputLevel.value = 0f
        }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(onAudioChunk: suspend (ByteArray) -> Unit) {
        if (isCapturing.getAndSet(true)) return

        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CAPTURE_CHUNK_BYTES * 4)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            isCapturing.set(false)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            isCapturing.set(false)
            return
        }

        val sessionId = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    echoCanceler = this
                }
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    noiseSuppressor = this
                }
            }
        }

        audioRecord = record
        record.startRecording()

        captureJob = scope.launch {
            val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
            var smoothedLevel = 0f
            try {
                while (isActive && isCapturing.get()) {
                    val bytesRead = record.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        if (isMuted.get()) {
                            _inputLevel.value = 0f
                            continue
                        }
                        val chunk = if (bytesRead == buffer.size) {
                            buffer.copyOf()
                        } else {
                            buffer.copyOf(bytesRead)
                        }
                        val rms = computePcm16Rms(chunk)
                        smoothedLevel = smoothedLevel * 0.55f + rms * 0.45f
                        _inputLevel.value = smoothedLevel
                        onAudioChunk(chunk)
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read error: $bytesRead")
                        delay(20L)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture loop failed", e)
            } finally {
                _inputLevel.value = 0f
            }
        }
    }

    fun startPlayback() {
        if (audioTrack != null) return

        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(OUTPUT_SAMPLE_RATE / 5 * 2)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(OUTPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize")
            track.release()
            return
        }

        audioTrack = track
        track.play()

        playbackChannel = Channel(Channel.UNLIMITED)
        playbackJob = scope.launch {
            var smoothedOut = 0f
            try {
                for ((epoch, chunk) in playbackChannel) {
                    if (!isActive) break
                    if (epoch != playbackEpoch.get() || chunk.isEmpty()) continue

                    idleResetJob?.cancel()
                    _isSpeaking.value = true

                    val rms = computePcm16Rms(chunk)
                    smoothedOut = smoothedOut * 0.45f + rms * 0.55f
                    _outputLevel.value = smoothedOut

                    var offset = 0
                    while (offset < chunk.size && isActive && epoch == playbackEpoch.get()) {
                        val written = track.write(
                            chunk,
                            offset,
                            chunk.size - offset,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }

                    idleResetJob = scope.launch {
                        delay(260L)
                        _outputLevel.value = 0f
                        _isSpeaking.value = false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback loop error", e)
            }
        }
    }

    fun enqueueAudio(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) return
        if (audioTrack == null) {
            startPlayback()
        }
        val currentEpoch = playbackEpoch.get()
        playbackChannel.trySend(currentEpoch to pcmBytes)
    }

    fun interruptPlayback() {
        playbackEpoch.incrementAndGet()
        idleResetJob?.cancel()
        while (playbackChannel.tryReceive().isSuccess) {
            // Drain queued stale audio chunks immediately
        }
        audioTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        }
        _outputLevel.value = 0f
        _isSpeaking.value = false
    }

    fun stopCapture() {
        isCapturing.set(false)
        captureJob?.cancel()
        captureJob = null

        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        audioRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            }
            runCatching { record.release() }
        }
        audioRecord = null
        _inputLevel.value = 0f
    }

    fun stopPlayback() {
        playbackEpoch.incrementAndGet()
        idleResetJob?.cancel()
        playbackJob?.cancel()
        playbackJob = null
        playbackChannel.close()

        audioTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
            }
            runCatching { track.release() }
        }
        audioTrack = null
        _outputLevel.value = 0f
        _isSpeaking.value = false
    }

    fun stopAll() {
        stopCapture()
        stopPlayback()
    }

    private fun computePcm16Rms(pcmBytes: ByteArray): Float {
        if (pcmBytes.size < 2) return 0f
        var sumSq = 0.0
        val sampleCount = pcmBytes.size / 2
        var i = 0
        while (i + 1 < pcmBytes.size) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample / 32768.0
            sumSq += normalized * normalized
            i += 2
        }
        val rms = sqrt(sumSq / sampleCount.coerceAtLeast(1)).toFloat()
        return (rms * 3.8f).coerceIn(0f, 1f)
    }
}
