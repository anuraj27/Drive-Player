package com.driveplayer.ui.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.ui.browser.FileBrowserScreen
import com.driveplayer.ui.login.LoginViewModel
import com.driveplayer.ui.theme.*
import okhttp3.OkHttpClient

/**
 * Cloud screen: shows connect UI if disconnected, or the Drive browser if connected.
 */
@Composable
fun CloudScreen(
    onVideoClick: (DriveFile, List<DriveFile>, DriveRepository, OkHttpClient) -> Unit,
    cloudVm: CloudViewModel = viewModel()
) {
    val connectionState by cloudVm.state.collectAsStateWithLifecycle()

    when (val cs = connectionState) {
        is CloudConnectionState.Disconnected -> {
            ConnectScreen(onConnected = { token -> cloudVm.onSignInSuccess(token) })
        }
        is CloudConnectionState.Connecting -> {
            Box(Modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentSecondary)
            }
        }
        is CloudConnectionState.Error -> {
            Box(Modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Warning, null, tint = ColorError, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(cs.message, color = TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { cloudVm.trySilentConnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) { Text("Retry") }
                }
            }
        }
        is CloudConnectionState.Connected -> {
            FileBrowserScreen(
                repo = cs.repo,
                accessToken = cs.accessToken,
                onVideoClick = { file, siblings -> onVideoClick(file, siblings, cs.repo, cs.okHttpClient) },
                onSignOut = { cloudVm.disconnect() }
            )
        }
    }
}

/**
 * Connect screen shown when not signed into Google Drive.
 */
@Composable
private fun ConnectScreen(onConnected: (String) -> Unit) {
    val loginVm: LoginViewModel = viewModel()
    val loginState by loginVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(loginState) {
        if (loginState is com.driveplayer.ui.login.LoginState.Success) {
            onConnected((loginState as com.driveplayer.ui.login.LoginState.Success).accessToken)
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> loginVm.handleSignInResult(result.data) }

    // Animated glow
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f, label = "scale",
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        // Background glows
        Box(
            modifier = Modifier
                .size(250.dp)
                .offset(x = (-40).dp, y = (-80).dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(listOf(AccentSecondary.copy(0.3f), Color.Transparent)),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(200.dp)
                .offset(x = 60.dp, y = 80.dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(listOf(AccentPrimary.copy(0.2f), Color.Transparent)),
                    CircleShape
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 36.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(glowScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(AccentSecondary, AccentPrimary))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Cloud, null, modifier = Modifier.size(44.dp), tint = Color.White)
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "Google Drive",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Connect to stream videos\ndirectly from your Drive.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(40.dp))

            when (loginState) {
                is com.driveplayer.ui.login.LoginState.Loading -> {
                    CircularProgressIndicator(color = AccentSecondary)
                }
                is com.driveplayer.ui.login.LoginState.Error -> {
                    Text(
                        (loginState as com.driveplayer.ui.login.LoginState.Error).message,
                        color = ColorError,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    ConnectButton { signInLauncher.launch(AppModule.googleSignInHelper.signInIntent()) }
                }
                else -> {
                    ConnectButton { signInLauncher.launch(AppModule.googleSignInHelper.signInIntent()) }
                }
            }
        }
    }
}

@Composable
private fun ConnectButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(AccentSecondary, AccentPrimary)),
                    RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Connect to Google Drive",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
