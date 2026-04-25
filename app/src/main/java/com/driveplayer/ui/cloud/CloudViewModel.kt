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
    val id: String
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
        // Load saved accounts from persistent storage
        viewModelScope.launch {
            accountPrefs.savedAccounts.collect { accounts ->
                _savedAccounts.value = accounts
            }
        }
        // Try silent restore
        trySilentConnect()
    }

    fun trySilentConnect() {
        val account = helper.currentAccount() ?: return
        _state.value = CloudConnectionState.Connecting
        viewModelScope.launch {
            try {
                val token = helper.getAccessToken(account)
                connectWith(token, account.email ?: "", account.displayName)
            } catch (e: Exception) {
                _state.value = CloudConnectionState.Disconnected
            }
        }
    }

    fun onSignInSuccess(token: String, email: String, displayName: String?) {
        connectWith(token, email, displayName)
    }

    private fun connectWith(token: String, email: String, displayName: String?) {
        val repo = AppModule.buildDriveRepository(token)
        val client = AppModule.buildOkHttpClient(token)
        
        // Save account to persistent storage
        val newAccount = SavedAccount(email, displayName, email)
        viewModelScope.launch {
            accountPrefs.saveAccount(newAccount)
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
        viewModelScope.launch {
            runCatching { helper.signOut() }
            _state.value = CloudConnectionState.Disconnected
            _showLogoutDialog.value = false
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
        _targetAccountEmail.value = account.email
        viewModelScope.launch {
            runCatching { helper.signOut() }
            _state.value = CloudConnectionState.Disconnected
            _autoSignIn.value = true
        }
    }

    fun addNewAccount() {
        _showAccountDialog.value = false
        _targetAccountEmail.value = null
        viewModelScope.launch {
            runCatching { helper.signOut() }
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
        }
    }
}
