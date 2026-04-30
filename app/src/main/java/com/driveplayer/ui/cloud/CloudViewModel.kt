package com.driveplayer.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driveplayer.di.AppModule
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.data.local.AccountPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlinx.serialization.Serializable

@Serializable
data class SavedAccount(
    val email: String,
    val displayName: String?,
    val id: String,
    val accessToken: String? = null,
    val lastTokenTime: Long? = null
)

sealed class CloudConnectionState {
    object Disconnected : CloudConnectionState()
    object Connecting : CloudConnectionState()
    data class Connected(
        val accessToken: String,
        val repo: DriveRepository,
        val okHttpClient: OkHttpClient,
        val userEmail: String,
        val displayName: String?,
    ) : CloudConnectionState()
    data class Error(val message: String) : CloudConnectionState()
}

class CloudViewModel : ViewModel() {

    private val helper = AppModule.googleSignInHelper
    private val accountPrefs = AccountPreferences(AppModule.appContext)

    private val _state = MutableStateFlow<CloudConnectionState>(CloudConnectionState.Disconnected)
    val state: StateFlow<CloudConnectionState> = _state

    private val _savedAccounts = MutableStateFlow<List<SavedAccount>>(emptyList())
    val savedAccounts: StateFlow<List<SavedAccount>> = _savedAccounts

    private val _showAccountDialog = MutableStateFlow(false)
    val showAccountDialog: StateFlow<Boolean> = _showAccountDialog

    private val _showLogoutDialog = MutableStateFlow(false)
    val showLogoutDialog: StateFlow<Boolean> = _showLogoutDialog

    private val _autoSignIn = MutableStateFlow(false)
    val autoSignIn: StateFlow<Boolean> = _autoSignIn

    private val _targetAccountEmail = MutableStateFlow<String?>(null)
    val targetAccountEmail: StateFlow<String?> = _targetAccountEmail

    init {
        // Start in Connecting state to avoid flashing the AccountListScreen before silent connect runs
        _state.value = CloudConnectionState.Connecting
        viewModelScope.launch {
            accountPrefs.savedAccounts.collect { accounts ->
                _savedAccounts.value = accounts
            }
        }
        trySilentConnect()
    }

    fun trySilentConnect() {
        viewModelScope.launch {
            // Prefer the explicitly-tracked active account over getLastSignedInAccount(),
            // because getLastSignedInAccount() only knows about ONE account at a time.
            val activeEmail = accountPrefs.getActiveAccount()
                ?: helper.currentAccount()?.email  // legacy fallback for existing users
            if (activeEmail == null) {
                _state.value = CloudConnectionState.Disconnected
                return@launch
            }
            _state.value = CloudConnectionState.Connecting
            try {
                val savedAccount = accountPrefs.getAccount(activeEmail)
                // getAccessTokenForEmail uses AccountManager — works for any Google account on
                // the device without needing an active Sign-In session.
                val token = helper.getAccessTokenForEmail(activeEmail)
                connectWith(token, activeEmail, savedAccount?.displayName)
            } catch (e: Exception) {
                _state.value = CloudConnectionState.Disconnected
            }
        }
    }

    fun onSignInSuccess(token: String, email: String, displayName: String?) {
        connectWith(token, email, displayName)
    }

    private fun connectWith(token: String, email: String, displayName: String?) {
        // Single source of truth for the Bearer token — OkHttp interceptor, OkHttp
        // 401 Authenticator, and DriveAuthProxy all read from AppModule, so a future
        // refresh propagates to every caller automatically.
        AppModule.setActiveCredentials(email, token)
        val repo = AppModule.buildDriveRepository()
        val client = AppModule.buildOkHttpClient()

        val newAccount = SavedAccount(email, displayName, email)
        viewModelScope.launch {
            accountPrefs.saveAccount(newAccount)
            accountPrefs.setActiveAccount(email)
        }

        _targetAccountEmail.value = null
        _state.value = CloudConnectionState.Connected(
            accessToken = token,
            repo = repo,
            okHttpClient = client,
            userEmail = email,
            displayName = displayName
        )
    }

    fun disconnect() {
        val currentEmail = (state.value as? CloudConnectionState.Connected)?.userEmail
        viewModelScope.launch {
            if (currentEmail != null) {
                accountPrefs.removeAccount(currentEmail)
            }
            accountPrefs.setActiveAccount(null)
            AppModule.clearActiveCredentials()
            _state.value = CloudConnectionState.Disconnected
            _showLogoutDialog.value = false
        }
    }

    fun signOutFromAllAccounts() {
        viewModelScope.launch {
            try {
                accountPrefs.clearAllAccounts()
                helper.signOut()
            } catch (e: Exception) {
                // ignore
            }
            AppModule.clearActiveCredentials()
            _state.value = CloudConnectionState.Disconnected
        }
    }

    fun showAccountDialog() {
        _showAccountDialog.value = true
    }

    fun hideAccountDialog() {
        _showAccountDialog.value = false
    }

    fun showLogoutDialog() {
        _showLogoutDialog.value = true
    }

    fun hideLogoutDialog() {
        _showLogoutDialog.value = false
    }

    fun switchAccount(account: SavedAccount) {
        _showAccountDialog.value = false
        _state.value = CloudConnectionState.Connecting
        viewModelScope.launch {
            try {
                // Get a fresh token via AccountManager — no need to store or check token age.
                // This works as long as the Google account is on the device and consent was granted.
                val token = helper.getAccessTokenForEmail(account.email)
                connectWith(token, account.email, account.displayName)
            } catch (e: Exception) {
                // Consent was revoked or account is not on device — need OAuth flow.
                // Sign out first so the picker doesn't pre-select the wrong account.
                try { helper.signOutCurrentClient() } catch (_: Exception) {}
                _targetAccountEmail.value = account.email
                _state.value = CloudConnectionState.Disconnected
                _autoSignIn.value = true
            }
        }
    }

    fun addNewAccount() {
        _showAccountDialog.value = false
        _targetAccountEmail.value = null
        viewModelScope.launch {
            // Must sign out before launching the intent, otherwise Google Play Services
            // pre-selects the current account instead of showing the account picker.
            try { helper.signOutCurrentClient() } catch (_: Exception) {}
            _state.value = CloudConnectionState.Disconnected
            _autoSignIn.value = true
        }
    }

    fun clearAutoSignIn() {
        _autoSignIn.value = false
    }

    fun removeAccount(account: SavedAccount) {
        viewModelScope.launch {
            accountPrefs.removeAccount(account.email)
            if (accountPrefs.getActiveAccount() == account.email) {
                accountPrefs.setActiveAccount(null)
            }
        }
    }
}
