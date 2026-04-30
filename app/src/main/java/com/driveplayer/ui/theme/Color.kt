package com.driveplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Theme-aware colour palette. The app historically exposed top-level `Color`
 * vals (e.g. [DarkBackground], [TextPrimary]) and every screen reads them by
 * name. To keep that callsite ergonomics while introducing a real light theme
 * we now route all of those names through a [CompositionLocal] of [AppColors],
 * with a `@Composable get()` shim per name so existing code keeps compiling.
 *
 * The player subtree is locked to [DarkAppColors] regardless of the global
 * theme — putting a light chrome over a moving video washes the picture out
 * and looks broken. Every other screen tracks the user's "Theme" preference
 * (System / Dark / Light) resolved in [DrivePlayerTheme].
 */
data class AppColors(
    val background:     Color,
    val surfaceVariant: Color,
    val cardSurface:    Color,
    val accentPrimary:  Color,
    val accentSecondary:Color,
    val accentGlow:     Color,
    val textPrimary:    Color,
    val textSecondary:  Color,
    val textMuted:      Color,
    val colorError:     Color,
    val colorSuccess:   Color,
    val colorWarning:   Color,
    /** True for the dark variant — used by status-bar icon colour selection. */
    val isDark:         Boolean,
)

/**
 * "Cinema dark" palette — a deep cool-charcoal base that lets thumbnails
 * pop, a confident blue primary for actions / progress / selected states,
 * and a soft lavender for "watched / completed" accents. Designed to hold
 * up at low brightness in dark rooms without crushing UI affordances.
 */
val DarkAppColors = AppColors(
    background      = Color(0xFF0B0F17), // deepest layer — Scaffold background
    surfaceVariant  = Color(0xFF161B26), // top app bar, bottom navigation
    cardSurface     = Color(0xFF1F2533), // list rows, settings rows, chips
    accentPrimary   = Color(0xFF5E9BFF), // selected tab, FAB, progress, primary buttons
    accentSecondary = Color(0xFFC4B5FD), // watched badges, "downloaded" tint
    accentGlow      = Color(0xFF3B6FCC), // gradient depth (kept on the API)
    textPrimary     = Color(0xFFECEEF5),
    textSecondary   = Color(0xFF9CA3B8),
    textMuted       = Color(0xFF5B6377),
    colorError      = Color(0xFFF87171),
    colorSuccess    = Color(0xFF34D399), // completed downloads
    colorWarning    = Color(0xFFFBBF24), // queued / in-progress downloads
    isDark          = true,
)

/**
 * Mirror palette for the rare user who picks Light mode for browse screens.
 * Player chrome stays locked to Dark regardless. The off-white is
 * purposely warm — pure #FFFFFF backgrounds tire the eye after a few
 * minutes of scrolling.
 */
val LightAppColors = AppColors(
    background      = Color(0xFFFAFBFC),
    surfaceVariant  = Color(0xFFFFFFFF),
    cardSurface     = Color(0xFFF1F4FA),
    accentPrimary   = Color(0xFF3B82F6),
    accentSecondary = Color(0xFF7C3AED),
    accentGlow      = Color(0xFF1D4ED8),
    textPrimary     = Color(0xFF0F172A),
    textSecondary   = Color(0xFF475569),
    textMuted       = Color(0xFF94A3B8),
    colorError      = Color(0xFFDC2626),
    colorSuccess    = Color(0xFF059669),
    colorWarning    = Color(0xFFD97706),
    isDark          = false,
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

// ─── Compose-time getters keep every existing call-site (e.g. `color = TextPrimary`)
// working without a sweep across the whole codebase. Each one is `@ReadOnlyComposable`
// so the compiler can inline the read into the surrounding composition without
// scheduling an extra recompose.

val DarkBackground:  Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.background
val SurfaceVariant:  Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.surfaceVariant
val CardSurface:     Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.cardSurface
val AccentPrimary:   Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentPrimary
val AccentSecondary: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentSecondary
val AccentGlow:      Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.accentGlow
val TextPrimary:     Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.textPrimary
val TextSecondary:   Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.textSecondary
val TextMuted:       Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.textMuted
val ColorError:      Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.colorError
val ColorSuccess:    Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.colorSuccess
val ColorWarning:    Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.colorWarning
