package com.driveplayer.image

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.driveplayer.di.AppModule
import okhttp3.OkHttpClient

/**
 * Centralised Coil [ImageLoader] used by every `AsyncImage` in the app.
 *
 * Why our own client instead of Coil's default:
 *  - Drive thumbnails (`thumbnailLink`) and `lh3.googleusercontent.com` content
 *    URLs return 403 when the user isn't already authenticated against the
 *    same browser. Adding `Authorization: Bearer …` makes the request go
 *    through reliably for our scope. We re-use [AppModule.currentAccessToken]
 *    so a refresh elsewhere automatically reaches Coil too.
 *  - We size the disk cache modestly (50 MB) — there are ~hundreds of
 *    thumbnails for a heavy Drive account but each is <30 KB at `=s400`.
 *  - We size the memory cache off the device heap (25 %) so a fast scroll
 *    through 200+ items stays jank-free.
 *
 * Wired in [com.driveplayer.MainActivity] via `Coil.setImageLoader(...)` so
 * every implicit `AsyncImage(url)` call picks it up without per-call config.
 */
object AppImageLoader {

    fun build(context: Context): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val needsAuth = host.endsWith("googleusercontent.com") ||
                                host.endsWith("googleapis.com")
                val token = AppModule.currentAccessToken()
                val authedReq = if (needsAuth && token != null) {
                    req.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else req
                chain.proceed(authedReq)
            }
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }
}
