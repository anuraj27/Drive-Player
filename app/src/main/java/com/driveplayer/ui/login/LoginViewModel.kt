package com.driveplayer.ui.login

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driveplayer.di.AppModule
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class LoginState {
    object Idle        : LoginState()
    object Loading     : LoginState()
    data class Success(val account: GoogleSignInAccount, val accessToken: String) : LoginState()
    data class Error(val message: String) : LoginState()
}

class LoginViewModel : ViewModel() {

    private val helper = AppModule.googleSignInHelper

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state

    /** Called on app start — silent sign-in if a previous session exists. */
    fun trySilentSignIn() {
        val account = helper.currentAccount() ?: return
        _state.value = LoginState.Loading
        viewModelScope.launch {
            try {
                val token = helper.getAccessToken(account)
                _state.value = LoginState.Success(account, token)
            } catch (e: Exception) {
                // Silent sign-in failed — show login screen normally
                _state.value = LoginState.Idle
            }
        }
    }

    /** Called after the user picks their account from the sign-in Intent result. */
    fun handleSignInResult(data: Intent?) {
        _state.value = LoginState.Loading
        viewModelScope.launch {
            val result = helper.handleSignInResult(data)
            result.fold(
                onSuccess = { account ->
                    try {
                        val token = helper.getAccessToken(account)
                        _state.value = LoginState.Success(account, token)
                    } catch (e: Exception) {
                        _state.value = LoginState.Error("Could not get access token: ${e.message}")
                    }
                },
                onFailure = { e ->
                    _state.value = LoginState.Error("Sign-in failed: ${e.message}")
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { helper.signOut() }
            _state.value = LoginState.Idle
        }
    }
}
