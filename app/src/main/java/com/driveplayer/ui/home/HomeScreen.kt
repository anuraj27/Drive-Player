package com.driveplayer.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.driveplayer.ui.theme.*

enum class HomeTab { LOCAL, CLOUD, DOWNLOADS }

@Composable
fun HomeScreen(
    initialTab: HomeTab = HomeTab.LOCAL,
    onTabChanged: (HomeTab) -> Unit = {},
    localContent: @Composable () -> Unit,
    cloudContent: @Composable () -> Unit,
    downloadsContent: @Composable () -> Unit,
) {
    var activeTab by remember { mutableStateOf(initialTab) }

    LaunchedEffect(activeTab) { onTabChanged(activeTab) }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceVariant,
                contentColor = TextPrimary,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == HomeTab.LOCAL,
                    onClick = { activeTab = HomeTab.LOCAL },
                    icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = "Local") },
                    label = {
                        Text(
                            "Local",
                            fontWeight = if (activeTab == HomeTab.LOCAL) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPrimary,
                        selectedTextColor = AccentPrimary,
                        indicatorColor = AccentPrimary.copy(alpha = 0.12f),
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
                NavigationBarItem(
                    selected = activeTab == HomeTab.CLOUD,
                    onClick = { activeTab = HomeTab.CLOUD },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = "Cloud") },
                    label = {
                        Text(
                            "Cloud",
                            fontWeight = if (activeTab == HomeTab.CLOUD) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentSecondary,
                        selectedTextColor = AccentSecondary,
                        indicatorColor = AccentSecondary.copy(alpha = 0.12f),
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
                NavigationBarItem(
                    selected = activeTab == HomeTab.DOWNLOADS,
                    onClick = { activeTab = HomeTab.DOWNLOADS },
                    icon = { Icon(Icons.Default.Download, contentDescription = "Downloads") },
                    label = {
                        Text(
                            "Downloads",
                            fontWeight = if (activeTab == HomeTab.DOWNLOADS) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AccentPrimary,
                        selectedTextColor = AccentPrimary,
                        indicatorColor = AccentPrimary.copy(alpha = 0.12f),
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            Crossfade(
                targetState = activeTab,
                animationSpec = tween(200),
                label = "tab_transition"
            ) { tab ->
                when (tab) {
                    HomeTab.LOCAL     -> localContent()
                    HomeTab.CLOUD     -> cloudContent()
                    HomeTab.DOWNLOADS -> downloadsContent()
                }
            }
        }
    }
}
