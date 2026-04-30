package com.driveplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import coil.Coil
import com.driveplayer.di.AppModule
import com.driveplayer.image.AppImageLoader
import com.driveplayer.navigation.AppNavigation
import com.driveplayer.player.DownloadService
import com.driveplayer.player.DownloadStatus
import com.driveplayer.ui.home.HomeTab
import com.driveplayer.ui.theme.DrivePlayerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent action posted by completion / failure download notifications. */
        const val ACTION_OPEN_DOWNLOADS = "com.driveplayer.action.OPEN_DOWNLOADS"
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result is informational only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppModule.init(this)
        // Install the app-wide Coil ImageLoader so every implicit AsyncImage
        // request shares the same disk/memory cache and the Bearer-token
        // interceptor for Drive thumbnails. Must run after AppModule.init
        // because the interceptor reads from AppModule.currentAccessToken().
        Coil.setImageLoader(AppImageLoader.build(applicationContext))

        ensureNotificationPermission()
        handleIntent(intent)
        kickDownloadServiceIfPending()

        setContent {
            DrivePlayerTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_DOWNLOADS) {
            AppModule.requestedHomeTab.value = HomeTab.DOWNLOADS.name
        }
    }

    /**
     * On Android 13+ POST_NOTIFICATIONS is a runtime permission. Without it, our
     * download service still runs but the user never sees progress notifications.
     * Request it once on first launch — Android won't show the system dialog
     * again if the user previously denied (we silently fall back).
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * If we have any QUEUED or RUNNING entries persisted from a previous session
     * (process death, reboot, manual app close), wake the download service so it
     * can reconcile and resume the queue.
     */
    private fun kickDownloadServiceIfPending() {
        CoroutineScope(Dispatchers.IO).launch {
            val anyPending = AppModule.downloadStore.downloads.first().any {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING
            }
            if (anyPending) {
                DownloadService.start(this@MainActivity)
            }
        }
    }
}
