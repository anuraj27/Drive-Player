package com.driveplayer.navigation

import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.ui.cloud.CloudScreen
import com.driveplayer.ui.home.HomeScreen
import com.driveplayer.ui.home.HomeTab
import com.driveplayer.ui.local.LocalBrowserScreen
import com.driveplayer.ui.player.PlayerScreen
import okhttp3.OkHttpClient

/**
 * Top-level navigation.
 *
 * Flow:
 *   HomeScreen (Local | Cloud tabs)
 *     └─ Local → LocalBrowserScreen → PlayerScreen
 *     └─ Cloud → CloudScreen (connect/browse) → PlayerScreen
 *
 * Sealed class navigation because we pass non-serializable objects
 * (OkHttpClient, DriveRepository) between screens.
 */
@UnstableApi
sealed class Screen {
    object Home : Screen()

    /** Playing a local device video */
    data class LocalPlayer(val video: LocalVideo) : Screen()

    /** Playing a Google Drive video */
    data class CloudPlayer(
        val videoFile: DriveFile,
        val siblingFiles: List<DriveFile>,
        val repo: DriveRepository,
        val okHttpClient: OkHttpClient,
    ) : Screen()
}

@UnstableApi
@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Persist the active tab across navigations so back returns to the correct tab
    var activeTab by remember { mutableStateOf(HomeTab.LOCAL) }

    // Increments every time a video is opened — ensures a fresh ViewModel even for the same video
    var playerSession by remember { mutableIntStateOf(0) }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                initialTab = activeTab,
                onTabChanged = { activeTab = it },
                localContent = {
                    LocalBrowserScreen(
                        localRepo = AppModule.localVideoRepository,
                        onVideoClick = { video ->
                            activeTab = HomeTab.LOCAL
                            playerSession++
                            currentScreen = Screen.LocalPlayer(video)
                        }
                    )
                },
                cloudContent = {
                    CloudScreen(
                        onVideoClick = { file, siblings, repo, client ->
                            activeTab = HomeTab.CLOUD
                            playerSession++
                            currentScreen = Screen.CloudPlayer(
                                videoFile = file,
                                siblingFiles = siblings,
                                repo = repo,
                                okHttpClient = client,
                            )
                        }
                    )
                }
            )
        }

        is Screen.LocalPlayer -> {
            PlayerScreen(
                localVideo = screen.video,
                playerKey = "player_$playerSession",
                onBack = { currentScreen = Screen.Home }
            )
        }

        is Screen.CloudPlayer -> {
            PlayerScreen(
                videoFile = screen.videoFile,
                siblingFiles = screen.siblingFiles,
                repo = screen.repo,
                okHttpClient = screen.okHttpClient,
                playerKey = "player_$playerSession",
                onBack = { currentScreen = Screen.Home }
            )
        }
    }
}
