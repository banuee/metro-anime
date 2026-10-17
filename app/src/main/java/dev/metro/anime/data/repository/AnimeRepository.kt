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
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences("metro_anime_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _bookmarks = MutableStateFlow<List<AnimeTitle>>(emptyList())
    val bookmarks: StateFlow<List<AnimeTitle>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow<List<WatchProgress>>(emptyList())
    val history: StateFlow<List<WatchProgress>> = _history.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<EpisodeSubscription>>(emptyList())
    val subscriptions: StateFlow<List<EpisodeSubscription>> = _subscriptions.asStateFlow()

    init {
        loadBookmarks()
        loadHistory()
        loadSubscriptions()
    }

    suspend fun getLatestAnime(): List<AnimeTitle> {
        val raw = ApiClient.getAniLibriaLatest(limit = 48)
        if (raw.isEmpty()) return raw

        // Отсеиваем релизы «старых» тайтлов, которые AniLibria просто подняла
        // из-за новой озвучки/переозвучки — они не должны попадать в «свежие серии».
        val clean = raw.filterNot { isRedubRelease(it) }

        val shikiIds = clean.mapNotNull { it.shikimoriId }
        val finishedIds = ApiClient.getShikimoriFinishedIds(shikiIds)

        return clean.filter { title ->
            if (!title.isOngoing) return@filter false
            val shiki = title.shikimoriId
            shiki == null || shiki !in finishedIds
        }.take(30)
    }

    private fun isRedubRelease(title: AnimeTitle): Boolean {
        val t = title.titleRu.lowercase()
        return t.contains("новая озвучка") ||
            t.contains("новый перевод") ||
            t.contains("новое озвучивание") ||
            t.contains("переозвучка") ||
            t.contains("новая озвучк")
    }

    suspend fun getAnimeSchedule(animeId: String, shikimoriId: Long?, titleRu: String?): AnimeSchedule? {
        // 1. If we already know the Shikimori id — use it (works even when AniLibria doesn't have the title).
        val knownShiki = shikimoriId?.takeIf { it > 0 }
        if (knownShiki != null) {
            val viaShiki = try {
                ApiClient.getShikimoriSchedule(knownShiki)
            } catch (_: Exception) {
                null
            }
            if (viaShiki != null) return viaShiki
        }

        // 2. Fallback to AniLibria schedule for releases it actually hosts.
        if (animeId.toLongOrNull() != null) {
            val viaAni = try {
                ApiClient.getAniLibriaScheduleFor(animeId)
            } catch (_: Exception) {
                null
            }
            if (viaAni != null) return viaAni
        }

        // 3. Last resort: resolve a Shikimori id by title.
        if (!titleRu.isNullOrBlank()) {
            val resolved = try {
                ApiClient.searchShikimori(titleRu, limit = 5).firstOrNull()?.shikimoriId
            } catch (_: Exception) {
                null
            }
            if (resolved != null && resolved > 0) {
                return try {
                    ApiClient.getShikimoriSchedule(resolved)
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    // =========================================================================
    // Episode release subscriptions (notifications)
    // =========================================================================

    private fun loadSubscriptions() {
        val json = prefs.getString("episode_subscriptions", null) ?: return
        try {
            val type = object : TypeToken<List<EpisodeSubscription>>() {}.type
            val list: List<EpisodeSubscription> = gson.fromJson(json, type) ?: emptyList()
            _subscriptions.value = list.map { it.copy(anime = it.anime.sanitized()) }
        } catch (_: Exception) {}
    }

    fun isEpisodeSubscribed(animeId: String): Boolean {
        return _subscriptions.value.any { it.anime.id == animeId }
    }

    fun getAllSubscriptions(): List<EpisodeSubscription> = _subscriptions.value

    fun toggleEpisodeSubscription(
        anime: AnimeTitle,
        shikimoriId: Long?,
        schedule: AnimeSchedule?,
    ): Boolean {
        val current = _subscriptions.value.toMutableList()
        val existing = current.indexOfFirst { it.anime.id == anime.id }
        val nowSubscribed: Boolean
        if (existing >= 0) {
            current.removeAt(existing)
            nowSubscribed = false
        } else {
            current.add(
                0,
                EpisodeSubscription(
                    anime = anime.sanitized(),
                    shikimoriId = shikimoriId?.takeIf { it > 0 },
                    lastAiredEpisode = schedule?.episodesAired ?: 0,
                    nextEpisodeAt = schedule?.nextEpisodeAt,
                )
            )
            nowSubscribed = true
        }
        persistSubscriptions(current)
        dev.metro.anime.data.EpisodeReminderManager.sync(appContext)
        return nowSubscribed
    }

    fun replaceSubscriptions(list: List<EpisodeSubscription>) {
        persistSubscriptions(list)
    }

    private fun persistSubscriptions(list: List<EpisodeSubscription>) {
        _subscriptions.value = list.toList()
        prefs.edit().putString("episode_subscriptions", gson.toJson(list)).apply()
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

        val shikiResults = ApiClient.searchShikimori(query, limit = 25)

        val kodikResults = mutableListOf<AnimeTitle>()
        val primaryKodik = ApiClient.searchKodikTitles(query, limit = 100)
        kodikResults.addAll(primaryKodik)
        if (!shikiQuery.isNullOrBlank() && !shikiQuery.equals(query, ignoreCase = true)) {
            val altKodik = ApiClient.searchKodikTitles(shikiQuery, limit = 100)
            kodikResults.addAll(altKodik)
        }
        if (baseCleanQuery.isNotBlank() && !baseCleanQuery.equals(query, ignoreCase = true) && !baseCleanQuery.equals(shikiQuery, ignoreCase = true)) {
            val baseKodik = ApiClient.searchKodikTitles(baseCleanQuery, limit = 100)
            kodikResults.addAll(baseKodik)
        }

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
        kodikResults.forEach { tryAdd(it) }
        shikiResults.forEach { tryAdd(it) }

        val q = query.lowercase().trim()
        val soughtSeason = extractSeasonNumber(q)
        val isSeekingFinal = q.contains("финал") || q.contains("final")

        val sorted = combined.sortedWith { a, b ->
            val scoreA = calculateRelevanceScore(a, q, soughtSeason, isSeekingFinal)
            val scoreB = calculateRelevanceScore(b, q, soughtSeason, isSeekingFinal)
            scoreB.compareTo(scoreA)
        }

        return sorted
    }

    private fun extractSeasonNumber(text: String): Int? {
        val lower = text.lowercase()
        val mTv = Regex("\\[тв-(\\d+)").find(lower) ?: Regex("\\bтв-?(\\d+)\\b").find(lower)
        if (mTv != null) return mTv.groupValues[1].toIntOrNull()

        val mSeas = Regex("(\\d+)\\s*сезон").find(lower) ?: Regex("сезон\\s*(\\d+)").find(lower)
        if (mSeas != null) return mSeas.groupValues[1].toIntOrNull()

        val mEng = Regex("(\\d+)(?:st|nd|rd|th)?\\s*season").find(lower) ?: Regex("season\\s*(\\d+)").find(lower)
        if (mEng != null) return mEng.groupValues[1].toIntOrNull()

        if (lower.contains("первый сезон")) return 1
        if (lower.contains("второй сезон")) return 2
        if (lower.contains("третий сезон")) return 3
        if (lower.contains("четвертый сезон") || lower.contains("четвёртый сезон")) return 4
        if (lower.contains("пятый сезон")) return 5

        return null
    }

    private fun extractPartNumber(text: String): Int {
        val lower = text.lowercase()
        val mPart = Regex("часть\\s*(\\d+)").find(lower) ?: Regex("part\\s*(\\d+)").find(lower)
        if (mPart != null) return mPart.groupValues[1].toIntOrNull() ?: 1
        if (lower.contains("заключительная глава")) return 3
        return 1
    }

    private fun calculateRelevanceScore(
        item: AnimeTitle,
        q: String,
        soughtSeason: Int?,
        isSeekingFinal: Boolean,
    ): Int {
        var score = 1000
        val t = item.titleRu.lowercase().trim()
        val typeStr = item.type?.lowercase().orEmpty()

        val isSpecial = t.contains("спэшл") || t.contains("спешл") || t.contains("special") ||
                t.contains("ova") || t.contains("ова") || t.contains("тиби") || t.contains("чиби") ||
                t.contains("коллаб") || typeStr.contains("спешл") || typeStr.contains("ova")
        val isMovie = typeStr == "anime" || typeStr.contains("фильм") || typeStr.contains("movie") ||
                t.contains("фильм") || t.contains("movie") || t.contains("хроника") || t.contains("крылья свободы") ||
                Regex("\\b(?:2|3|4|5|6|7|8|9|10):\\s").containsMatchIn(t)
        val isSpinoff = t.contains("средней школы") || t.contains("чудо-титаны") || t.contains("сникерс") || t.contains("драма")

        val season = extractSeasonNumber(t) ?: if (!isSpecial && !isMovie && !isSpinoff && !t.contains("финал")) 1 else if (t.contains("финал")) 4 else null
        val part = extractPartNumber(t)

        // Query match quality
        val cleanQ = q.replace(Regex("(?i)\\b(\\d+\\s*сезон|сезон\\s*\\d+|тв-?\\d+|финал)\\b"), "").trim()
        if (t == q || t == "$q 1" || t == "$q [тв-1]") {
            score += 1500
        } else if (cleanQ.isNotBlank() && (t == cleanQ || t.startsWith("$cleanQ [тв-") || t.startsWith("$cleanQ:"))) {
            score += 1200
        } else if (t.startsWith(q) || (cleanQ.isNotBlank() && t.startsWith(cleanQ))) {
            score += 800
        } else if (t.contains(q) || (cleanQ.isNotBlank() && t.contains(cleanQ))) {
            score += 500
        }

        // Penalties for specials / spinoffs / movies
        if (isSpecial) {
            score -= 900
        } else if (isSpinoff) {
            score -= 600
        } else if (isMovie) {
            score -= 400
        } else {
            // Main TV series
            score += 300
            if (soughtSeason != null) {
                if (season == soughtSeason) {
                    score += 2000
                }
            } else if (isSeekingFinal) {
                if (t.contains("финал") || season == 4) {
                    score += 2000
                }
            } else {
                // Canonical chronological order for main TV series:
                // Season 1 > Season 2 > Season 3 > Season 4...
                if (season != null) {
                    score += 500 + (10 - season.coerceIn(1, 9)) * 100 + (5 - part.coerceIn(1, 4)) * 10
                }
            }
        }

        // Release year priority (earlier years preferred for chronological ordering)
        if (item.year != null && item.year > 0) {
            score += (2030 - item.year).coerceIn(0, 50)
        }

        // Tie-breaker rating bonus
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

