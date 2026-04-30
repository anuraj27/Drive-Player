package com.driveplayer.di

import android.content.Context
import com.driveplayer.data.SettingsStore
import com.driveplayer.data.auth.GoogleSignInHelper
import com.driveplayer.data.local.LocalVideoRepository
import com.driveplayer.player.DownloadStore
import com.driveplayer.player.DriveDownloadManager
import com.driveplayer.player.PinnedFolderStore
import com.driveplayer.player.RecentSearchStore
import com.driveplayer.player.WatchHistoryStore
import com.driveplayer.data.remote.DriveApiService
import com.driveplayer.data.remote.DriveRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.driveplayer.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.atomic.AtomicReference

private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
private const val DRIVE_BASE_URL = "https://www.googleapis.com/"

/**
 * Manual singleton DI — no Hilt because:
 * - 3 screens don't justify annotation processor overhead
 * - Simpler to debug and reason about
 * - Hilt would add ~200KB and a build step
 */
object AppModule {

    private lateinit var _appContext: Context

    fun init(context: Context) {
        _appContext = context.applicationContext
    }

    val appContext: Context get() = _appContext

    val localVideoRepository: LocalVideoRepository by lazy {
        LocalVideoRepository(appContext)
    }

    val watchHistoryStore: WatchHistoryStore by lazy {
        WatchHistoryStore(appContext)
    }

    val pinnedFolderStore: PinnedFolderStore by lazy {
        PinnedFolderStore(appContext)
    }

    val downloadStore: DownloadStore by lazy {
        DownloadStore(appContext)
    }

    val recentSearchStore: RecentSearchStore by lazy {
        RecentSearchStore(appContext)
    }

    val settingsStore: SettingsStore by lazy {
        SettingsStore(appContext)
    }

    val playbackStateStore: com.driveplayer.player.PlaybackStateStore by lazy {
        com.driveplayer.player.PlaybackStateStore(appContext)
    }

    val driveDownloadManager: DriveDownloadManager by lazy {
        DriveDownloadManager(appContext)
    }

    /**
     * Live byte-level progress for active downloads, keyed by Drive fileId →
     * (bytesDownloaded, totalBytes). Written by [com.driveplayer.player.DownloadService]
     * every ~500 ms during the queue/poll loop and read by `DownloadsViewModel` to
     * render the progress bar without hammering DataStore. Entries are removed when
     * the corresponding download reaches a terminal state (final values are also
     * persisted to [downloadStore]).
     */
    val liveDownloadProgress: MutableStateFlow<Map<String, Pair<Long, Long>>> =
        MutableStateFlow(emptyMap())

    /**
     * One-shot request from a notification (or other deep-link) to switch the
     * Home screen to a particular tab on the next composition. `AppNavigation`
     * consumes the value and resets it back to null.
     *
     * String type to avoid an awkward UI module dependency from this DI module.
     * Valid values match the names of [com.driveplayer.ui.home.HomeTab].
     */
    val requestedHomeTab: MutableStateFlow<String?> = MutableStateFlow(null)

    val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        GoogleSignIn.getClient(appContext, gso)
    }

    val googleSignInHelper: GoogleSignInHelper by lazy {
        GoogleSignInHelper(appContext, googleSignInClient)
    }

    // ── Active credentials ───────────────────────────────────────────────────
    // Single source of truth for the OAuth Bearer token. The OkHttp interceptor,
    // OkHttp Authenticator, and DriveAuthProxy all read from here so that a refresh
    // automatically propagates everywhere — including the localhost streaming proxy.
    private val activeToken = AtomicReference<String?>(null)
    private val activeEmail = AtomicReference<String?>(null)
    private val refreshMutex = Mutex()

    fun currentAccessToken(): String? = activeToken.get()
    fun currentAccountEmail(): String? = activeEmail.get()

    fun setActiveCredentials(email: String, token: String) {
        activeEmail.set(email)
        activeToken.set(token)
    }

    fun clearActiveCredentials() {
        activeEmail.set(null)
        activeToken.set(null)
    }

    /**
     * Invalidates the current token and fetches a fresh one for [activeEmail].
     * Throws if no email is set or if the account is no longer on the device.
     * Coalesced via [refreshMutex] so concurrent 401s don't trigger N parallel refreshes.
     */
    suspend fun refreshAccessToken(): String = refreshMutex.withLock {
        val email = activeEmail.get()
            ?: throw IllegalStateException("No active account; cannot refresh token")
        val helper = googleSignInHelper
        val current = activeToken.get()
        if (current != null) {
            runCatching { helper.invalidateToken(current) }
        }
        val fresh = helper.getAccessTokenForEmail(email)
        activeToken.set(fresh)
        fresh
    }

    /**
     * Builds an OkHttpClient that pulls the current Bearer token from [activeToken]
     * on every request (so it always sends the latest token), and on a 401 response
     * triggers a synchronous refresh and retries once.
     */
    fun buildOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = activeToken.get()
                val req = if (token != null) {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else chain.request()
                chain.proceed(req)
            }
            .authenticator { _, response ->
                // Retry once. If the previous response already came back with our retry,
                // OkHttp automatically gives up to avoid an infinite loop.
                if (response.code != 401) return@authenticator null
                if (response.priorResponse != null) return@authenticator null
                val refreshed = runCatching {
                    runBlocking { refreshAccessToken() }
                }.getOrNull() ?: return@authenticator null
                response.request.newBuilder()
                    .header("Authorization", "Bearer $refreshed")
                    .build()
            }
            .apply {
                if (BuildConfig.DEBUG) addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                )
            }
            .build()

    fun buildDriveRepository(): DriveRepository {
        val client = buildOkHttpClient()
        val retrofit = Retrofit.Builder()
            .baseUrl(DRIVE_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return DriveRepository(retrofit.create(DriveApiService::class.java))
    }
}
