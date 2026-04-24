package com.driveplayer.player

import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSource
import okhttp3.OkHttpClient

/**
 * Custom DataSource.Factory that injects an authenticated OkHttpClient into ExoPlayer.
 *
 * Why OkHttpDataSource instead of DefaultHttpDataSource?
 * — We reuse the SAME OkHttpClient that Retrofit uses (one connection pool, shared interceptors).
 * — The OkHttp interceptor already adds "Authorization: Bearer TOKEN" so ExoPlayer's
 *   HTTP range requests for seeking all carry the correct auth header automatically.
 */
class DriveDataSourceFactory(
    private val okHttpClient: OkHttpClient
) : DataSource.Factory {

    override fun createDataSource(): DataSource =
        OkHttpDataSource.Factory(okHttpClient).createDataSource()
}
