package com.driveplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driveplayer.di.AppModule

/**
 * Build the Material3 colour scheme from our internal palette so the few M3
 * widgets that read `MaterialTheme.colorScheme` directly (Switch, Slider,
 * RadioButton, etc.) match the rest of the chrome.
 */
private fun colorSchemeFor(c: AppColors) = if (c.isDark) {
    darkColorScheme(
        primary          = c.accentPrimary,
        secondary        = c.accentSecondary,
        background       = c.background,
        surface          = c.surfaceVariant,
        surfaceVariant   = c.cardSurface,
        onPrimary        = c.textPrimary,
        onSecondary      = c.textPrimary,
        onBackground     = c.textPrimary,
        onSurface        = c.textPrimary,
        onSurfaceVariant = c.textSecondary,
        error            = c.colorError,
    )
} else {
    lightColorScheme(
        primary          = c.accentPrimary,
        secondary        = c.accentSecondary,
        background       = c.background,
        surface          = c.surfaceVariant,
        surfaceVariant   = c.cardSurface,
        onPrimary        = androidx.compose.ui.graphics.Color.White,
        onSecondary      = androidx.compose.ui.graphics.Color.White,
        onBackground     = c.textPrimary,
        onSurface        = c.textPrimary,
        onSurfaceVariant = c.textSecondary,
        error            = c.colorError,
    )
}

/**
 * Top-level theme. Reads the user's theme preference live from
 * [com.driveplayer.data.SettingsStore] so changing the toggle in Settings
 * recolours every screen on the next frame — no app restart, no Activity
 * recreation. Falls back to [DarkAppColors] before DataStore has emitted to
 * avoid a one-frame light flash on cold start.
 */
@Composable
fun DrivePlayerTheme(content: @Composable () -> Unit) {
    val mode by AppModule.settingsStore.themeMode
        .collectAsStateWithLifecycle(initialValue = "SYSTEM")
    val systemDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        "DARK"  -> true
        "LIGHT" -> false
        else    -> systemDark
    }
    val colors = if (isDark) DarkAppColors else LightAppColors

    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(
            colorScheme = colorSchemeFor(colors),
            typography  = AppTypography,
            content     = content,
        )
    }
}

/**
 * Lock a subtree to [DarkAppColors]. Used by [com.driveplayer.ui.player.PlayerScreen]
 * because a light chrome over a video viewport washes the picture out. Calling
 * this is cheap — it just provides a different value to the [LocalAppColors]
 * CompositionLocal and rebuilds the colour scheme. No Activity recreation.
 */
@Composable
fun DarkOnlyTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides DarkAppColors) {
        MaterialTheme(
            colorScheme = colorSchemeFor(DarkAppColors),
            typography  = AppTypography,
            content     = content,
        )
    }
}
