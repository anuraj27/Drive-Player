package com.driveplayer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driveplayer.di.AppModule

/**
 * Build the Material3 colour scheme from our internal palette so the few M3
 * widgets that read `MaterialTheme.colorScheme` directly (Switch, Slider,
 * RadioButton, etc.) match the rest of the chrome.
 */
/**
 * Heuristic contrast picker for "onAccent" content. Material3 expects the
 * `onPrimary` / `onSecondary` slot to read legibly against the matching
 * filled background — for our light-mode lavender (`#7C3AED`) that means
 * white text, but for the soft blue (`#5E9BFF`) of our dark theme black
 * gives more contrast than white. A standard relative-luminance cut-off
 * matches what Material themselves recommend.
 */
private fun onAccent(bg: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val r = bg.red
    val g = bg.green
    val b = bg.blue
    fun ch(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    val luminance = 0.2126f * ch(r) + 0.7152f * ch(g) + 0.0722f * ch(b)
    return if (luminance > 0.5f) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
}

private fun colorSchemeFor(c: AppColors) = if (c.isDark) {
    darkColorScheme(
        primary          = c.accentPrimary,
        secondary        = c.accentSecondary,
        // Map our extra warning colour to Material's `tertiary` slot so any
        // Material widget that respects tertiary shares the queued/
        // in-progress hue without per-callsite overrides.
        tertiary         = c.colorWarning,
        background       = c.background,
        surface          = c.surfaceVariant,
        surfaceVariant   = c.cardSurface,
        onPrimary        = onAccent(c.accentPrimary),
        onSecondary      = onAccent(c.accentSecondary),
        onTertiary       = onAccent(c.colorWarning),
        onBackground     = c.textPrimary,
        onSurface        = c.textPrimary,
        onSurfaceVariant = c.textSecondary,
        error            = c.colorError,
    )
} else {
    lightColorScheme(
        primary          = c.accentPrimary,
        secondary        = c.accentSecondary,
        tertiary         = c.colorWarning,
        background       = c.background,
        surface          = c.surfaceVariant,
        surfaceVariant   = c.cardSurface,
        // Properly derived from the accent luminance instead of forced white,
        // so a pale accent doesn't render unreadable text.
        onPrimary        = onAccent(c.accentPrimary),
        onSecondary      = onAccent(c.accentSecondary),
        onTertiary       = onAccent(c.colorWarning),
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

    // Drive the system-bar icon colour from the active palette so the
    // status-bar clock / icons stay legible in either theme. Runs as a
    // SideEffect (post-composition) because we're touching the host
    // Activity's window from the composition context — doing it inside
    // composition would crash on rapid theme flips.
    val ctx = LocalContext.current
    SideEffect {
        val window = (ctx as? Activity)?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !colors.isDark
        controller.isAppearanceLightNavigationBars = !colors.isDark
    }

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
