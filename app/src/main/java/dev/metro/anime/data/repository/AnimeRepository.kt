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
        val directResults = ApiClient.searchAniLibria(query, limit = 25)
        val shikiQuery = ApiClient.resolveShikimoriRussianTitle(query)
        val shikiAniResults = if (!shikiQuery.isNullOrBlank() && shikiQuery != query) {
            ApiClient.searchAniLibria(shikiQuery, limit = 25)
        } else emptyList()

        val kodikQuery = shikiQuery ?: query
        val kodikResults = ApiClient.searchKodikTitles(kodikQuery, limit = 25)

        val combined = mutableListOf<AnimeTitle>()
        combined.addAll(directResults)
        val seenTitles = directResults.map { it.titleRu.lowercase().trim() }.toMutableSet()
        val seenShiki = directResults.mapNotNull { it.shikimoriId }.toMutableSet()

        for (item in shikiAniResults) {
            val key = item.titleRu.lowercase().trim()
            val shiki = item.shikimoriId
            if (!seenTitles.contains(key) && (shiki == null || !seenShiki.contains(shiki))) {
                combined.add(item)
                seenTitles.add(key)
                if (shiki != null) seenShiki.add(shiki)
            }
        }

        for (item in kodikResults) {
            val key = item.titleRu.lowercase().trim()
            val shiki = item.shikimoriId
            if (!seenTitles.contains(key) && (shiki == null || !seenShiki.contains(shiki))) {
                combined.add(item)
                seenTitles.add(key)
                if (shiki != null) seenShiki.add(shiki)
            }
        }

        return combined
    }

    suspend fun getAnimeDetails(idOrAlias: String, shikimoriId: Long?, titleQuery: String?): AnimeDetails? {
        val details: AnimeDetails
        val kodikDubs: List<DubbingGroup>

        if (idOrAlias.startsWith("kodik_")) {
            kodikDubs = ApiClient.getKodikDubs(shikimoriId, titleQuery)
            details = AnimeDetails(
                title = AnimeTitle(
                    id = idOrAlias,
                    titleRu = titleQuery ?: "Аниме",
                    posterUrl = "",
                    shikimoriId = shikimoriId,
                    source = AnimeSource.KODIK,
                ),
                dubbings = emptyList(),
                rawDescription = "Просмотр через Kodik",
            )
        } else {
            val aniDetails = ApiClient.getAniLibriaDetails(idOrAlias) ?: return null
            kodikDubs = ApiClient.getKodikDubs(shikimoriId ?: aniDetails.title.shikimoriId, titleQuery ?: aniDetails.title.titleRu)
            details = aniDetails
        }

        val allDubs = mutableListOf<DubbingGroup>()
        // Format AniLibria dub
        for (ad in details.dubbings) {
            val eps = ad.episodes.sortedBy { it.ordinal }
            val first = eps.firstOrNull()?.ordinal ?: 1
            val last = eps.lastOrNull()?.ordinal ?: eps.size
            val count = eps.size
            val label = if (first > 1) {
                "AniLibria (${first}–${last} эп.)"
            } else {
                "AniLibria (${count} эп.)"
            }
            allDubs.add(ad.copy(title = label, episodes = eps))
        }

        // Format Kodik dubs
        for (kd in kodikDubs) {
            if (kd.title.contains("AniLibria", ignoreCase = true) && allDubs.isNotEmpty()) {
                continue
            }
            val eps = kd.episodes.sortedBy { it.ordinal }
            val first = eps.firstOrNull()?.ordinal ?: 1
            val last = eps.lastOrNull()?.ordinal ?: eps.size
            val count = eps.size
            val label = if (first > 1) {
                "${kd.title} (${first}–${last} эп.)"
            } else {
                "${kd.title} (${count} эп.)"
            }
            allDubs.add(kd.copy(title = label, episodes = eps))
        }

        // Smart sort dubs:
        // Priority 1: Dubs that start at episode 1 with the highest number of episodes!
        // Priority 2: Other dubs by episode count descending
        val sortedDubs = allDubs.sortedWith(
            compareByDescending<DubbingGroup> { dub ->
                val first = dub.episodes.firstOrNull()?.ordinal ?: 1
                if (first == 1) 1 else 0
            }.thenByDescending { dub ->
                dub.episodes.size
            }
        )

        return details.copy(dubbings = sortedDubs)
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

    fun getAllBookmarks(): List<AnimeTitle> = _bookmarks.value

    fun getAllHistory(): List<WatchProgress> = _history.value

    fun restoreData(bookmarks: List<AnimeTitle>, history: List<WatchProgress>) {
        _bookmarks.value = bookmarks
        _history.value = history
        prefs.edit()
            .putString("bookmarks", gson.toJson(bookmarks))
            .putString("watch_history", gson.toJson(history))
            .apply()
    }
}

