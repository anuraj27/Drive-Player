package com.driveplayer.navigation

import androidx.compose.runtime.*
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.ui.cloud.CloudScreen
import com.driveplayer.ui.downloads.DownloadsScreen
import com.driveplayer.ui.home.HomeScreen
import com.driveplayer.ui.home.HomeTab
import com.driveplayer.ui.local.LocalBrowserScreen
import com.driveplayer.ui.player.PlayerScreen

sealed class Screen {
    object Home : Screen()

    data class LocalPlayer(val video: LocalVideo) : Screen()

    data class CloudPlayer(
        val videoFile: DriveFile,
        val siblingFiles: List<DriveFile>,
        val repo: DriveRepository,
        val accessToken: String,
    ) : Screen()
}

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
    var activeTab      by remember { mutableStateOf(HomeTab.LOCAL) }
    var playerSession  by remember { mutableIntStateOf(0) }

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
                        onVideoClick = { file, siblings, repo, accessToken ->
                            activeTab = HomeTab.CLOUD
                            playerSession++
                            currentScreen = Screen.CloudPlayer(
                                videoFile = file,
                                siblingFiles = siblings,
                                repo = repo,
                                accessToken = accessToken,
                            )
                        }
                    )
                },
                downloadsContent = {
                    DownloadsScreen(
                        onPlayDownload = { uri ->
                            val syntheticVideo = LocalVideo(
                                id = -1L,
                                title = uri.lastPathSegment ?: "Downloaded Video",
                                path = uri.path ?: "",
                                uri = uri,
                                duration = 0L,
                                size = 0L,
                                folderName = "Downloads",
                                folderPath = "",
                                dateModified = 0L,
                            )
                            activeTab = HomeTab.DOWNLOADS
                            playerSession++
                            currentScreen = Screen.LocalPlayer(syntheticVideo)
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
                accessToken = screen.accessToken,
                playerKey = "player_$playerSession",
                onBack = { currentScreen = Screen.Home }
            )
        }
    }
}
