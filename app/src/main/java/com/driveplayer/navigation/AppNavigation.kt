package com.driveplayer.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.driveplayer.ui.settings.SettingsScreen
import com.driveplayer.ui.theme.DarkOnlyTheme
import kotlinx.coroutines.runBlocking

sealed class Screen {
    object Home : Screen()

    object Settings : Screen()

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

    // Seed the active tab from the user's "default home tab" preference exactly
    // once on cold start. After that the user controls the tab; the preference
    // doesn't yank them back to a different tab if they changed it mid-session.
    val seededTab = remember {
        val name = runBlocking { AppModule.settingsStore.snapshot().defaultHomeTab }
        runCatching { HomeTab.valueOf(name) }.getOrDefault(HomeTab.LOCAL)
    }
    var activeTab      by remember { mutableStateOf(seededTab) }
    var playerSession  by remember { mutableIntStateOf(0) }

    // Consume one-shot tab requests posted by deep links (e.g. tapping the
    // "Download complete" notification). MainActivity writes the requested tab
    // name to AppModule.requestedHomeTab; we resolve it back to the enum, switch
    // tabs, force-pop to Home if the user is in a player, and then clear the flag.
    val requestedTabName by AppModule.requestedHomeTab.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTabName) {
        val name = requestedTabName ?: return@LaunchedEffect
        val tab = runCatching { HomeTab.valueOf(name) }.getOrNull()
        if (tab != null) {
            activeTab = tab
            if (currentScreen !is Screen.Home) {
                currentScreen = Screen.Home
            }
        }
        AppModule.requestedHomeTab.value = null
    }

    val openSettings: () -> Unit = { currentScreen = Screen.Settings }

    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                selectedTab = activeTab,
                onTabChanged = { activeTab = it },
                localContent = {
                    LocalBrowserScreen(
                        localRepo = AppModule.localVideoRepository,
                        onOpenSettings = openSettings,
                        onVideoClick = { video ->
                            activeTab = HomeTab.LOCAL
                            playerSession++
                            currentScreen = Screen.LocalPlayer(video)
                        }
                    )
                },
                cloudContent = {
                    CloudScreen(
                        onOpenSettings = openSettings,
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
                        onOpenSettings = openSettings,
                        onPlayDownload = { uri, fileId ->
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
                                // Stable key tied to the Drive file id — survives across launches
                                // and matches the same file regardless of where it was downloaded.
                                positionKey = "download_$fileId",
                            )
                            activeTab = HomeTab.DOWNLOADS
                            playerSession++
                            currentScreen = Screen.LocalPlayer(syntheticVideo)
                        }
                    )
                }
            )
        }

        is Screen.Settings -> {
            SettingsScreen(onBack = { currentScreen = Screen.Home })
        }

        is Screen.LocalPlayer -> {
            // Player UI is always rendered in dark — light chrome over a moving
            // video viewport washes out the picture and looks broken.
            DarkOnlyTheme {
                PlayerScreen(
                    localVideo = screen.video,
                    playerKey = "player_$playerSession",
                    onBack = { currentScreen = Screen.Home }
                )
            }
        }

        is Screen.CloudPlayer -> {
            DarkOnlyTheme {
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
}
