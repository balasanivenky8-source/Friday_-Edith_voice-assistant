package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FridayDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = GlassCard,
    onPrimaryContainer = ElectricCyan,
    secondary = FridayMagenta,
    onSecondary = StarlightText,
    secondaryContainer = DeepViolet,
    onSecondaryContainer = StarlightText,
    tertiary = UltravioletGlow,
    onTertiary = StarlightText,
    background = ObsidianVoid,
    onBackground = StarlightText,
    surface = MidnightSurface,
    onSurface = StarlightText,
    surfaceVariant = GlassCard,
    onSurfaceVariant = MutedSlate,
    outline = GlassStroke,
    error = ErrorCrimson,
    onError = StarlightText
)

@Composable
fun FridayTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FridayDarkColorScheme,
        typography = FridayTypography,
        content = content
    )
}
