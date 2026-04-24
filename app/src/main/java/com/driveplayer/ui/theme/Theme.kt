package com.driveplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColorScheme = darkColorScheme(
    primary          = AccentPrimary,
    secondary        = AccentSecondary,
    background       = DarkBackground,
    surface          = SurfaceVariant,
    surfaceVariant   = CardSurface,
    onPrimary        = TextPrimary,
    onSecondary      = TextPrimary,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error            = ColorError,
)

@Composable
fun DrivePlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography  = AppTypography,
        content     = content
    )
}
