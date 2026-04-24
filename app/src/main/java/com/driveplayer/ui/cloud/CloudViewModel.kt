package com.driveplayer.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driveplayer.di.AppModule
import com.driveplayer.data.remote.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed class CloudConnectionState {
    object Disconnected : CloudConnectionState()
    object Connecting : CloudConnectionState()
    data class Connected(
        val accessToken: String,
        val repo: DriveRepository,
        val okHttpClient: OkHttpClient,
    ) : CloudConnectionState()
    data class Error(val message: String) : CloudConnectionState()
}

class CloudViewModel : ViewModel() {

    private val helper = AppModule.googleSignInHelper

    private val _state = MutableStateFlow<CloudConnectionState>(CloudConnectionState.Disconnected)
    val state: StateFlow<CloudConnectionState> = _state

    init {
        // Try silent restore
        trySilentConnect()
    }

    fun trySilentConnect() {
        val account = helper.currentAccount() ?: return
        _state.value = CloudConnectionState.Connecting
        viewModelScope.launch {
            try {
                val token = helper.getAccessToken(account)
                connectWith(token)
            } catch (e: Exception) {
                _state.value = CloudConnectionState.Disconnected
            }
        }
    }

    fun onSignInSuccess(token: String) {
        connectWith(token)
    }

    private fun connectWith(token: String) {
        val repo = AppModule.buildDriveRepository(token)
        val client = AppModule.buildOkHttpClient(token)
        _state.value = CloudConnectionState.Connected(
            accessToken = token,
            repo = repo,
            okHttpClient = client
        )
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { helper.signOut() }
            _state.value = CloudConnectionState.Disconnected
        }
    }
}
