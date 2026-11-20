package com.example.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.StopScreenShare
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.ui.theme.AmberNeural
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ErrorCrimson
import com.example.ui.theme.FridayMagenta
import com.example.ui.theme.GlassCard
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.MidnightSurface
import com.example.ui.theme.MutedSlate
import com.example.ui.theme.NeonSky
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.StarlightText
import com.example.ui.theme.UltravioletGlow
import com.example.viewmodel.FridayUiState
import com.example.viewmodel.VoiceOrbState
import com.example.vision.FridayVisionService

@Composable
fun FridayScreen(
    uiState: FridayUiState,
    onPowerToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onCameraToggle: () -> Unit,
    onFlipCamera: () -> Unit,
    onScreenToggle: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLiveSession = uiState.orbState == VoiceOrbState.CONNECTING ||
        uiState.orbState == VoiceOrbState.LISTENING ||
        uiState.orbState == VoiceOrbState.SPEAKING

    val accentColor by animateColorAsState(
        targetValue = when {
            uiState.toolTelemetry != null -> AmberNeural
            uiState.orbState == VoiceOrbState.SPEAKING -> FridayMagenta
            uiState.orbState == VoiceOrbState.LISTENING && !uiState.isMuted -> ElectricCyan
            uiState.orbState == VoiceOrbState.CONNECTING -> NeonSky
            uiState.orbState == VoiceOrbState.ERROR -> ErrorCrimson
            else -> UltravioletGlow
        },
        label = "accent_color"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid)
    ) {
        // Atmospheric futuristic background glow & subtle telemetry grid
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        ObsidianVoid,
                        MidnightSurface,
                        ObsidianVoid
                    )
                )
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.46f),
                    radius = size.minDimension * 0.65f
                ),
                radius = size.minDimension * 0.65f,
                center = Offset(size.width * 0.5f, size.height * 0.46f)
            )
            // Subtle horizon rings
            val step = 72.dp.toPx()
            var y = step
            while (y < size.height) {
                drawLine(
                    color = Color.White.copy(alpha = 0.025f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += step
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            val isWide = maxWidth > 620.dp
            val orbDiameter: Dp = (minOf(maxWidth, maxHeight) * if (isWide) 0.56f else 0.78f)
                .coerceIn(220.dp, 360.dp)

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top HUD bar
                TopTelemetryHeader(
                    uiState = uiState,
                    accentColor = accentColor,
                    onFlipCamera = onFlipCamera
                )

                // Error banner (only visible on real errors)
                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn() + scaleIn(initialScale = 0.96f),
                    exit = fadeOut() + scaleOut(targetScale = 0.96f)
                ) {
                    uiState.errorMessage?.let { err ->
                        ErrorTelemetryBanner(
                            message = err,
                            onRetry = onPowerToggle,
                            onDismiss = onDismissError
                        )
                    }
                }

                // Center Voice Core + state cue
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    OrbVisualizer(
                        orbState = uiState.orbState,
                        isMuted = uiState.isMuted,
                        inputLevel = uiState.inputLevel,
                        outputLevel = uiState.outputLevel,
                        hasToolActivity = uiState.toolTelemetry != null,
                        orbSize = orbDiameter,
                        onPowerToggle = onPowerToggle
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Transient status / tool telemetry pill (NOT a chat log)
                    AnimatedVisibility(
                        visible = uiState.toolTelemetry != null,
                        enter = fadeIn() + scaleIn(initialScale = 0.9f),
                        exit = fadeOut() + scaleOut(targetScale = 0.9f)
                    ) {
                        uiState.toolTelemetry?.let { telemetry ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = AmberNeural.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, AmberNeural.copy(alpha = 0.5f)),
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                Text(
                                    text = telemetry,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AmberNeural,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    val hintText = when {
                        uiState.orbState == VoiceOrbState.CONNECTING ->
                            stringResource(R.string.hint_connecting)
                        uiState.orbState == VoiceOrbState.SPEAKING ->
                            stringResource(R.string.hint_speaking)
                        uiState.orbState == VoiceOrbState.LISTENING && uiState.isMuted ->
                            stringResource(R.string.hint_muted)
                        uiState.orbState == VoiceOrbState.LISTENING ->
                            stringResource(R.string.hint_listening)
                        else -> stringResource(R.string.hint_tap_wake)
                    }

                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedSlate,
                        textAlign = TextAlign.Center
                    )
                }

                // Bottom futuristic control dock
                BottomControlDock(
                    isLiveSession = isLiveSession,
                    isMuted = uiState.isMuted,
                    isCameraActive = uiState.isCameraActive,
                    isScreenActive = uiState.isScreenActive,
                    onCameraToggle = onCameraToggle,
                    onMuteToggle = onMuteToggle,
                    onScreenToggle = onScreenToggle
                )
            }
        }
    }
}

