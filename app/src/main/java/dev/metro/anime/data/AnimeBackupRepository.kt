package dev.metro.anime.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.model.WatchProgress
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.data.settings.AnimeSettings
import dev.metro.anime.data.settings.MetroSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class AnimeBackupResult(
    val success: Boolean,
    val message: String? = null,
)

class AnimeBackupRepository(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun exportBackup(
        uri: Uri,
        settingsRepo: MetroSettingsRepository,
        animeRepo: AnimeRepository,
    ): AnimeBackupResult = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            root.put("version", 1)
            root.put("timestamp", System.currentTimeMillis())
            root.put("app", "dev.metro.anime")

            // 1. Settings
            val s = settingsRepo.settingsFlow.first()
            val settingsJson = gson.toJson(s)
            root.put("settings", JSONObject(settingsJson))

            // 2. Wallpaper
            val wpFile = File(context.filesDir, "anime_wallpaper.jpg")
            if (wpFile.exists()) {
                val bytes = wpFile.readBytes()
                root.put("wallpaperBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } else {
                root.put("wallpaperBase64", JSONObject.NULL)
            }

            // 3. Bookmarks
            val bookmarks = animeRepo.getAllBookmarks()
            root.put("bookmarksJson", gson.toJson(bookmarks))

            // 4. Watch History
            val history = animeRepo.getAllHistory()
            root.put("historyJson", gson.toJson(history))

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(root.toString(2))
                }
            } ?: return@withContext AnimeBackupResult(false, "Не удалось открыть файл для записи")

            AnimeBackupResult(true, "Резервная копия успешно создана")
        } catch (e: Exception) {
            AnimeBackupResult(false, e.localizedMessage ?: e.message)
        }
    }

    suspend fun importBackup(
        uri: Uri,
        settingsRepo: MetroSettingsRepository,
        animeRepo: AnimeRepository,
    ): AnimeBackupResult = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { inp ->
                inp.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } ?: return@withContext AnimeBackupResult(false, "Не удалось прочитать файл")

            val root = JSONObject(content)

            // 1. Settings
            if (root.has("settings")) {
                val settingsObj = root.getJSONObject("settings")
                val s = gson.fromJson(settingsObj.toString(), AnimeSettings::class.java)
                if (s != null) {
                    settingsRepo.restoreSettings(s)
                }
            }

            // 2. Wallpaper
            val wpFile = File(context.filesDir, "anime_wallpaper.jpg")
            if (root.has("wallpaperBase64") && !root.isNull("wallpaperBase64")) {
                val b64 = root.getString("wallpaperBase64")
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    wpFile.writeBytes(bytes)
                } else {
                    if (wpFile.exists()) wpFile.delete()
                }
            } else {
                if (wpFile.exists()) wpFile.delete()
            }
            settingsRepo.reloadWallpaper()

            // 3. Bookmarks
            val bookmarksList: List<AnimeTitle> = if (root.has("bookmarksJson") && !root.isNull("bookmarksJson")) {
                val type = object : TypeToken<List<AnimeTitle>>() {}.type
                gson.fromJson(root.getString("bookmarksJson"), type) ?: emptyList()
            } else {
                animeRepo.getAllBookmarks()
            }

            // 4. History
            val historyList: List<WatchProgress> = if (root.has("historyJson") && !root.isNull("historyJson")) {
                val type = object : TypeToken<List<WatchProgress>>() {}.type
                gson.fromJson(root.getString("historyJson"), type) ?: emptyList()
            } else {
                animeRepo.getAllHistory()
            }

            animeRepo.restoreData(bookmarksList, historyList)

            AnimeBackupResult(true, "Данные успешно восстановлены")
        } catch (e: Exception) {
            AnimeBackupResult(false, e.localizedMessage ?: e.message)
        }
    }
}
