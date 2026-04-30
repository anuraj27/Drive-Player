package com.driveplayer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for app-wide user preferences. Backed by Preferences
 * DataStore so reads stay reactive and writes are atomic.
 *
 * Design notes:
 *  - Each preference exposes a [Flow] for reactive consumers (the settings
 *    screen, ViewModels that bind on construction) and a synchronous [Flow.first]
 *    accessor — `read()` — for one-shot consumers like [com.driveplayer.ui.player.controllers.PlayerController]
 *    that just need the current value at media-prepare time.
 *  - All defaults are colocated in [Defaults] so the settings screen can show
 *    "Default" labels and a future "Reset all" action can flip every key back.
 *  - Keys are private — exposing them publicly invites direct DataStore writes
 *    that would bypass the validation in `set*()` (e.g. clamping ranges).
 */
class SettingsStore(private val context: Context) {

    // ── Library ──────────────────────────────────────────────────────────────

    /** Tab to show on cold start: "LOCAL" | "CLOUD" | "DOWNLOADS". */
    val defaultHomeTab: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_DEFAULT_HOME_TAB] ?: Defaults.HOME_TAB }

    suspend fun setDefaultHomeTab(value: String) {
        context.settingsDataStore.edit { it[KEY_DEFAULT_HOME_TAB] = value }
    }

    // ── Appearance ───────────────────────────────────────────────────────────

    /** Theme mode for browse screens: "SYSTEM" | "DARK" | "LIGHT".
     *  The player UI always renders dark — applying light to a video viewport
     *  produces a washed-out chrome that contradicts the content. */
    val themeMode: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_THEME_MODE] ?: Defaults.THEME_MODE }

    suspend fun setThemeMode(value: String) {
        context.settingsDataStore.edit { it[KEY_THEME_MODE] = value }
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    /** Resume from the last saved playback position when reopening a video. */
    val resumePlayback: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_RESUME_PLAYBACK] ?: Defaults.RESUME_PLAYBACK }

    suspend fun setResumePlayback(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_RESUME_PLAYBACK] = value }
    }

    /** Default playback speed for a fresh video. 0.25..3.0. */
    val defaultPlaybackSpeed: Flow<Float> = context.settingsDataStore.data
        .map { it[KEY_DEFAULT_SPEED] ?: Defaults.PLAYBACK_SPEED }

    suspend fun setDefaultPlaybackSpeed(value: Float) {
        context.settingsDataStore.edit {
            it[KEY_DEFAULT_SPEED] = value.coerceIn(0.25f, 3.0f)
        }
    }

    /**
     * Skip duration in milliseconds for double-tap and the overlay skip
     * buttons. Restricted to {5, 10, 15, 30 seconds}.
     */
    val skipDurationMs: Flow<Long> = context.settingsDataStore.data
        .map { it[KEY_SKIP_DURATION_MS] ?: Defaults.SKIP_DURATION_MS }

    suspend fun setSkipDurationMs(value: Long) {
        context.settingsDataStore.edit {
            it[KEY_SKIP_DURATION_MS] = value.coerceIn(1_000L, 60_000L)
        }
    }

    /**
     * Network-buffer duration libVLC reads ahead of the playhead, in
     * milliseconds. Higher values smooth over cellular jitter at the cost of
     * a longer "loading…" before the first frame.
     */
    val networkCacheMs: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_NETWORK_CACHE_MS] ?: Defaults.NETWORK_CACHE_MS }

    suspend fun setNetworkCacheMs(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_NETWORK_CACHE_MS] = value.coerceIn(300, 10_000)
        }
    }

    /** Default screen orientation when the player opens: "AUTO" | "LANDSCAPE" | "PORTRAIT". */
    val defaultOrientation: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_DEFAULT_ORIENTATION] ?: Defaults.ORIENTATION }

    suspend fun setDefaultOrientation(value: String) {
        context.settingsDataStore.edit { it[KEY_DEFAULT_ORIENTATION] = value }
    }

    /** Loop the current track when it ends instead of stopping. */
    val repeatOne: Flow<Boolean> = boolFlow(KEY_REPEAT_ONE, false)
    suspend fun setRepeatOne(value: Boolean) = setBool(KEY_REPEAT_ONE, value)

    /** Keep the screen on while the player is foregrounded (default true). */
    val keepScreenOn: Flow<Boolean> = boolFlow(KEY_KEEP_SCREEN_ON, true)
    suspend fun setKeepScreenOn(value: Boolean) = setBool(KEY_KEEP_SCREEN_ON, value)

    /** Auto-hide controls timeout in ms — 3000, 5000, or 10000. */
    val controlsAutoHideMs: Flow<Long> = context.settingsDataStore.data
        .map { it[KEY_CONTROLS_AUTOHIDE_MS] ?: Defaults.CONTROLS_AUTOHIDE_MS }

    suspend fun setControlsAutoHideMs(value: Long) {
        context.settingsDataStore.edit {
            it[KEY_CONTROLS_AUTOHIDE_MS] = value.coerceIn(1000L, 30_000L)
        }
    }

    /** Show first-time gesture hints overlay when a video starts. */
    val showGestureHints: Flow<Boolean> = boolFlow(KEY_SHOW_GESTURE_HINTS, true)
    suspend fun setShowGestureHints(value: Boolean) = setBool(KEY_SHOW_GESTURE_HINTS, value)

    // ── Audio ────────────────────────────────────────────────────────────────

    /** libVLC volume scaling factor 1.0..2.0 (= 100..200 %). 1.0 means "track the
     *  AudioManager volume only" — anything above 1.0 boosts in software. */
    val volumeBoost: Flow<Float> = context.settingsDataStore.data
        .map { it[KEY_VOLUME_BOOST] ?: Defaults.VOLUME_BOOST }

    suspend fun setVolumeBoost(value: Float) {
        context.settingsDataStore.edit {
            it[KEY_VOLUME_BOOST] = value.coerceIn(1.0f, 2.0f)
        }
    }

    /** Apply the user's selected EQ preset to every new playback. Off by default
     *  because libVLC's EQ adds a small CPU cost even when "Normal" is selected. */
    val equalizerEnabled: Flow<Boolean> = boolFlow(KEY_EQUALIZER_ENABLED, false)
    suspend fun setEqualizerEnabled(value: Boolean) = setBool(KEY_EQUALIZER_ENABLED, value)

    /** Index into libVLC's preset list (`MediaPlayer.Equalizer.getPresetCount()`).
     *  -1 means "no preset selected"; 0 is typically "Flat". */
    val equalizerPreset: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_EQUALIZER_PRESET] ?: Defaults.EQUALIZER_PRESET }

    suspend fun setEqualizerPreset(value: Int) {
        context.settingsDataStore.edit { it[KEY_EQUALIZER_PRESET] = value }
    }

    /** Keep audio playing when the activity goes to background (screen off,
     *  recents, home button). Off by default — most users hit "back" expecting
     *  silence; turn this on for podcast-style usage. */
    val backgroundAudio: Flow<Boolean> = boolFlow(KEY_BACKGROUND_AUDIO, false)
    suspend fun setBackgroundAudio(value: Boolean) = setBool(KEY_BACKGROUND_AUDIO, value)

    // ── Gestures ─────────────────────────────────────────────────────────────

    val brightnessGestureEnabled: Flow<Boolean> = boolFlow(KEY_GESTURE_BRIGHTNESS, true)
    suspend fun setBrightnessGestureEnabled(value: Boolean) =
        setBool(KEY_GESTURE_BRIGHTNESS, value)

    val volumeGestureEnabled: Flow<Boolean> = boolFlow(KEY_GESTURE_VOLUME, true)
    suspend fun setVolumeGestureEnabled(value: Boolean) =
        setBool(KEY_GESTURE_VOLUME, value)

    val seekGestureEnabled: Flow<Boolean> = boolFlow(KEY_GESTURE_SEEK, true)
    suspend fun setSeekGestureEnabled(value: Boolean) =
        setBool(KEY_GESTURE_SEEK, value)

    val doubleTapSeekEnabled: Flow<Boolean> = boolFlow(KEY_GESTURE_DOUBLE_TAP, true)
    suspend fun setDoubleTapSeekEnabled(value: Boolean) =
        setBool(KEY_GESTURE_DOUBLE_TAP, value)

    val pinchZoomEnabled: Flow<Boolean> = boolFlow(KEY_GESTURE_PINCH, true)
    suspend fun setPinchZoomEnabled(value: Boolean) =
        setBool(KEY_GESTURE_PINCH, value)

    // ── Subtitles ────────────────────────────────────────────────────────────

    /** Whether the embedded subtitle track is enabled by default on a new video.
     *  When OFF the player opens with subs disabled even if a track exists — the
     *  user can still toggle them on from the Subtitle panel. */
    val subtitlesEnabledByDefault: Flow<Boolean> = boolFlow(KEY_SUBS_DEFAULT_ON, true)
    suspend fun setSubtitlesEnabledByDefault(value: Boolean) =
        setBool(KEY_SUBS_DEFAULT_ON, value)

    /** Auto-attach an external `.srt` from the same folder if the filename
     *  matches the video stem. Cloud only — local browse doesn't surface .srt. */
    val autoLoadSubtitles: Flow<Boolean> = boolFlow(KEY_SUBS_AUTO_LOAD, true)
    suspend fun setAutoLoadSubtitles(value: Boolean) =
        setBool(KEY_SUBS_AUTO_LOAD, value)

    /** Default subtitle text scale, libVLC's `--sub-text-scale` (10..400, 100 = 1×). */
    val defaultSubtitleScale: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_SUBS_SCALE] ?: Defaults.SUBTITLE_SCALE }

    suspend fun setDefaultSubtitleScale(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_SUBS_SCALE] = value.coerceIn(10, 400)
        }
    }

    /** Default subtitle text colour, packed 0xRRGGBB. */
    val defaultSubtitleColor: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_SUBS_COLOR] ?: Defaults.SUBTITLE_COLOR }

    suspend fun setDefaultSubtitleColor(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_SUBS_COLOR] = value and 0xFFFFFF
        }
    }

    /** Default subtitle background opacity (0..255). */
    val defaultSubtitleBgAlpha: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_SUBS_BG_ALPHA] ?: Defaults.SUBTITLE_BG_ALPHA }

    suspend fun setDefaultSubtitleBgAlpha(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_SUBS_BG_ALPHA] = value.coerceIn(0, 255)
        }
    }

    // ── Downloads ────────────────────────────────────────────────────────────

    /** Restrict downloads to Wi-Fi (and Ethernet). Default off so users can
     *  intentionally fetch a file on cellular when they want to. */
    val downloadsWifiOnly: Flow<Boolean> = boolFlow(KEY_DOWNLOADS_WIFI_ONLY, false)
    suspend fun setDownloadsWifiOnly(value: Boolean) =
        setBool(KEY_DOWNLOADS_WIFI_ONLY, value)

    /** Days after which a completed download is auto-deleted; 0 = never. The
     *  cleanup runs in [com.driveplayer.player.DownloadService.onCreate], so
     *  every app launch (and every queue start) sweeps stale entries. */
    val autoDeleteDownloadsDays: Flow<Int> = context.settingsDataStore.data
        .map { it[KEY_AUTO_DELETE_DAYS] ?: Defaults.AUTO_DELETE_DAYS }

    suspend fun setAutoDeleteDownloadsDays(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_AUTO_DELETE_DAYS] = value.coerceIn(0, 365)
        }
    }

    // ── Advanced ─────────────────────────────────────────────────────────────

    /** "AUTO" enables hardware decoding when available; "DISABLED" forces
     *  software decoding. Useful as a fallback on devices where MediaCodec is
     *  flaky for a particular codec / container combination. */
    val hardwareAcceleration: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_HARDWARE_ACCEL] ?: Defaults.HARDWARE_ACCEL }

    suspend fun setHardwareAcceleration(value: String) {
        context.settingsDataStore.edit { it[KEY_HARDWARE_ACCEL] = value }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        context.settingsDataStore.data.map { it[key] ?: default }

    private suspend fun setBool(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }

    /**
     * Reactive snapshot — emits a fresh [Snapshot] every time any preference
     * changes. Used by the Settings screen so a single `collectAsState` keeps
     * the entire screen in sync without juggling N individual flows.
     */
    val snapshotFlow: Flow<Snapshot> = context.settingsDataStore.data.map { prefs ->
        Snapshot(
            defaultHomeTab            = prefs[KEY_DEFAULT_HOME_TAB] ?: Defaults.HOME_TAB,
            themeMode                 = prefs[KEY_THEME_MODE] ?: Defaults.THEME_MODE,
            resumePlayback            = prefs[KEY_RESUME_PLAYBACK] ?: Defaults.RESUME_PLAYBACK,
            defaultPlaybackSpeed      = prefs[KEY_DEFAULT_SPEED] ?: Defaults.PLAYBACK_SPEED,
            skipDurationMs            = prefs[KEY_SKIP_DURATION_MS] ?: Defaults.SKIP_DURATION_MS,
            networkCacheMs            = prefs[KEY_NETWORK_CACHE_MS] ?: Defaults.NETWORK_CACHE_MS,
            defaultOrientation        = prefs[KEY_DEFAULT_ORIENTATION] ?: Defaults.ORIENTATION,
            repeatOne                 = prefs[KEY_REPEAT_ONE] ?: false,
            keepScreenOn              = prefs[KEY_KEEP_SCREEN_ON] ?: true,
            controlsAutoHideMs        = prefs[KEY_CONTROLS_AUTOHIDE_MS] ?: Defaults.CONTROLS_AUTOHIDE_MS,
            showGestureHints          = prefs[KEY_SHOW_GESTURE_HINTS] ?: true,
            volumeBoost               = prefs[KEY_VOLUME_BOOST] ?: Defaults.VOLUME_BOOST,
            equalizerEnabled          = prefs[KEY_EQUALIZER_ENABLED] ?: false,
            equalizerPreset           = prefs[KEY_EQUALIZER_PRESET] ?: Defaults.EQUALIZER_PRESET,
            backgroundAudio           = prefs[KEY_BACKGROUND_AUDIO] ?: false,
            brightnessGesture         = prefs[KEY_GESTURE_BRIGHTNESS] ?: true,
            volumeGesture             = prefs[KEY_GESTURE_VOLUME] ?: true,
            seekGesture               = prefs[KEY_GESTURE_SEEK] ?: true,
            doubleTapSeek             = prefs[KEY_GESTURE_DOUBLE_TAP] ?: true,
            pinchZoom                 = prefs[KEY_GESTURE_PINCH] ?: true,
            subtitlesEnabledByDefault = prefs[KEY_SUBS_DEFAULT_ON] ?: true,
            autoLoadSubtitles         = prefs[KEY_SUBS_AUTO_LOAD] ?: true,
            defaultSubtitleScale      = prefs[KEY_SUBS_SCALE] ?: Defaults.SUBTITLE_SCALE,
            defaultSubtitleColor      = prefs[KEY_SUBS_COLOR] ?: Defaults.SUBTITLE_COLOR,
            defaultSubtitleBgAlpha    = prefs[KEY_SUBS_BG_ALPHA] ?: Defaults.SUBTITLE_BG_ALPHA,
            downloadsWifiOnly         = prefs[KEY_DOWNLOADS_WIFI_ONLY] ?: false,
            autoDeleteDownloadsDays   = prefs[KEY_AUTO_DELETE_DAYS] ?: Defaults.AUTO_DELETE_DAYS,
            hardwareAcceleration      = prefs[KEY_HARDWARE_ACCEL] ?: Defaults.HARDWARE_ACCEL,
        )
    }

    /**
     * Read all current settings as an immutable [Snapshot] in one DataStore
     * read. Used by [com.driveplayer.ui.player.controllers.PlayerController] /
     * [com.driveplayer.player.DriveDownloadManager] which only need a one-shot
     * value at construction / enqueue time, not a live flow.
     */
    suspend fun snapshot(): Snapshot = snapshotFlow.first()

    /**
     * Wipe every preference back to the [Defaults]. Wired to the "Reset all
     * settings" action in the Privacy section. Implemented as `prefs.clear()`
     * so we don't have to track which keys exist — the next read falls back
     * through the `?: Defaults.X` chain in [snapshotFlow].
     */
    suspend fun resetAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    data class Snapshot(
        val defaultHomeTab: String,
        val themeMode: String,
        val resumePlayback: Boolean,
        val defaultPlaybackSpeed: Float,
        val skipDurationMs: Long,
        val networkCacheMs: Int,
        val defaultOrientation: String,
        val repeatOne: Boolean,
        val keepScreenOn: Boolean,
        val controlsAutoHideMs: Long,
        val showGestureHints: Boolean,
        val volumeBoost: Float,
        val equalizerEnabled: Boolean,
        val equalizerPreset: Int,
        val backgroundAudio: Boolean,
        val brightnessGesture: Boolean,
        val volumeGesture: Boolean,
        val seekGesture: Boolean,
        val doubleTapSeek: Boolean,
        val pinchZoom: Boolean,
        val subtitlesEnabledByDefault: Boolean,
        val autoLoadSubtitles: Boolean,
        val defaultSubtitleScale: Int,
        val defaultSubtitleColor: Int,
        val defaultSubtitleBgAlpha: Int,
        val downloadsWifiOnly: Boolean,
        val autoDeleteDownloadsDays: Int,
        val hardwareAcceleration: String,
    )

    object Defaults {
        const val HOME_TAB = "CLOUD"
        const val THEME_MODE = "SYSTEM"          // SYSTEM | DARK | LIGHT
        const val RESUME_PLAYBACK = true
        const val PLAYBACK_SPEED = 1.0f
        const val SKIP_DURATION_MS = 10_000L
        const val NETWORK_CACHE_MS = 1500
        const val ORIENTATION = "AUTO"           // AUTO | LANDSCAPE | PORTRAIT
        const val CONTROLS_AUTOHIDE_MS = 3_000L
        const val VOLUME_BOOST = 1.0f            // 1.0 = 100 %, no boost
        const val EQUALIZER_PRESET = -1          // -1 = no preset selected
        const val SUBTITLE_SCALE = 100
        const val SUBTITLE_COLOR = 0xFFFFFF      // white
        const val SUBTITLE_BG_ALPHA = 0
        const val AUTO_DELETE_DAYS = 0           // 0 = never
        const val HARDWARE_ACCEL = "AUTO"        // AUTO | DISABLED
    }

    private companion object {
        // Library
        val KEY_DEFAULT_HOME_TAB     = stringPreferencesKey("default_home_tab")
        // Appearance
        val KEY_THEME_MODE           = stringPreferencesKey("theme_mode")
        // Playback
        val KEY_RESUME_PLAYBACK      = booleanPreferencesKey("resume_playback")
        val KEY_DEFAULT_SPEED        = floatPreferencesKey("default_playback_speed")
        val KEY_SKIP_DURATION_MS     = longPreferencesKey("skip_duration_ms")
        val KEY_NETWORK_CACHE_MS     = intPreferencesKey("network_cache_ms")
        val KEY_DEFAULT_ORIENTATION  = stringPreferencesKey("default_orientation")
        val KEY_REPEAT_ONE           = booleanPreferencesKey("repeat_one")
        val KEY_KEEP_SCREEN_ON       = booleanPreferencesKey("keep_screen_on")
        val KEY_CONTROLS_AUTOHIDE_MS = longPreferencesKey("controls_autohide_ms")
        val KEY_SHOW_GESTURE_HINTS   = booleanPreferencesKey("show_gesture_hints")
        // Audio
        val KEY_VOLUME_BOOST         = floatPreferencesKey("volume_boost")
        val KEY_EQUALIZER_ENABLED    = booleanPreferencesKey("equalizer_enabled")
        val KEY_EQUALIZER_PRESET     = intPreferencesKey("equalizer_preset")
        val KEY_BACKGROUND_AUDIO     = booleanPreferencesKey("background_audio")
        // Gestures
        val KEY_GESTURE_BRIGHTNESS   = booleanPreferencesKey("gesture_brightness")
        val KEY_GESTURE_VOLUME       = booleanPreferencesKey("gesture_volume")
        val KEY_GESTURE_SEEK         = booleanPreferencesKey("gesture_seek")
        val KEY_GESTURE_DOUBLE_TAP   = booleanPreferencesKey("gesture_double_tap")
        val KEY_GESTURE_PINCH        = booleanPreferencesKey("gesture_pinch")
        // Subtitles
        val KEY_SUBS_DEFAULT_ON      = booleanPreferencesKey("subs_default_on")
        val KEY_SUBS_AUTO_LOAD       = booleanPreferencesKey("subs_auto_load")
        val KEY_SUBS_SCALE           = intPreferencesKey("subs_scale")
        val KEY_SUBS_COLOR           = intPreferencesKey("subs_color")
        val KEY_SUBS_BG_ALPHA        = intPreferencesKey("subs_bg_alpha")
        // Downloads
        val KEY_DOWNLOADS_WIFI_ONLY  = booleanPreferencesKey("downloads_wifi_only")
        val KEY_AUTO_DELETE_DAYS     = intPreferencesKey("auto_delete_days")
        // Advanced
        val KEY_HARDWARE_ACCEL       = stringPreferencesKey("hardware_accel")
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "user_settings")
