package com.driveplayer

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.driveplayer.di.AppModule
import com.driveplayer.navigation.AppNavigation
import com.driveplayer.ui.theme.DrivePlayerTheme

class MainActivity : ComponentActivity() {

    // Set by PlayerScreen while the player is active; called from onUserLeaveHint so
    // the app auto-enters PiP when the user presses Home during playback.
    internal var pipEntryCallback: (() -> Unit)? = null

    // Called by onPictureInPictureModeChanged when leaving PiP — this fires
    // before the Compose lifecycle observer and guarantees the player is paused
    // immediately so no background audio can leak.
    internal var pipExitCallback: (() -> Unit)? = null

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipEntryCallback?.invoke()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) {
            // User exited PiP — pause immediately to prevent background audio
            pipExitCallback?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppModule.init(this)
        setContent {
            DrivePlayerTheme {
                AppNavigation()
            }
        }
    }
}
