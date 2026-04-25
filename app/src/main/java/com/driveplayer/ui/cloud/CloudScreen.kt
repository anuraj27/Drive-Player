package com.driveplayer.ui.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Logout
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
import androidx.compose.ui.text.style.TextOverflow
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
    val savedAccounts by cloudVm.savedAccounts.collectAsStateWithLifecycle()
    val showAccountDialog by cloudVm.showAccountDialog.collectAsStateWithLifecycle()
    val showLogoutDialog by cloudVm.showLogoutDialog.collectAsStateWithLifecycle()
    val autoSignIn by cloudVm.autoSignIn.collectAsStateWithLifecycle()
    val targetAccountEmail by cloudVm.targetAccountEmail.collectAsStateWithLifecycle()

    when (val cs = connectionState) {
        is CloudConnectionState.Disconnected -> {
            // savedAccounts is cleared before any OAuth flow starts, so this branch
            // only shows when there are genuinely persisted accounts (future: persistent storage).
            if (savedAccounts.isNotEmpty() && !autoSignIn) {
                AccountListScreen(
                    accounts = savedAccounts,
                    onAccountClick = { cloudVm.switchAccount(it) },
                    onAddAccount = { cloudVm.addNewAccount() }
                )
            } else {
                ConnectScreen(
                    onConnected = { token, email, displayName -> cloudVm.onSignInSuccess(token, email, displayName) },
                    autoSignIn = autoSignIn,
                    onAutoSignInHandled = { cloudVm.clearAutoSignIn() },
                    targetAccountEmail = targetAccountEmail
                )
            }
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
            Column(Modifier.fillMaxSize()) {
                UserHeader(
                    email = cs.userEmail,
                    displayName = cs.displayName,
                    onSwitchAccount = { cloudVm.showAccountDialog() },
                    onDisconnect = { cloudVm.showLogoutDialog() }
                )
                FileBrowserScreen(
                    repo = cs.repo,
                    accessToken = cs.accessToken,
                    onVideoClick = { file, siblings -> onVideoClick(file, siblings, cs.repo, cs.okHttpClient) },
                    onSignOut = { cloudVm.disconnect() }
                )
            }
        }
    }

    // Account selection dialog
    if (showAccountDialog) {
        AccountSelectionDialog(
            accounts = savedAccounts,
            currentEmail = (connectionState as? CloudConnectionState.Connected)?.userEmail,
            onAccountClick = { cloudVm.switchAccount(it) },
            onAddAccount = { cloudVm.addNewAccount() },
            onDismiss = { cloudVm.hideAccountDialog() }
        )
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onConfirm = { cloudVm.disconnect() },
            onDismiss = { cloudVm.hideLogoutDialog() }
        )
    }
}

/**
 * Connect screen shown when not signed into Google Drive.
 */
@Composable
private fun ConnectScreen(
    onConnected: (String, String, String?) -> Unit,
    autoSignIn: Boolean = false,
    onAutoSignInHandled: () -> Unit = {},
    targetAccountEmail: String? = null
) {
    val loginVm: LoginViewModel = viewModel()
    val loginState by loginVm.state.collectAsStateWithLifecycle()

    // Guards against stale LoginState.Success from a previous session triggering an
    // immediate reconnect when the screen re-enters (logout, switch account, etc.)
    var hasPerformedSignIn by remember { mutableStateOf(false) }

    LaunchedEffect(loginState) {
        if (loginState is com.driveplayer.ui.login.LoginState.Success && hasPerformedSignIn) {
            val success = loginState as com.driveplayer.ui.login.LoginState.Success
            onConnected(success.accessToken, success.account.email ?: "", success.account.displayName)
        }
    }

    // Compute once per target; setAccountName skips the picker if account is on device.
    val signInIntent = remember(targetAccountEmail) {
        if (targetAccountEmail != null) {
            AppModule.googleSignInHelper.signInIntentForAccount(targetAccountEmail)
        } else {
            AppModule.googleSignInHelper.signInIntent()
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        hasPerformedSignIn = true
        loginVm.handleSignInResult(result.data)
    }

    // Auto-launch sign-in if triggered
    LaunchedEffect(autoSignIn) {
        if (autoSignIn) {
            onAutoSignInHandled()
            signInLauncher.launch(signInIntent)
        }
    }

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
                    if (hasPerformedSignIn) {
                        Text(
                            (loginState as com.driveplayer.ui.login.LoginState.Error).message,
                            color = ColorError,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    ConnectButton { signInLauncher.launch(signInIntent) }
                }
                else -> {
                    ConnectButton { signInLauncher.launch(signInIntent) }
                }
            }
        }
    }
}

@Composable
private fun UserHeader(
    email: String,
    displayName: String?,
    onSwitchAccount: () -> Unit,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentPrimary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    (displayName?.firstOrNull() ?: email.firstOrNull())?.uppercaseChar()?.toString() ?: "?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    displayName ?: email,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    email,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row {
            IconButton(onClick = onSwitchAccount) {
                Icon(Icons.Default.Person, contentDescription = "Switch account", tint = TextSecondary)
            }
            IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.Logout, contentDescription = "Logout", tint = ColorError)
            }
        }
    }
}

@Composable
private fun AccountListScreen(
    accounts: List<SavedAccount>,
    onAccountClick: (SavedAccount) -> Unit,
    onAddAccount: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Cloud, null, tint = AccentPrimary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Select an account",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))
        
        accounts.forEach { account ->
            AccountListItem(
                account = account,
                onClick = { onAccountClick(account) }
            )
            Spacer(Modifier.height(8.dp))
        }
        
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAddAccount,
            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add new account")
        }
    }
}

@Composable
private fun AccountListItem(
    account: SavedAccount,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AccentPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (account.displayName?.firstOrNull() ?: account.email.firstOrNull())?.uppercaseChar()?.toString() ?: "?",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                account.displayName ?: account.email,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                account.email,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun AccountSelectionDialog(
    accounts: List<SavedAccount>,
    currentEmail: String?,
    onAccountClick: (SavedAccount) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch account") },
        text = {
            Column {
                accounts.forEach { account ->
                    AccountListItem(
                        account = account,
                        onClick = { onAccountClick(account) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAddAccount,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add new account")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LogoutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Logout") },
        text = { Text("Are you sure you want to logout?") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Logout", color = ColorError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
