package dev.metro.anime.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dev.metro.anime.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val changelog: String,
    val apkDownloadUrl: String,
    val apkSize: Long,
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: ReleaseInfo) : UpdateState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class UpdateRepository(private val context: Context) {
    private val app = context.applicationContext

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/banuee/metro-anime/releases/latest"
        private const val TAG = "MetroAnimeUpdate"
    }

    suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "MetroAnime-App/${BuildConfig.VERSION_NAME}")
            }

            if (conn.responseCode != 200) return@withContext null

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)

            val tagName = root.optString("tag_name", "")
            val body = root.optString("body", "Нет описания изменений.")
            val rawVersion = tagName.removePrefix("v").trim()

            // Find APK in assets
            val assets = root.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isBlank()) return@withContext null

            val currentVersion = BuildConfig.VERSION_NAME
            if (isNewerVersion(rawVersion, currentVersion)) {
                ReleaseInfo(
                    tagName = tagName,
                    versionName = rawVersion,
                    changelog = body,
                    apkDownloadUrl = apkUrl,
                    apkSize = apkSize,
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка проверки обновлений: ${e.message}")
            null
        }
    }

    suspend fun checkForUpdates() {
        _updateState.value = UpdateState.Checking
        try {
            val release = fetchLatestRelease()
            if (release != null) {
                _updateState.value = UpdateState.Available(release)
            } else {
                _updateState.value = UpdateState.UpToDate
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка проверки обновлений: ${e.message}")
            _updateState.value = UpdateState.Error(e.localizedMessage ?: "Сетевая ошибка")
        }
    }

    suspend fun downloadUpdate(info: ReleaseInfo) {
        _updateState.value = UpdateState.Downloading(0f, 0L, info.apkSize)
        withContext(Dispatchers.IO) {
            try {
                val updatesDir = File(app.cacheDir, "updates").apply { mkdirs() }
                val targetFile = File(updatesDir, "metro-anime-${info.versionName}.apk")
                if (targetFile.exists()) targetFile.delete()

                var currentUrl = info.apkDownloadUrl
                var conn: HttpURLConnection
                var redirects = 0

                while (true) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 20000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "MetroAnime-App/${BuildConfig.VERSION_NAME}")
                    }
                    val code = conn.responseCode
                    if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308) && redirects < 5) {
                        val location = conn.getHeaderField("Location")
                        if (!location.isNullOrBlank()) {
                            currentUrl = location
                            redirects++
                            conn.disconnect()
                            continue
                        }
                    }
                    break
                }

                val totalLength = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.apkSize
                var downloaded = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(16384)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val progress = if (totalLength > 0) downloaded.toFloat() / totalLength else 0f
                            _updateState.value = UpdateState.Downloading(progress, downloaded, totalLength)
                        }
                    }
                }

                _updateState.value = UpdateState.ReadyToInstall(targetFile)
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка скачивания APK: ${e.message}")
                _updateState.value = UpdateState.Error("Ошибка скачивания: ${e.localizedMessage}")
            }
        }
    }

    fun installApk(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!app.packageManager.canRequestPackageInstalls()) {
                    val permIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${app.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(permIntent)
                    return
                }
            }

            val contentUri: Uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.fileprovider",
                file,
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска установки: ${e.message}")
            _updateState.value = UpdateState.Error("Не удалось запустить установщик: ${e.localizedMessage}")
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val rParts = remote.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val cParts = current.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        } catch (_: Exception) {
            return remote != current
        }
    }
}
