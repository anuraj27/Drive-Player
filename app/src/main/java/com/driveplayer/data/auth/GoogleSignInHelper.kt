package com.driveplayer.data.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.GoogleAuthUtil
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

class GoogleSignInHelper(
    private val context: Context,
    private val client: GoogleSignInClient,
) {
    // Cache for account-specific clients to avoid recreating them
    private val clientCache = mutableMapOf<String, GoogleSignInClient>()

    /** Returns the sign-in Intent to launch via ActivityResultLauncher. */
    fun signInIntent(): Intent = client.signInIntent

    /**
     * Sign-in intent pre-targeted at [email]. If that account is still on the device,
     * Google Play Services skips the account picker entirely.
     * Uses cached client for faster switching between accounts.
     */
    fun signInIntentForAccount(email: String): Intent {
        val cachedClient = clientCache.getOrPut(email) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DRIVE_SCOPE))
                .setAccountName(email)
                .build()
            GoogleSignIn.getClient(context, gso)
        }
        return cachedClient.signInIntent
    }

    /** Returns the signed-in account only if it already has Drive scope granted. */
    fun currentAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)?.takeIf {
            GoogleSignIn.hasPermissions(it, Scope(DRIVE_SCOPE))
        }

    /**
     * Parses the result from the sign-in Intent.
     * Returns Result.failure if the user cancelled or auth failed.
     */
    fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount> = runCatching {
        GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(ApiException::class.java)
    }

    /**
     * Gets a valid OAuth2 access token for Drive.
     * Runs on IO dispatcher — this is a network call.
     * Automatically handles token caching + refresh via GoogleAuthUtil.
     *
     * Decision: GoogleAuthUtil.getToken() instead of requestServerAuthCode because
     * we don't need a backend. This gives a Bearer token directly on-device.
     */
    suspend fun getAccessToken(account: GoogleSignInAccount): String =
        withContext(Dispatchers.IO) {
            GoogleAuthUtil.getToken(
                context,
                account.account!!,
                "oauth2:$DRIVE_SCOPE"
            )
        }

    /**
     * Invalidate a stale token and fetch a fresh one.
     * Call this when the API returns 401.
     */
    suspend fun refreshAccessToken(account: GoogleSignInAccount): String =
        withContext(Dispatchers.IO) {
            val oldToken = GoogleAuthUtil.getToken(
                context,
                account.account!!,
                "oauth2:$DRIVE_SCOPE"
            )
            GoogleAuthUtil.invalidateToken(context, oldToken)
            // Fetch fresh token after invalidation
            GoogleAuthUtil.getToken(
                context,
                account.account!!,
                "oauth2:$DRIVE_SCOPE"
            )
        }

    /** Signs out and clears cached account. */
    suspend fun signOut() = suspendCancellableCoroutine<Unit> { cont ->
        client.signOut()
            .addOnSuccessListener  { cont.resume(Unit) }
            .addOnFailureListener  { cont.resumeWithException(it) }
    }
}
