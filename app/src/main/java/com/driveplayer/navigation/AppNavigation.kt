package com.driveplayer.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import com.driveplayer.data.model.DriveFile
import com.driveplayer.di.AppModule
import com.driveplayer.ui.browser.FileBrowserScreen
import com.driveplayer.ui.login.LoginScreen
import com.driveplayer.ui.player.PlayerScreen

/**
 * Simple manual navigation — no NavHost/NavGraph.
 *
 * Decision: Using sealed class state instead of Compose Navigation for this app.
 * Reasons:
 *  1. We need to pass complex objects (DriveFile, List<DriveFile>, OkHttpClient) between screens.
 *     NavHost requires everything to be serializable Strings — ugly workarounds.
 *  2. Three screens with simple linear flow don't justify NavGraph complexity.
 *  3. Back press is handled manually with BackHandler in each screen.
 */
@UnstableApi
sealed class Screen {
    object Login : Screen()
    data class Browser(val accessToken: String) : Screen()
    data class Player(
        val videoFile: DriveFile,
        val siblingFiles: List<DriveFile>,
        val accessToken: String,
    ) : Screen()
}

@UnstableApi
@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }

    when (val screen = currentScreen) {
        is Screen.Login -> {
            LoginScreen(
                onLoginSuccess = { token ->
                    currentScreen = Screen.Browser(accessToken = token)
                }
            )
        }

        is Screen.Browser -> {
            val repo   = remember(screen.accessToken) { AppModule.buildDriveRepository(screen.accessToken) }
            val client = remember(screen.accessToken) { AppModule.buildOkHttpClient(screen.accessToken) }

            FileBrowserScreen(
                repo        = repo,
                accessToken = screen.accessToken,
                onVideoClick = { file, siblings ->
                    // siblings = all files in the current folder (for SRT auto-detection)
                    currentScreen = Screen.Player(
                        videoFile    = file,
                        siblingFiles = siblings,
                        accessToken  = screen.accessToken
                    )
                },
                onSignOut = { currentScreen = Screen.Login }
            )
        }

        is Screen.Player -> {
            val repo   = remember(screen.accessToken) { AppModule.buildDriveRepository(screen.accessToken) }
            val client = remember(screen.accessToken) { AppModule.buildOkHttpClient(screen.accessToken) }

            PlayerScreen(
                videoFile    = screen.videoFile,
                siblingFiles = screen.siblingFiles,
                repo         = repo,
                okHttpClient = client,
                onBack       = { currentScreen = Screen.Browser(screen.accessToken) }
            )
        }
    }
}
