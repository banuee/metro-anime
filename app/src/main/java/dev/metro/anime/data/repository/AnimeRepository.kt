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

        val baseCleanQuery = query.replace(
            Regex("(?i)\\b(\\d+\\s*сезон|сезон\\s*\\d+|\\d+\\s*(?:st|nd|rd|th)?\\s*season|season\\s*\\d+|тв-?\\d+|тв\\s*\\d+)\\b"),
            ""
        ).trim()
        val baseAniResults = if (baseCleanQuery.isNotBlank() && !baseCleanQuery.equals(query, ignoreCase = true)) {
            ApiClient.searchAniLibria(baseCleanQuery, limit = 25)
        } else emptyList()

        val shikiQuery = ApiClient.resolveShikimoriRussianTitle(query)
        val shikiAniResults = if (!shikiQuery.isNullOrBlank() &&
            !shikiQuery.equals(query, ignoreCase = true) &&
            !shikiQuery.equals(baseCleanQuery, ignoreCase = true)
        ) {
            ApiClient.searchAniLibria(shikiQuery, limit = 25)
        } else emptyList()

        val shikiResults = ApiClient.searchShikimori(query, limit = 15)

        val kodikQuery = shikiQuery ?: (if (baseCleanQuery.isNotBlank()) baseCleanQuery else query)
        val kodikResults = ApiClient.searchKodikTitles(kodikQuery, limit = 50)

        val combined = mutableListOf<AnimeTitle>()
        val seenTitles = mutableSetOf<String>()
        val seenShiki = mutableSetOf<Long>()

        fun tryAdd(item: AnimeTitle) {
            val key = item.titleRu.lowercase().trim()
            val shiki = item.shikimoriId
            if (shiki != null && seenShiki.contains(shiki)) return
            if (seenTitles.contains(key)) return
            combined.add(item)
            seenTitles.add(key)
            if (shiki != null) seenShiki.add(shiki)
        }

        directResults.forEach { tryAdd(it) }
        baseAniResults.forEach { tryAdd(it) }
        shikiAniResults.forEach { tryAdd(it) }
        shikiResults.forEach { tryAdd(it) }
        kodikResults.forEach { tryAdd(it) }

        val q = query.lowercase().trim()
        val isSeekingSeason1 = q.contains("1 сезон") || q.contains("сезон 1") || q.contains("тв-1") || q.contains("тв 1") || q.contains("1st season") || q.contains("season 1")
        val isSeekingSeason2 = q.contains("2 сезон") || q.contains("сезон 2") || q.contains("тв-2") || q.contains("тв 2") || q.contains("2nd season") || q.contains("season 2")

        val sorted = combined.sortedWith { a, b ->
            val scoreA = calculateRelevanceScore(a, q, isSeekingSeason1, isSeekingSeason2)
            val scoreB = calculateRelevanceScore(b, q, isSeekingSeason1, isSeekingSeason2)
            scoreB.compareTo(scoreA)
        }

        return sorted
    }

    private fun calculateRelevanceScore(
        item: AnimeTitle,
        q: String,
        isSeekingSeason1: Boolean,
        isSeekingSeason2: Boolean,
    ): Int {
        var score = 0
        val t = item.titleRu.lowercase().trim()
        val typeStr = item.type?.lowercase().orEmpty()
        val isSpecial = t.contains("спэшл") || t.contains("спешл") || t.contains("special") ||
                t.contains("ova") || t.contains("ова") || t.contains("тиби") || t.contains("чиби") ||
                typeStr.contains("спешл") || typeStr.contains("ova")
        val isMovie = typeStr == "anime" || typeStr.contains("фильм") || typeStr.contains("movie") ||
                t.contains("фильм") || t.contains("movie") || Regex("\\b(?:2|3|4|5|6|7|8|9|10):\\s").containsMatchIn(t)
        val isTv = typeStr.contains("serial") || typeStr.contains("сериал") || typeStr.contains("тв") ||
                (item.episodesCount ?: 0) > 1

        // Match quality
        if (t == q || t == "$q 1" || t == "$q [тв-1]") {
            score += 2000
        } else if (t.startsWith("$q:") || t.startsWith("$q ") || t.startsWith("$q-")) {
            score += 1500
        } else if (t.contains(q)) {
            score += 1000
        } else {
            score += 500
        }

        // TV series preference over movies
        if (isTv) score += 300
        if (isMovie) score -= 200
        if (isSpecial) score -= 800

        // Canonical season ordering
        if (isSeekingSeason1) {
            val isS1 = t.contains("1 сезон") || t.contains("тв-1") || (!t.contains("2") && !isSpecial && !isMovie)
            if (isS1) score += 700
        } else if (isSeekingSeason2) {
            val isS2 = t.contains("2 сезон") || t.contains("тв-2") || t.contains(" 2") || t.contains("второй") || t.contains("ураганные хроники")
            if (isS2) score += 700
        } else {
            // General query: Season 1 / main TV series first!
            if (t == q || (!t.contains(" 2") && !t.contains("сезон 2") && !t.contains("тв-2"))) {
                score += 200
            }
        }

        // Tie-breaker: score/rating
        val ratingBonus = ((item.rating ?: 0.0) * 10).toInt().coerceIn(0, 100)
        score += ratingBonus

        return score
    }

    suspend fun getAnimeDetails(idOrAlias: String, shikimoriId: Long?, titleQuery: String?): AnimeDetails? {
        val details: AnimeDetails
        val kodikDubs: List<DubbingGroup>

        if (idOrAlias.startsWith("kodik_") || idOrAlias.startsWith("shiki_")) {
            var effectiveShikiId = shikimoriId
            if (effectiveShikiId == null && idOrAlias.startsWith("kodik_shiki_")) {
                effectiveShikiId = idOrAlias.removePrefix("kodik_shiki_").toLongOrNull()
            }
            if (effectiveShikiId == null && idOrAlias.startsWith("shiki_")) {
                effectiveShikiId = idOrAlias.removePrefix("shiki_").toLongOrNull()
            }

            var shikiTitle: AnimeTitle? = null
            if (effectiveShikiId != null && effectiveShikiId > 0) {
                shikiTitle = ApiClient.getShikimoriDetails(effectiveShikiId)
            }
            if (shikiTitle == null && !titleQuery.isNullOrBlank()) {
                val shikiList = ApiClient.searchShikimori(titleQuery, limit = 3)
                shikiTitle = shikiList.firstOrNull()
                if (shikiTitle != null && effectiveShikiId == null) {
                    effectiveShikiId = shikiTitle.shikimoriId
                }
            }

            kodikDubs = ApiClient.getKodikDubs(effectiveShikiId, titleQuery ?: shikiTitle?.titleRu)

            val resolvedTitleRu = shikiTitle?.titleRu ?: titleQuery ?: "Аниме"
            val resolvedPoster = shikiTitle?.posterUrl?.ifBlank { null }
                ?: if (effectiveShikiId != null && effectiveShikiId > 0) "https://shikimori.one/system/animes/original/$effectiveShikiId.jpg" else ""
            val resolvedDesc = shikiTitle?.description?.ifBlank { null }

            details = AnimeDetails(
                title = AnimeTitle(
                    id = idOrAlias,
                    titleRu = resolvedTitleRu,
                    titleOrig = shikiTitle?.titleOrig,
                    posterUrl = resolvedPoster,
                    description = resolvedDesc,
                    year = shikiTitle?.year,
                    type = shikiTitle?.type,
                    episodesCount = shikiTitle?.episodesCount ?: kodikDubs.firstOrNull()?.episodes?.size,
                    genres = shikiTitle?.genres,
                    rating = shikiTitle?.rating,
                    shikimoriId = effectiveShikiId,
                    source = AnimeSource.KODIK,
                ),
                dubbings = emptyList(),
                rawDescription = resolvedDesc,
            )
        } else {
            val aniDetails = ApiClient.getAniLibriaDetails(idOrAlias) ?: return null
            var enrichedTitle = aniDetails.title
            var enrichedDesc = aniDetails.rawDescription
            val effectiveShiki = shikimoriId ?: aniDetails.title.shikimoriId
            if (effectiveShiki != null && effectiveShiki > 0 && (enrichedDesc.isNullOrBlank() || enrichedTitle.posterUrl.isBlank())) {
                val shikiInfo = ApiClient.getShikimoriDetails(effectiveShiki)
                if (shikiInfo != null) {
                    if (enrichedDesc.isNullOrBlank()) enrichedDesc = shikiInfo.description
                    if (enrichedTitle.posterUrl.isBlank()) enrichedTitle = enrichedTitle.copy(posterUrl = shikiInfo.posterUrl)
                    if (enrichedTitle.rating == null) enrichedTitle = enrichedTitle.copy(rating = shikiInfo.rating)
                }
            }
            kodikDubs = ApiClient.getKodikDubs(effectiveShiki, titleQuery ?: aniDetails.title.titleRu)
            details = aniDetails.copy(title = enrichedTitle, rawDescription = enrichedDesc)
        }

        val fillers = try {
            ApiClient.fetchFillerEpisodes(details.title.titleOrig, details.title.titleRu.ifBlank { titleQuery })
        } catch (_: Exception) {
            emptySet()
        }

        val allDubs = mutableListOf<DubbingGroup>()
        // Format AniLibria dub
        for (ad in details.dubbings) {
            val eps = ad.episodes.sortedBy { it.ordinal }.map { ep ->
                if (fillers.contains(ep.ordinal)) ep.copy(isFiller = true) else ep
            }
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
            val eps = kd.episodes.sortedBy { it.ordinal }.map { ep ->
                if (fillers.contains(ep.ordinal)) ep.copy(isFiller = true) else ep
            }
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
            _bookmarks.value = list.map { it.sanitized() }
        } catch (_: Exception) {}
    }

    fun toggleBookmark(title: AnimeTitle) {
        val cleanTitle = title.sanitized()
        val current = _bookmarks.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == cleanTitle.id }
        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(0, cleanTitle)
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
            _history.value = list.map { it.sanitized() }
        } catch (_: Exception) {}
    }

    fun saveProgress(progress: WatchProgress) {
        val cleanProgress = progress.sanitized().copy(updatedAt = System.currentTimeMillis())
        val current = _history.value.toMutableList()
        current.removeAll { it.anime.id == cleanProgress.anime.id }
        current.add(0, cleanProgress)
        // Keep up to 50 items
        val trimmed = if (current.size > 50) current.take(50) else current
        _history.value = trimmed.toList()
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
        val cleanBookmarks = bookmarks.map { it.sanitized() }
        val cleanHistory = history.map { it.sanitized() }
        _bookmarks.value = cleanBookmarks
        _history.value = cleanHistory
        prefs.edit()
            .putString("bookmarks", gson.toJson(cleanBookmarks))
            .putString("watch_history", gson.toJson(cleanHistory))
            .apply()
    }
}

