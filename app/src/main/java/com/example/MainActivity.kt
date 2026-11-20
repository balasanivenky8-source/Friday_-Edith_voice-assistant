package com.example

import android.Manifest
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.ui.FridayScreen
import com.example.ui.theme.FridayTheme
import com.example.viewmodel.FridayUiEvent
import com.example.viewmodel.FridayViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent before UI or ViewModel creation.
        configureAppCheck(this, intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        setContent {
            FridayTheme {
                val context = LocalContext.current
                val viewModel: FridayViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.onMicPermissionResult(granted)
                }

                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.onCameraPermissionResult(granted)
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                val screenCaptureLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    viewModel.onMediaProjectionResult(result.resultCode, result.data)
                }

                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            FridayUiEvent.RequestMicPermission -> {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            FridayUiEvent.RequestCameraPermission -> {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            FridayUiEvent.RequestNotificationPermissionIfNeeded -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                            }
                            FridayUiEvent.RequestScreenCapture -> {
                                val manager = context.getSystemService(MediaProjectionManager::class.java)
                                screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                            }
                        }
                    }
                }

                FridayScreen(
                    uiState = uiState,
                    onPowerToggle = viewModel::onPowerToggle,
                    onMuteToggle = viewModel::onMuteToggle,
                    onCameraToggle = viewModel::onCameraToggle,
                    onFlipCamera = viewModel::onFlipCamera,
                    onScreenToggle = viewModel::onScreenToggle,
                    onDismissError = viewModel::dismissError
                )
            }
        }
    }
}
