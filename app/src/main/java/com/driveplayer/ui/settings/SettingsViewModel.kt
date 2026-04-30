package com.driveplayer.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.SettingsStore
import com.driveplayer.data.local.AccountPreferences
import com.driveplayer.di.AppModule
import com.driveplayer.player.RecentSearchStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Read-write surface over [SettingsStore] for the Settings screen.
 *
 * State exposure: every preference is exposed via the [combinedState] StateFlow
 * which mirrors a [SettingsStore.Snapshot]. The screen collects the snapshot
 * once and reads scalar fields off it — cheaper than collecting one flow per
 * preference and avoids partial-apply visual glitches when several preferences
 * mutate together.
 *
 * Action surface: every setter is a fire-and-forget `fun set*(value)` that
 * launches into [viewModelScope]. Privacy / sign-out actions are also methods
 * here (rather than threading callbacks down from MainActivity), since they
 * route to the same `AppModule` singletons the cloud screen uses.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppModule.settingsStore
    private val watchHistory = AppModule.watchHistoryStore
    private val recents = AppModule.recentSearchStore
    private val accountPrefs = AccountPreferences(AppModule.appContext)
    private val helper = AppModule.googleSignInHelper

    val state: StateFlow<SettingsStore.Snapshot> = store.snapshotFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        FALLBACK_SNAPSHOT,
    )

    // ── Setters ──────────────────────────────────────────────────────────────

    fun setDefaultHomeTab(value: String)               = launch { store.setDefaultHomeTab(value) }
    fun setThemeMode(value: String)                    = launch { store.setThemeMode(value) }
    fun setResumePlayback(value: Boolean)              = launch { store.setResumePlayback(value) }
    fun setDefaultPlaybackSpeed(value: Float)          = launch { store.setDefaultPlaybackSpeed(value) }
    fun setSkipDurationMs(value: Long)                 = launch { store.setSkipDurationMs(value) }
    fun setNetworkCacheMs(value: Int)                  = launch { store.setNetworkCacheMs(value) }
    fun setDefaultOrientation(value: String)           = launch { store.setDefaultOrientation(value) }
    fun setRepeatOne(value: Boolean)                   = launch { store.setRepeatOne(value) }
    fun setKeepScreenOn(value: Boolean)                = launch { store.setKeepScreenOn(value) }
    fun setControlsAutoHideMs(value: Long)             = launch { store.setControlsAutoHideMs(value) }
    fun setShowGestureHints(value: Boolean)            = launch { store.setShowGestureHints(value) }
    fun setVolumeBoost(value: Float)                   = launch { store.setVolumeBoost(value) }
    fun setEqualizerEnabled(value: Boolean)            = launch { store.setEqualizerEnabled(value) }
    fun setEqualizerPreset(value: Int)                 = launch { store.setEqualizerPreset(value) }
    fun setBackgroundAudio(value: Boolean)             = launch { store.setBackgroundAudio(value) }
    fun setBrightnessGesture(value: Boolean)           = launch { store.setBrightnessGestureEnabled(value) }
    fun setVolumeGesture(value: Boolean)               = launch { store.setVolumeGestureEnabled(value) }
    fun setSeekGesture(value: Boolean)                 = launch { store.setSeekGestureEnabled(value) }
    fun setDoubleTapSeek(value: Boolean)               = launch { store.setDoubleTapSeekEnabled(value) }
    fun setPinchZoom(value: Boolean)                   = launch { store.setPinchZoomEnabled(value) }
    fun setSubtitlesEnabledByDefault(value: Boolean)   = launch { store.setSubtitlesEnabledByDefault(value) }
    fun setAutoLoadSubtitles(value: Boolean)           = launch { store.setAutoLoadSubtitles(value) }
    fun setDefaultSubtitleScale(value: Int)            = launch { store.setDefaultSubtitleScale(value) }
    fun setDefaultSubtitleColor(value: Int)            = launch { store.setDefaultSubtitleColor(value) }
    fun setDefaultSubtitleBgAlpha(value: Int)          = launch { store.setDefaultSubtitleBgAlpha(value) }
    fun setDownloadsWifiOnly(value: Boolean)           = launch { store.setDownloadsWifiOnly(value) }
    fun setAutoDeleteDownloadsDays(value: Int)         = launch { store.setAutoDeleteDownloadsDays(value) }
    fun setHardwareAcceleration(value: String)         = launch { store.setHardwareAcceleration(value) }

    // ── Privacy actions ──────────────────────────────────────────────────────

    fun clearWatchHistory() = launch { watchHistory.clearAll() }

    /** Wipes both LOCAL and CLOUD recent-search namespaces. */
    fun clearSearchHistory() = launch {
        recents.clear(RecentSearchStore.Namespace.LOCAL)
        recents.clear(RecentSearchStore.Namespace.CLOUD)
    }

    fun signOutFromAllAccounts() = launch {
        runCatching {
            accountPrefs.clearAllAccounts()
            helper.signOut()
        }
        AppModule.clearActiveCredentials()
    }

    /** Wipe every preference back to factory defaults. The Snapshot flow emits
     *  the defaulted values automatically via the `?: Defaults.X` fallbacks. */
    fun resetAllSettings() = launch { store.resetAll() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        /**
         * Initial value for [state] before DataStore has emitted. Mirrors
         * [SettingsStore.Defaults] so the first frame of the screen doesn't
         * flicker between a "stub" and the real values when prefs exist.
         */
        val FALLBACK_SNAPSHOT = SettingsStore.Snapshot(
            defaultHomeTab            = SettingsStore.Defaults.HOME_TAB,
            themeMode                 = SettingsStore.Defaults.THEME_MODE,
            resumePlayback            = SettingsStore.Defaults.RESUME_PLAYBACK,
            defaultPlaybackSpeed      = SettingsStore.Defaults.PLAYBACK_SPEED,
            skipDurationMs            = SettingsStore.Defaults.SKIP_DURATION_MS,
            networkCacheMs            = SettingsStore.Defaults.NETWORK_CACHE_MS,
            defaultOrientation        = SettingsStore.Defaults.ORIENTATION,
            repeatOne                 = false,
            keepScreenOn              = true,
            controlsAutoHideMs        = SettingsStore.Defaults.CONTROLS_AUTOHIDE_MS,
            showGestureHints          = true,
            volumeBoost               = SettingsStore.Defaults.VOLUME_BOOST,
            equalizerEnabled          = false,
            equalizerPreset           = SettingsStore.Defaults.EQUALIZER_PRESET,
            backgroundAudio           = false,
            brightnessGesture         = true,
            volumeGesture             = true,
            seekGesture               = true,
            doubleTapSeek             = true,
            pinchZoom                 = true,
            subtitlesEnabledByDefault = true,
            autoLoadSubtitles         = true,
            defaultSubtitleScale      = SettingsStore.Defaults.SUBTITLE_SCALE,
            defaultSubtitleColor      = SettingsStore.Defaults.SUBTITLE_COLOR,
            defaultSubtitleBgAlpha    = SettingsStore.Defaults.SUBTITLE_BG_ALPHA,
            downloadsWifiOnly         = false,
            autoDeleteDownloadsDays   = SettingsStore.Defaults.AUTO_DELETE_DAYS,
            hardwareAcceleration      = SettingsStore.Defaults.HARDWARE_ACCEL,
        )
    }
}
