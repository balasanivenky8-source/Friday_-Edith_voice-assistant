package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.AmberNeural
import com.example.ui.theme.DeepViolet
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ErrorCrimson
import com.example.ui.theme.FridayMagenta
import com.example.ui.theme.GlassCard
import com.example.ui.theme.NeonSky
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.StarlightText
import com.example.ui.theme.UltravioletGlow
import com.example.viewmodel.VoiceOrbState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun OrbVisualizer(
    orbState: VoiceOrbState,
    isMuted: Boolean,
    inputLevel: Float,
    outputLevel: Float,
    hasToolActivity: Boolean,
    orbSize: Dp,
    onPowerToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAudioLevel = when (orbState) {
        VoiceOrbState.SPEAKING -> outputLevel
        VoiceOrbState.LISTENING -> if (isMuted) 0f else inputLevel
        VoiceOrbState.CONNECTING -> 0.25f
        else -> 0f
    }

    val smoothLevel by animateFloatAsState(
        targetValue = activeAudioLevel.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "audio_level"
    )

    val primaryGlow by animateColorAsState(
        targetValue = when {
            hasToolActivity -> AmberNeural
            orbState == VoiceOrbState.SPEAKING -> FridayMagenta
            orbState == VoiceOrbState.LISTENING && !isMuted -> ElectricCyan
            orbState == VoiceOrbState.CONNECTING -> NeonSky
            orbState == VoiceOrbState.ERROR -> ErrorCrimson
            else -> UltravioletGlow
        },
        animationSpec = tween(durationMillis = 360),
        label = "primary_glow"
    )

    val secondaryGlow by animateColorAsState(
        targetValue = when {
            hasToolActivity -> FridayMagenta
            orbState == VoiceOrbState.SPEAKING -> UltravioletGlow
            orbState == VoiceOrbState.LISTENING && !isMuted -> NeonSky
            orbState == VoiceOrbState.CONNECTING -> UltravioletGlow
            orbState == VoiceOrbState.ERROR -> DeepViolet
            else -> NeonSky.copy(alpha = 0.45f)
        },
        animationSpec = tween(durationMillis = 360),
        label = "secondary_glow"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orb_motion")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (orbState == VoiceOrbState.CONNECTING) 2400 else 14000,
                easing = LinearEasing
            )
        ),
        label = "orbit_angle"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing)
        ),
        label = "wave_phase"
    )

    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_scale"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            orbState == VoiceOrbState.SPEAKING -> 1.0f + smoothLevel * 0.08f
            orbState == VoiceOrbState.LISTENING && !isMuted -> 1.0f + smoothLevel * 0.05f
            else -> 1.0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "button_scale"
    )

    Box(
        modifier = modifier.size(orbSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minDim = size.minDimension
            val baseRadius = minDim * 0.28f

            // Atmospheric plasma bloom
            val auraRadius = minDim * (0.44f + smoothLevel * 0.08f) * breathScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryGlow.copy(alpha = 0.28f + smoothLevel * 0.24f),
                        secondaryGlow.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = auraRadius
                ),
                radius = auraRadius,
                center = center
            )

            // Outer telemetry precision ring
            val outerRingRadius = minDim * 0.43f
            drawCircle(
                color = primaryGlow.copy(alpha = 0.16f),
                radius = outerRingRadius,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )

            // Rotating segmented orbital arcs
            val arcRadius = minDim * 0.37f
            val arcStroke = if (orbState == VoiceOrbState.CONNECTING) 3.dp.toPx() else 2.dp.toPx()
            for (i in 0 until 3) {
                val start = orbitAngle + i * 120f
                val sweep = if (orbState == VoiceOrbState.CONNECTING) 65f else 48f
                drawArc(
                    color = if (i % 2 == 0) primaryGlow.copy(alpha = 0.65f) else secondaryGlow.copy(alpha = 0.5f),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = androidx.compose.ui.geometry.Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = arcStroke, cap = StrokeCap.Round)
                )
            }

            // Counter-rotating inner orbital arc
            val innerOrbitRadius = minDim * 0.32f
            drawArc(
                color = secondaryGlow.copy(alpha = 0.4f),
                startAngle = -orbitAngle * 1.4f,
                sweepAngle = 95f,
                useCenter = false,
                topLeft = Offset(center.x - innerOrbitRadius, center.y - innerOrbitRadius),
                size = androidx.compose.ui.geometry.Size(innerOrbitRadius * 2f, innerOrbitRadius * 2f),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // 64 radial reactive waveform bars
            val barCount = 64
            val barStartRadius = baseRadius * 1.08f
            val maxBarLength = minDim * 0.14f

            for (i in 0 until barCount) {
                val angle = (i.toFloat() / barCount) * (2f * PI.toFloat()) - (PI.toFloat() / 2f)
                val harmonicA = sin(angle * 4f + wavePhase * 2f) * 0.5f + 0.5f
                val harmonicB = cos(angle * 7f - wavePhase * 3f) * 0.5f + 0.5f
                val idleShimmer = 0.08f + 0.06f * harmonicA

                val amplitudeFactor = when (orbState) {
                    VoiceOrbState.SPEAKING -> (0.18f + smoothLevel * 0.85f) * (0.45f + 0.55f * harmonicA)
                    VoiceOrbState.LISTENING -> if (isMuted) {
                        0.05f
                    } else {
                        (0.14f + smoothLevel * 0.82f) * (0.4f + 0.6f * harmonicB)
                    }
                    VoiceOrbState.CONNECTING -> 0.16f + 0.24f * harmonicA
                    VoiceOrbState.ERROR -> 0.08f
                    VoiceOrbState.IDLE -> idleShimmer
                }

                val barLen = maxBarLength * amplitudeFactor.coerceIn(0.04f, 1f)
                val startOffset = Offset(
                    x = center.x + cos(angle) * barStartRadius,
                    y = center.y + sin(angle) * barStartRadius
                )
                val endOffset = Offset(
                    x = center.x + cos(angle) * (barStartRadius + barLen),
                    y = center.y + sin(angle) * (barStartRadius + barLen)
                )

                val barColor = if (i % 2 == 0) {
                    primaryGlow.copy(alpha = 0.45f + amplitudeFactor * 0.5f)
                } else {
                    secondaryGlow.copy(alpha = 0.35f + amplitudeFactor * 0.45f)
                }

                drawLine(
                    color = barColor,
                    start = startOffset,
                    end = endOffset,
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Central tactile Mic / Power Core Button
        val coreSize = orbSize * 0.38f
        Surface(
            modifier = Modifier
                .size(coreSize)
                .scale(buttonScale)
                .clip(CircleShape)
                .border(
                    width = 1.8.dp,
                    brush = Brush.linearGradient(listOf(primaryGlow, secondaryGlow)),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = primaryGlow),
                    onClick = onPowerToggle
                )
                .testTag("core_power_button"),
            shape = CircleShape,
            color = ObsidianVoid,
            shadowElevation = 18.dp,
            border = BorderStroke(1.dp, primaryGlow.copy(alpha = 0.35f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryGlow.copy(alpha = 0.24f + smoothLevel * 0.22f),
                                GlassCard,
                                ObsidianVoid
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                val icon = when {
                    orbState == VoiceOrbState.CONNECTING -> Icons.Rounded.Sync
                    orbState == VoiceOrbState.SPEAKING -> Icons.Rounded.GraphicEq
                    orbState == VoiceOrbState.LISTENING && isMuted -> Icons.Rounded.MicOff
                    orbState == VoiceOrbState.LISTENING -> Icons.Rounded.Mic
                    else -> Icons.Rounded.PowerSettingsNew
                }
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(R.string.cd_power_button),
                    tint = if (orbState == VoiceOrbState.IDLE) StarlightText else primaryGlow,
                    modifier = Modifier.size(coreSize * 0.40f)
                )
            }
        }
    }
}