@Composable
private fun TopTelemetryHeader(
    uiState: FridayUiState,
    accentColor: Color,
    onFlipCamera: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.friday_wordmark),
                    style = MaterialTheme.typography.displaySmall,
                    color = StarlightText
                )
                StatusBadge(
                    orbState = uiState.orbState,
                    isMuted = uiState.isMuted,
                    accentColor = accentColor
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.friday_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlate
                )
                if (uiState.isCameraActive) {
                    VisionTagPill(
                        text = stringResource(R.string.vision_camera_active),
                        color = ElectricCyan
                    )
                }
                if (uiState.isScreenActive) {
                    VisionTagPill(
                        text = stringResource(R.string.vision_screen_active),
                        color = FridayMagenta
                    )
                }
            }
        }

        // Compact circular live CameraX preview orb when Camera Eyes are active
        AnimatedVisibility(
            visible = uiState.isCameraActive,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f)
        ) {
            CameraPreviewOrb(onFlipCamera = onFlipCamera)
        }
    }
}

@Composable
private fun StatusBadge(
    orbState: VoiceOrbState,
    isMuted: Boolean,
    accentColor: Color
) {
    val label = when {
        orbState == VoiceOrbState.CONNECTING -> stringResource(R.string.state_connecting)
        orbState == VoiceOrbState.SPEAKING -> stringResource(R.string.state_speaking)
        orbState == VoiceOrbState.LISTENING && isMuted -> stringResource(R.string.state_muted)
        orbState == VoiceOrbState.LISTENING -> stringResource(R.string.state_listening)
        orbState == VoiceOrbState.ERROR -> stringResource(R.string.state_error)
        else -> stringResource(R.string.state_disconnected)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = accentColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor
            )
        }
    }
}

@Composable
private fun VisionTagPill(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun CameraPreviewOrb(
    onFlipCamera: () -> Unit
) {
    Box(
        modifier = Modifier.size(92.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                FridayVisionService.attachCameraPreview(previewView.surfaceProvider)
            },
            modifier = Modifier
                .size(84.dp)
                .align(Alignment.TopStart)
                .clip(CircleShape)
                .border(2.dp, ElectricCyan.copy(alpha = 0.8f), CircleShape)
        )

        DisposableEffect(Unit) {
            onDispose {
                FridayVisionService.attachCameraPreview(null)
            }
        }

        FilledIconButton(
            onClick = onFlipCamera,
            modifier = Modifier
                .size(48.dp)
                .testTag("flip_camera_button"),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = GlassCard.copy(alpha = 0.92f),
                contentColor = ElectricCyan
            )
        ) {
            Icon(
                imageVector = Icons.Rounded.Cameraswitch,
                contentDescription = stringResource(R.string.cd_flip_camera),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun BottomControlDock(
    isLiveSession: Boolean,
    isMuted: Boolean,
    isCameraActive: Boolean,
    isScreenActive: Boolean,
    onCameraToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onScreenToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = GlassCard.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, GlassStroke),
        modifier = Modifier.widthIn(max = 420.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockActionButton(
                active = isCameraActive,
                enabled = true,
                activeColor = ElectricCyan,
                icon = if (isCameraActive) Icons.Rounded.Videocam else Icons.Outlined.VideocamOff,
                label = stringResource(R.string.label_camera),
                contentDescription = stringResource(R.string.cd_camera_vision),
                testTag = "camera_vision_button",
                onClick = onCameraToggle
            )

            DockActionButton(
                active = isLiveSession && isMuted,
                enabled = isLiveSession,
                activeColor = ErrorCrimson,
                icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                label = if (isMuted) stringResource(R.string.label_unmute) else stringResource(R.string.label_mute),
                contentDescription = stringResource(R.string.cd_mute_mic),
                testTag = "mute_button",
                onClick = onMuteToggle
            )

            DockActionButton(
                active = isScreenActive,
                enabled = true,
                activeColor = FridayMagenta,
                icon = if (isScreenActive) Icons.Rounded.ScreenShare else Icons.Outlined.StopScreenShare,
                label = stringResource(R.string.label_screen),
                contentDescription = stringResource(R.string.cd_screen_vision),
                testTag = "screen_vision_button",
                onClick = onScreenToggle
            )
        }
    }
}

@Composable
private fun DockActionButton(
    active: Boolean,
    enabled: Boolean,
    activeColor: Color,
    icon: ImageVector,
    label: String,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit
) {
    val containerColor = when {
        !enabled -> GlassCard.copy(alpha = 0.35f)
        active -> activeColor.copy(alpha = 0.18f)
        else -> MidnightSurface.copy(alpha = 0.8f)
    }
    val contentColor = when {
        !enabled -> MutedSlate.copy(alpha = 0.4f)
        active -> activeColor
        else -> StarlightText.copy(alpha = 0.85f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(54.dp)
                .minimumInteractiveComponentSize()
                .border(
                    width = 1.dp,
                    color = if (active) activeColor.copy(alpha = 0.65f) else GlassStroke,
                    shape = CircleShape
                )
                .testTag(testTag),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor,
                disabledContentColor = contentColor
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) activeColor else MutedSlate
        )
    }
}

@Composable
private fun ErrorTelemetryBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ErrorCrimson.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, ErrorCrimson.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 540.dp)
            .padding(top = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = StarlightText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag("retry_button")
            ) {
                Text(
                    text = stringResource(R.string.label_retry),
                    color = ElectricCyan,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dismiss_error_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_error),
                    tint = StarlightText
                )
            }
        }
    }
}
