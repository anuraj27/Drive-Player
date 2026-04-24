package com.driveplayer.di

import android.content.Context
import com.driveplayer.data.auth.GoogleSignInHelper
import com.driveplayer.data.local.LocalVideoRepository
import com.driveplayer.data.remote.DriveApiService
import com.driveplayer.data.remote.DriveRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.driveplayer.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
private const val DRIVE_BASE_URL = "https://www.googleapis.com/"

/**
 * Manual singleton DI — no Hilt because:
 * - 3 screens don't justify annotation processor overhead
 * - Simpler to debug and reason about
 * - Hilt would add ~200KB and a build step
 */
object AppModule {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val localVideoRepository: LocalVideoRepository by lazy {
        LocalVideoRepository(appContext)
    }

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

    /**
     * Builds an OkHttpClient that injects Authorization header on every request.
     * The same client is reused by both Retrofit AND ExoPlayer's data source factory
     * — one connection pool, consistent config.
     */
    fun buildOkHttpClient(accessToken: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                chain.proceed(req)
            }
            .apply {
                if (BuildConfig.DEBUG) addInterceptor(
                    HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
                )
            }
            .build()

    fun buildDriveRepository(accessToken: String): DriveRepository {
        val client = buildOkHttpClient(accessToken)
        val retrofit = Retrofit.Builder()
            .baseUrl(DRIVE_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return DriveRepository(retrofit.create(DriveApiService::class.java))
    }
}
