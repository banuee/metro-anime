package dev.metro.anime

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

class MetroAnimeApp : Application(), ImageLoaderFactory {

    companion object {
        var isSerostMode: Boolean = false
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(coil.intercept.Interceptor { chain ->
                    if (isSerostMode) {
                        val newRequest = chain.request.newBuilder()
                            .data(R.drawable.serost_cat)
                            .build()
                        chain.proceed(newRequest)
                    } else {
                        chain.proceed(chain.request)
                    }
                })
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(40L * 1024 * 1024) // 40 MB max disk cache for image thumbnails
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Auto-clean any accumulated update APKs on startup
        cleanUpdateApks()
    }

    private fun cleanUpdateApks() {
        try {
            val updatesDir = File(cacheDir, "updates")
            if (updatesDir.exists()) {
                val apks = updatesDir.listFiles { _, name -> name.endsWith(".apk", ignoreCase = true) }
                apks?.forEach { apk ->
                    apk.delete()
                }
            }
        } catch (e: Exception) {
            Log.w("MetroAnimeApp", "Failed to clean update APKs: ${e.message}")
        }
    }
}
