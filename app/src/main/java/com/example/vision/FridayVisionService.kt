package com.example.vision

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.MainActivity
import com.example.R
import java.lang.ref.WeakReference

private const val TAG = "FridayVisionService"
private const val CHANNEL_ID = "friday_live_core_channel"
private const val NOTIFICATION_ID = 4041

class FridayVisionService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var cameraStreamer: CameraFrameStreamer
    private lateinit var screenStreamer: ScreenFrameStreamer

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        cameraStreamer = CameraFrameStreamer(applicationContext, this)
        screenStreamer = ScreenFrameStreamer(applicationContext)
        createNotificationChannel()
        activeServiceRef = WeakReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VOICE -> {
                promoteForeground(includeCamera = false, includeMediaProjection = false)
            }
            ACTION_START_CAMERA -> {
                val useFront = intent.getBooleanExtra(EXTRA_USE_FRONT, false)
                if (hasCameraPermission()) {
                    promoteForeground(
                        includeCamera = true,
                        includeMediaProjection = VisionFrameBus.isScreenActive.value
                    )
                    cameraStreamer.start(useFront, pendingPreviewProvider)
                }
            }
            ACTION_SWITCH_CAMERA -> {
                val useFront = intent.getBooleanExtra(EXTRA_USE_FRONT, false)
                cameraStreamer.switchCamera(useFront)
            }
            ACTION_STOP_CAMERA -> {
                cameraStreamer.stop()
                promoteForeground(
                    includeCamera = false,
                    includeMediaProjection = VisionFrameBus.isScreenActive.value
                )
            }
            ACTION_START_SCREEN -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA_INTENT)
                }
                if (resultCode != Int.MIN_VALUE && dataIntent != null) {
                    // Android 14+ requires foreground service with MEDIA_PROJECTION type before getMediaProjection
                    promoteForeground(
                        includeCamera = VisionFrameBus.isCameraActive.value && hasCameraPermission(),
                        includeMediaProjection = true
                    )
                    val projectionManager = getSystemService(MediaProjectionManager::class.java)
                    val projection = runCatching {
                        projectionManager.getMediaProjection(resultCode, dataIntent)
                    }.onFailure {
                        Log.e(TAG, "Failed to obtain MediaProjection", it)
                    }.getOrNull()

                    if (projection != null) {
                        screenStreamer.start(projection) {
                            promoteForeground(
                                includeCamera = VisionFrameBus.isCameraActive.value && hasCameraPermission(),
                                includeMediaProjection = false
                            )
                        }
                    } else {
                        VisionFrameBus.setScreenActive(false)
                        promoteForeground(
                            includeCamera = VisionFrameBus.isCameraActive.value && hasCameraPermission(),
                            includeMediaProjection = false
                        )
                    }
                }
            }
            ACTION_STOP_SCREEN -> {
                screenStreamer.stop()
                promoteForeground(
                    includeCamera = VisionFrameBus.isCameraActive.value && hasCameraPermission(),
                    includeMediaProjection = false
                )
            }
            ACTION_STOP_ALL -> {
                onStopRequestedByNotification?.invoke()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteForeground(includeCamera: Boolean, includeMediaProjection: Boolean) {
        var types = 0
        if (hasMicPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (includeCamera && hasCameraPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (includeMediaProjection) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

        val notification = buildNotification(
            cameraActive = includeCamera,
            screenActive = includeMediaProjection
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground with types=$types", e)
        }
    }

    private fun buildNotification(cameraActive: Boolean, screenActive: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FridayVisionService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val textRes = when {
            cameraActive && screenActive -> R.string.notification_text_both
            cameraActive -> R.string.notification_text_camera
            screenActive -> R.string.notification_text_screen
            else -> R.string.notification_text_voice
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(textRes))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "Disconnect", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        pendingPreviewProvider = null
        activeServiceRef = null
        runCatching { cameraStreamer.release() }
        runCatching { screenStreamer.stop() }
        VisionFrameBus.clearAll()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_VOICE = "com.example.vision.ACTION_START_VOICE"
        const val ACTION_START_CAMERA = "com.example.vision.ACTION_START_CAMERA"
        const val ACTION_SWITCH_CAMERA = "com.example.vision.ACTION_SWITCH_CAMERA"
        const val ACTION_STOP_CAMERA = "com.example.vision.ACTION_STOP_CAMERA"
        const val ACTION_START_SCREEN = "com.example.vision.ACTION_START_SCREEN"
        const val ACTION_STOP_SCREEN = "com.example.vision.ACTION_STOP_SCREEN"
        const val ACTION_STOP_ALL = "com.example.vision.ACTION_STOP_ALL"

        const val EXTRA_USE_FRONT = "extra_use_front"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA_INTENT = "extra_data_intent"

        private var activeServiceRef: WeakReference<FridayVisionService>? = null
        private var pendingPreviewProvider: Preview.SurfaceProvider? = null
        var onStopRequestedByNotification: (() -> Unit)? = null

        fun attachCameraPreview(provider: Preview.SurfaceProvider?) {
            pendingPreviewProvider = provider
            activeServiceRef?.get()?.cameraStreamer?.attachPreview(provider)
        }

        fun startVoiceService(context: Context) {
            val intent = Intent(context, FridayVisionService::class.java).apply {
                action = ACTION_START_VOICE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startCamera(context: Context, useFront: Boolean) {
            val intent = Intent(context, FridayVisionService::class.java).apply {
                action = ACTION_START_CAMERA
                putExtra(EXTRA_USE_FRONT, useFront)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun switchCamera(context: Context, useFront: Boolean) {
            val intent = Intent(context, FridayVisionService::class.java).apply {
                action = ACTION_SWITCH_CAMERA
                putExtra(EXTRA_USE_FRONT, useFront)
            }
            context.startService(intent)
        }

        fun stopCamera(context: Context) {
            val intent = Intent(context, FridayVisionService::class.java).apply {
                action = ACTION_STOP_CAMERA
            }
            context.startService(intent)
        }

        fun startScreen(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, FridayVisionService::class.java).apply {
                action = ACTION_START_SCREEN
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopScreen(context: Context) {
            val intent = Intent(context, FridayVisionService::class.java).apply {
                action = ACTION_STOP_SCREEN
            }
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            val intent = Intent(context, FridayVisionService::class.java).apply {
                action = ACTION_STOP_ALL
            }
            runCatching { context.stopService(intent) }
        }
    }
}
