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
    /** True for the dark variant — used by status-bar icon colour selection. */
    val isDark:         Boolean,
)

val DarkAppColors = AppColors(
    background      = Color(0xFF0D0F14),
    surfaceVariant  = Color(0xFF1A1D25),
    cardSurface     = Color(0xFF22263A),
    accentPrimary   = Color(0xFF4F8EF7),
    accentSecondary = Color(0xFF7B5CF0),
    accentGlow      = Color(0xFF2D6BE4),
    textPrimary     = Color(0xFFF0F2FF),
    textSecondary   = Color(0xFF8B92B3),
    textMuted       = Color(0xFF505570),
    colorError      = Color(0xFFE05C5C),
    colorSuccess    = Color(0xFF4CAF80),
    isDark          = true,
)

val LightAppColors = AppColors(
    // Off-white background with subtle warmth — pure white tires the eye fast.
    background      = Color(0xFFF7F8FB),
    surfaceVariant  = Color(0xFFFFFFFF),
    cardSurface     = Color(0xFFEEF1F8),
    // Same accent pair as dark theme so the brand reads consistently.
    accentPrimary   = Color(0xFF2563EB),
    accentSecondary = Color(0xFF7C3AED),
    accentGlow      = Color(0xFF1D4ED8),
    textPrimary     = Color(0xFF111827),
    textSecondary   = Color(0xFF4B5563),
    textMuted       = Color(0xFF9CA3AF),
    colorError      = Color(0xFFDC2626),
    colorSuccess    = Color(0xFF059669),
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
