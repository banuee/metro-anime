package dev.metro.anime.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.metro.anime.data.api.ApiClient
import dev.metro.anime.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AnimeRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("metro_anime_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _bookmarks = MutableStateFlow<List<AnimeTitle>>(emptyList())
    val bookmarks: StateFlow<List<AnimeTitle>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<WatchProgress>>(emptyList())
    val history: StateFlow<List<WatchProgress>> = _history.asStateFlow()

    init {
        loadBookmarks()
        loadHistory()
    }

    suspend fun getLatestAnime(): List<AnimeTitle> {
        return ApiClient.getAniLibriaLatest(limit = 30)
    }

    suspend fun searchAnime(query: String): List<AnimeTitle> {
        if (query.isBlank()) return emptyList()
        val directResults = ApiClient.searchAniLibria(query, limit = 30)
        if (directResults.isNotEmpty()) return directResults

        // If direct search gave 0 results, try resolving via Shikimori
        val shikiQuery = ApiClient.resolveShikimoriRussianTitle(query)
        if (!shikiQuery.isNullOrBlank() && shikiQuery != query) {
            val shikiResults = ApiClient.searchAniLibria(shikiQuery, limit = 30)
            if (shikiResults.isNotEmpty()) return shikiResults
        }

        // Also fallback to searching Kodik catalog
        val kodikResults = ApiClient.searchKodikTitles(query)
        if (kodikResults.isNotEmpty()) return kodikResults
        if (!shikiQuery.isNullOrBlank()) {
            return ApiClient.searchKodikTitles(shikiQuery)
        }
        return emptyList()
    }

    suspend fun getAnimeDetails(idOrAlias: String, shikimoriId: Long?, titleQuery: String?): AnimeDetails? {
        if (idOrAlias.startsWith("kodik_")) {
            val dubs = ApiClient.getKodikDubs(shikimoriId, titleQuery)
            return AnimeDetails(
                title = AnimeTitle(
                    id = idOrAlias,
                    titleRu = titleQuery ?: "Аниме",
                    posterUrl = "",
                    shikimoriId = shikimoriId,
                    source = AnimeSource.KODIK,
                ),
                dubbings = dubs,
                rawDescription = "Просмотр через Kodik",
            )
        }

        val details = ApiClient.getAniLibriaDetails(idOrAlias) ?: return null

        // Also fetch Kodik alternative dubs asynchronously if available
        val kodikDubs = ApiClient.getKodikDubs(shikimoriId ?: details.title.shikimoriId, titleQuery ?: details.title.titleRu)
        val combinedDubs = details.dubbings.toMutableList()

        for (kd in kodikDubs) {
            // Avoid duplicate AniLibria dub if Kodik also has AniLibria
            if (!kd.title.contains("AniLibria", ignoreCase = true)) {
                combinedDubs.add(kd)
            }
        }

        return details.copy(dubbings = combinedDubs)
    }

    suspend fun resolveEpisodeStream(episode: AnimeEpisode, source: AnimeSource): Map<String, String> {
        if (source == AnimeSource.ANILIBRIA) {
            return episode.hlsStreams
        }
        val embedUrl = episode.hlsStreams["embed"] ?: return episode.hlsStreams
        return ApiClient.resolveKodikStream(embedUrl)
    }

    // =========================================================================
    // Bookmarks & History
    // =========================================================================

    private fun loadBookmarks() {
        val json = prefs.getString("bookmarks", null) ?: return
        try {
            val type = object : TypeToken<List<AnimeTitle>>() {}.type
            val list: List<AnimeTitle> = gson.fromJson(json, type) ?: emptyList()
            _bookmarks.value = list
        } catch (_: Exception) {}
    }

    fun toggleBookmark(title: AnimeTitle) {
        val current = _bookmarks.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == title.id }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(0, title)
        }
        _bookmarks.value = current
        prefs.edit().putString("bookmarks", gson.toJson(current)).apply()
    }

    fun isBookmarked(titleId: String): Boolean {
        return _bookmarks.value.any { it.id == titleId }
    }

    private fun loadHistory() {
        val json = prefs.getString("watch_history", null) ?: return
        try {
            val type = object : TypeToken<List<WatchProgress>>() {}.type
            val list: List<WatchProgress> = gson.fromJson(json, type) ?: emptyList()
            _history.value = list
        } catch (_: Exception) {}
    }

    fun saveProgress(progress: WatchProgress) {
        val current = _history.value.toMutableList()
        current.removeAll { it.anime.id == progress.anime.id }
        current.add(0, progress)
        // Keep up to 50 items
        val trimmed = if (current.size > 50) current.take(50) else current
        _history.value = trimmed
        prefs.edit().putString("watch_history", gson.toJson(trimmed)).apply()
    }

    fun getProgress(animeId: String): WatchProgress? {
        return _history.value.firstOrNull { it.anime.id == animeId }
    }

    fun removeHistoryItem(animeId: String) {
        val current = _history.value.toMutableList()
        current.removeAll { it.anime.id == animeId }
        _history.value = current
        prefs.edit().putString("watch_history", gson.toJson(current)).apply()
    }
}

