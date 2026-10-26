package dev.metro.anime.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.metro.anime.data.api.ApiClient
import dev.metro.anime.data.api.HiAnimeClient
import dev.metro.anime.data.api.JimakuClient
import dev.metro.anime.data.download.DownloadManager
import dev.metro.anime.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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

    private val hiAnimeSubtitlesCache = ConcurrentHashMap<String, List<SubtitleTrack>>()
    private val hiAnimeSkipCache = ConcurrentHashMap<String, Pair<SkipTimestamps?, SkipTimestamps?>>()

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

    private val STOP_WORDS = setOf(
        "в", "во", "на", "и", "с", "со", "под", "по", "из", "изо", "о", "об", "обо", "к", "ко", "у", "от", "до", "за",
        "the", "a", "an", "of", "in", "on", "no", "to", "and", "is", "at"
    )

    private fun normalizeForSearch(text: String): String {
        val lower = text.lowercase().replace('ё', 'е')
        val clean = lower.replace(Regex("[^a-zа-я0-9\\s]"), " ")
        return clean.replace(Regex("\\s+"), " ").trim()
    }

    suspend fun searchAnime(query: String): List<AnimeTitle> = coroutineScope {
        val qTrim = query.trim()
        if (qTrim.isBlank()) return@coroutineScope emptyList()

        val baseCleanQuery = qTrim.replace(
            Regex("(?i)\\b(\\d+\\s*сезон|сезон\\s*\\d+|\\d+\\s*(?:st|nd|rd|th)?\\s*season|season\\s*\\d+|тв-?\\d+|тв\\s*\\d+)\\b"),
            ""
        ).trim()

        val directAniDeferred = async { ApiClient.searchAniLibria(qTrim, limit = 25) }
        val baseAniDeferred = async {
            if (baseCleanQuery.isNotBlank() && !baseCleanQuery.equals(qTrim, ignoreCase = true)) {
                ApiClient.searchAniLibria(baseCleanQuery, limit = 25)
            } else emptyList()
        }

        val primaryKodikDeferred = async { ApiClient.searchKodikTitles(qTrim, limit = 100) }
        val altKodikDeferred = async {
            val swapped = if (qTrim.contains("е")) qTrim.replace('е', 'ё') else qTrim.replace('ё', 'е')
            if (swapped != qTrim) {
                ApiClient.searchKodikTitles(swapped, limit = 60)
            } else if (baseCleanQuery.isNotBlank() && !baseCleanQuery.equals(qTrim, ignoreCase = true)) {
                ApiClient.searchKodikTitles(baseCleanQuery, limit = 60)
            } else emptyList()
        }

        val shikiResultsDeferred = async {
            try {
                ApiClient.searchShikimori(qTrim, limit = 20)
            } catch (_: Exception) {
                emptyList()
            }
        }

        val hiAnimeDeferred = async {
            if (qTrim.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                try {
                    HiAnimeClient.searchHiAnime(qTrim)
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()
        }

        val directResults = directAniDeferred.await()
        val baseAniResults = baseAniDeferred.await()
        val primaryKodik = primaryKodikDeferred.await()
        val altKodik = altKodikDeferred.await()
        val shikiResults = shikiResultsDeferred.await()
        val hiAnimeResults = hiAnimeDeferred.await()

        val combined = mutableListOf<AnimeTitle>()
        val seenTitles = mutableSetOf<String>()
        val seenShiki = mutableSetOf<Long>()

        fun tryAdd(item: AnimeTitle) {
            val key = normalizeForSearch(item.titleRu.ifBlank { item.titleOrig ?: "" })
            val shiki = item.shikimoriId
            if (shiki != null && shiki > 0 && seenShiki.contains(shiki)) return
            if (seenTitles.contains(key)) return
            combined.add(item)
            seenTitles.add(key)
            if (shiki != null && shiki > 0) seenShiki.add(shiki)
        }

        directResults.forEach { tryAdd(it) }
        baseAniResults.forEach { tryAdd(it) }
        primaryKodik.forEach { tryAdd(it) }
        altKodik.forEach { tryAdd(it) }
        shikiResults.forEach { tryAdd(it) }
        hiAnimeResults.forEach { tryAdd(it) }

        val soughtSeason = extractSeasonNumber(qTrim)
        val isSeekingFinal = qTrim.contains("финал", ignoreCase = true) || qTrim.contains("final", ignoreCase = true)

        val scored = combined.mapNotNull { item ->
            val score = calculateRelevanceScore(item, qTrim, soughtSeason, isSeekingFinal)
            if (score > 0) Pair(item, score) else null
        }

        scored.sortedByDescending { it.second }.map { it.first }
    }

    private fun extractSeasonNumber(text: String): Int? {
        val lower = text.lowercase().replace('ё', 'е')
        val mTv = Regex("\\[тв-(\\d+)").find(lower) ?: Regex("\\bтв-?(\\d+)\\b").find(lower)
        if (mTv != null) return mTv.groupValues[1].toIntOrNull()

        val mSeas = Regex("(\\d+)\\s*сезон").find(lower) ?: Regex("сезон\\s*(\\d+)").find(lower)
        if (mSeas != null) return mSeas.groupValues[1].toIntOrNull()

        val mEng = Regex("(\\d+)(?:st|nd|rd|th)?\\s*season").find(lower) ?: Regex("season\\s*(\\d+)").find(lower)
        if (mEng != null) return mEng.groupValues[1].toIntOrNull()

        val mTrail = Regex("\\s+(\\d+)\\s*$").find(lower)
        if (mTrail != null) return mTrail.groupValues[1].toIntOrNull()

        if (lower.contains("первый сезон")) return 1
        if (lower.contains("второй сезон")) return 2
        if (lower.contains("третий сезон")) return 3
        if (lower.contains("четвертый сезон") || lower.contains("четвёртый сезон")) return 4
        if (lower.contains("пятый сезон")) return 5

        return null
    }

    private fun extractPartNumber(text: String): Int {
        val lower = text.lowercase().replace('ё', 'е')
        val mPart = Regex("часть\\s*(\\d+)").find(lower) ?: Regex("part\\s*(\\d+)").find(lower)
        if (mPart != null) return mPart.groupValues[1].toIntOrNull() ?: 1
        if (lower.contains("заключительная глава")) return 3
        return 1
    }

    private fun matchBestHiAnimeTitle(titles: List<AnimeTitle>, targetSeason: Int): AnimeTitle? {
        if (titles.isEmpty()) return null
        if (targetSeason <= 1) {
            val s1 = titles.find {
                val s = extractSeasonNumber(it.titleRu) ?: 1
                s == 1 &&
                        !it.id.contains("season-2") &&
                        !it.id.contains("season-3") &&
                        !it.id.contains("season-4") &&
                        !it.id.contains("season-5") &&
                        !it.id.contains("season-6") &&
                        !it.id.contains("season-7")
            }
            if (s1 != null) return s1
        } else {
            val sMatch = titles.find {
                val s = extractSeasonNumber(it.titleRu)
                s == targetSeason ||
                        it.id.contains("season-$targetSeason") ||
                        it.titleRu.contains("$targetSeason", ignoreCase = true)
            }
            if (sMatch != null) return sMatch
        }
        return titles.firstOrNull()
    }

    private fun calculateRelevanceScore(
        item: AnimeTitle,
        q: String,
        soughtSeason: Int?,
        isSeekingFinal: Boolean,
    ): Int {
        val tNorm = normalizeForSearch(item.titleRu.ifBlank { item.titleOrig ?: "" })
        val origNorm = item.titleOrig?.let { normalizeForSearch(it) } ?: ""
        val qNorm = normalizeForSearch(q)

        val cleanQ = q.replace(Regex("(?i)\\b(\\d+\\s*сезон|сезон\\s*\\d+|\\d+\\s*(?:st|nd|rd|th)?\\s*season|season\\s*\\d+|тв-?\\d+|тв\\s*\\d+|финал)\\b"), "").trim()
        val cleanQNorm = normalizeForSearch(cleanQ)

        val activeQNorm = if (cleanQNorm.isNotBlank()) cleanQNorm else qNorm
        val qWords = activeQNorm.split(" ").filter { it.isNotBlank() && it !in STOP_WORDS }
        val tWords = tNorm.split(" ").filter { it.isNotBlank() && it !in STOP_WORDS }
        val tWordsSet = tWords.toSet()
        val origWordsSet = origNorm.split(" ").filter { it.isNotBlank() && it !in STOP_WORDS }.toSet()

        val matchCount = qWords.count { w ->
            tWordsSet.contains(w) || origWordsSet.contains(w) ||
                    tWordsSet.any { tw -> tw.contains(w) } || origWordsSet.any { ow -> ow.contains(w) }
        }

        // Hard filter: if query has significant words and title has 0 matches, completely discard
        if (qWords.isNotEmpty() && matchCount == 0) {
            return -999999
        }

        var score = 1000

        // Ratio of matched words
        if (qWords.isNotEmpty()) {
            val ratio = matchCount.toDouble() / qWords.size.toDouble()
            if (ratio >= 1.0) {
                score += 5000
                val extraWords = maxOf(0, tWords.size - qWords.size)
                score -= minOf(1500, extraWords * 120)
            } else {
                score += (ratio * 1000).toInt() - 3000
            }
        }

        // Exact / prefix title matches
        if (tNorm == qNorm || (cleanQNorm.isNotBlank() && tNorm == cleanQNorm)) {
            score += 3500
        } else if (cleanQNorm.isNotBlank() && (tNorm.startsWith("$cleanQNorm тв") || tNorm.startsWith("$cleanQNorm 1") || tNorm.startsWith("$cleanQNorm "))) {
            score += 2500
        } else if (tNorm.startsWith(qNorm) || (cleanQNorm.isNotBlank() && tNorm.startsWith(cleanQNorm))) {
            score += 1500
        }

        val typeStr = item.type?.lowercase().orEmpty()
        val isSpecial = tNorm.contains("спэшл") || tNorm.contains("спешл") || tNorm.contains("special") ||
                tNorm.contains("ova") || tNorm.contains("ова") || tNorm.contains("тиби") || tNorm.contains("чиби") ||
                tNorm.contains("воспоминания") || typeStr.contains("спешл") || typeStr.contains("ova")
        val isMovie = typeStr == "anime" || typeStr.contains("фильм") || typeStr.contains("movie") ||
                tNorm.contains("фильм") || tNorm.contains("movie") || tNorm.contains("хроника") ||
                tNorm.contains("героя") || tNorm.contains("героев") ||
                Regex("\\b(?:2|3|4|5|6|7|8|9|10):\\s").containsMatchIn(item.titleRu.lowercase())
        val isSpinoff = tNorm.contains("средней школы") || tNorm.contains("чудо титаны") ||
                tNorm.contains("сникерс") || tNorm.contains("драма") || tNorm.contains("вне закона")

        val season = extractSeasonNumber(item.titleRu)
            ?: if (!isSpecial && !isMovie && !isSpinoff && !tNorm.contains("финал")) 1 else if (tNorm.contains("финал")) 4 else null
        val part = extractPartNumber(item.titleRu)

        if (isSpecial) {
            score -= 1200
        } else if (isSpinoff) {
            score -= 900
        } else if (isMovie) {
            score -= 700
        } else {
            score += 300
            if (soughtSeason != null) {
                if (season == soughtSeason) {
                    score += 3000
                } else {
                    score -= 1000
                }
            } else if (isSeekingFinal) {
                if (tNorm.contains("финал") || season == 4) {
                    score += 3000
                }
            } else {
                if (season != null) {
                    score += 800 + (10 - season.coerceIn(1, 9)) * 120 + (5 - part.coerceIn(1, 4)) * 10
                }
            }
        }

        val ratingBonus = ((item.rating ?: 0.0) * 5).toInt().coerceIn(0, 50)
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

            // Check if AniLibria has this anime to enrich dubbings & opening/ending timestamps
            val aniMatches = try {
                ApiClient.searchAniLibria(resolvedTitleRu, limit = 3)
            } catch (_: Exception) { emptyList() }
            val bestAni = aniMatches.firstOrNull { it.shikimoriId != null && it.shikimoriId == effectiveShikiId }
                ?: aniMatches.firstOrNull()
            val aniDubs = if (bestAni != null) {
                try { ApiClient.getAniLibriaDetails(bestAni.id)?.dubbings ?: emptyList() } catch (_: Exception) { emptyList() }
            } else emptyList()

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
                dubbings = aniDubs,
                rawDescription = resolvedDesc,
            )
        } else {
            val aniDetails = ApiClient.getAniLibriaDetails(idOrAlias)
                ?: return cachedDetailsOrNull(idOrAlias)?.let { withOfflineDubs(it) }
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
        val anilibriaRefEps = details.dubbings.flatMap { it.episodes }

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
                val ref = anilibriaRefEps.find { it.ordinal == ep.ordinal }
                ep.copy(
                    isFiller = fillers.contains(ep.ordinal) || (ref?.isFiller == true),
                    opening = ep.opening ?: ref?.opening,
                    ending = ep.ending ?: ref?.ending,
                    durationSec = ep.durationSec ?: ref?.durationSec,
                )
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

        // Format HiAnime dubs (English Dub and Sub)
        try {
            val rawEnQuery = details.title.titleOrig?.takeIf { it.isNotBlank() }
                ?: (if (details.title.titleRu.any { it in 'a'..'z' || it in 'A'..'Z' }) details.title.titleRu else titleQuery)
            if (!rawEnQuery.isNullOrBlank()) {
                val cleanEnQuery = rawEnQuery.replace(Regex("\\[[^\\]]*\\]|\\([^\\)]*\\)"), "").trim()
                var hiMatches = HiAnimeClient.searchHiAnime(cleanEnQuery)
                if (hiMatches.isEmpty() && cleanEnQuery != rawEnQuery) {
                    hiMatches = HiAnimeClient.searchHiAnime(rawEnQuery)
                }
                val targetSeason = extractSeasonNumber(details.title.titleRu.ifBlank { titleQuery ?: "" }) ?: 1
                val bestMatch = matchBestHiAnimeTitle(hiMatches, targetSeason) ?: hiMatches.firstOrNull()
                if (bestMatch != null) {
                    val hiEpisodes = HiAnimeClient.getEpisodes(bestMatch.id)
                    if (hiEpisodes.isNotEmpty()) {
                        val dubEps = hiEpisodes.map { ep ->
                            val ref = anilibriaRefEps.find { it.ordinal == ep.ordinal }
                            ep.copy(
                                hlsStreams = ep.hlsStreams + mapOf("is_dub" to "true"),
                                isFiller = fillers.contains(ep.ordinal) || (ref?.isFiller == true),
                                opening = ep.opening ?: ref?.opening,
                                ending = ep.ending ?: ref?.ending,
                                durationSec = ep.durationSec ?: ref?.durationSec,
                            )
                        }
                        val subEps = hiEpisodes.map { ep ->
                            val ref = anilibriaRefEps.find { it.ordinal == ep.ordinal }
                            ep.copy(
                                hlsStreams = ep.hlsStreams + mapOf("is_dub" to "false"),
                                isFiller = fillers.contains(ep.ordinal) || (ref?.isFiller == true),
                                opening = ep.opening ?: ref?.opening,
                                ending = ep.ending ?: ref?.ending,
                                durationSec = ep.durationSec ?: ref?.durationSec,
                            )
                        }
                        val dubFirst = dubEps.firstOrNull()?.ordinal ?: 1
                        val dubLast = dubEps.lastOrNull()?.ordinal ?: dubEps.size
                        val dubLabel = if (dubFirst > 1) {
                            "English Dub (HiAnime) (${dubFirst}–${dubLast} эп.)"
                        } else {
                            "English Dub (HiAnime) (${dubEps.size} эп.)"
                        }
                        val subFirst = subEps.firstOrNull()?.ordinal ?: 1
                        val subLast = subEps.lastOrNull()?.ordinal ?: subEps.size
                        val subLabel = if (subFirst > 1) {
                            "English Sub (HiAnime) (${subFirst}–${subLast} эп.)"
                        } else {
                            "English Sub (HiAnime) (${subEps.size} эп.)"
                        }
                        allDubs.add(
                            DubbingGroup(
                                id = "hianime_dub_${bestMatch.id}",
                                title = dubLabel,
                                source = AnimeSource.HIANIME,
                                episodes = dubEps,
                            )
                        )
                        allDubs.add(
                            DubbingGroup(
                                id = "hianime_sub_${bestMatch.id}",
                                title = subLabel,
                                source = AnimeSource.HIANIME,
                                episodes = subEps,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AnimeRepository", "Error loading HiAnime dubs", e)
        }

        // Smart sort dubs:
        // Priority 1: AniLibria as main Russian dub
        // Priority 2: English Dub and English Sub right next to it so it is immediately accessible
        // Priority 3: Other full Russian dubs starting at ep 1 by episode count descending
        // Priority 4: Partial dubs
        val sortedDubs = allDubs.sortedWith(
            compareByDescending<DubbingGroup> { dub ->
                when {
                    dub.source == AnimeSource.ANILIBRIA -> 1000
                    dub.id.startsWith("hianime_dub") -> 900
                    dub.id.startsWith("hianime_sub") -> 850
                    dub.episodes.firstOrNull()?.ordinal == 1 -> 500
                    else -> 0
                }
            }.thenByDescending { dub ->
                dub.episodes.size
            }
        )

        val result = details.copy(dubbings = sortedDubs)
        if (result.dubbings.isNotEmpty()) {
            cacheDetails(idOrAlias, result)
        } else {
            val cached = cachedDetailsOrNull(idOrAlias)
            if (cached != null) return withOfflineDubs(cached)
        }
        return withOfflineDubs(result)
    }

    // =========================================================================
    // Offline downloaded episodes + details cache
    // =========================================================================

    private fun withOfflineDubs(details: AnimeDetails): AnimeDetails {
        if (!DownloadManager.isInitialized()) return details
        val offline = DownloadManager.downloadedFor(details.title.id)
        if (offline.isEmpty()) return details
        val episodes = offline.map { rec ->
            AnimeEpisode(
                ordinal = rec.episodeOrdinal,
                name = rec.episodeName ?: "Серия ${rec.episodeOrdinal}",
                hlsStreams = mapOf(OFFLINE_STREAM_KEY to rec.fileUri),
                durationSec = rec.durationSec,
            )
        }
        val dub = DubbingGroup(
            id = "offline",
            title = "Скачано (офлайн)",
            source = AnimeSource.ANILIBRIA,
            episodes = episodes,
        )
        return details.copy(dubbings = listOf(dub) + details.dubbings)
    }

    private data class CachedDetails(val savedAt: Long, val details: AnimeDetails)

    // Кэш деталей читается из UI-потока и пишется из IO — нужен потокобезопасный доступ.
    private val detailsCacheLock = Any()
    @Volatile
    private var detailsCache: MutableMap<String, CachedDetails>? = null

    private fun cacheFile() = File(appContext.filesDir, "anime_details_cache.json")

    private fun loadCache(): MutableMap<String, CachedDetails> {
        detailsCache?.let { return it }
        synchronized(detailsCacheLock) {
            detailsCache?.let { return it }
            val map = mutableMapOf<String, CachedDetails>()
            try {
                val file = cacheFile()
                if (file.exists()) {
                    val type = object : TypeToken<Map<String, CachedDetails>>() {}.type
                    val parsed: Map<String, CachedDetails>? = gson.fromJson(file.readText(), type)
                    if (parsed != null) map.putAll(parsed)
                }
            } catch (_: Exception) {}
            detailsCache = map
            return map
        }
    }

    private fun cachedDetailsOrNull(id: String): AnimeDetails? = loadCache()[id]?.details

    private suspend fun cacheDetails(id: String, details: AnimeDetails) = withContext(Dispatchers.IO) {
        try {
            // Запись тоже под блокировкой: параллельная запись в тот же файл
            // могла привести к повреждённому JSON.
            synchronized(detailsCacheLock) {
                val map = loadCache()
                map[id] = CachedDetails(System.currentTimeMillis(), details)
                if (map.size > 150) {
                    val trimmed = map.entries
                        .sortedByDescending { it.value.savedAt }
                        .take(120)
                        .associate { it.key to it.value }
                    map.clear()
                    map.putAll(trimmed)
                }
                cacheFile().writeText(gson.toJson(map))
            }
        } catch (_: Exception) {}
    }

    suspend fun resolveEpisodeStream(episode: AnimeEpisode, source: AnimeSource): Map<String, String> {
        if (source == AnimeSource.ANILIBRIA) {
            return episode.hlsStreams
        }
        if (source == AnimeSource.HIANIME) {
            val epId = episode.hlsStreams["hianime_ep_id"] ?: return episode.hlsStreams
            val isDub = episode.hlsStreams["is_dub"] == "true"
            val resolved = HiAnimeClient.resolveStream(epId, isDub) ?: return emptyMap()
            hiAnimeSubtitlesCache[epId] = resolved.subtitles
            if (resolved.opening != null || resolved.ending != null) {
                hiAnimeSkipCache[epId] = Pair(resolved.opening, resolved.ending)
            }
            return mapOf(
                "1080" to resolved.streamUrl,
                "auto" to resolved.streamUrl,
            )
        }
        val embedUrl = episode.hlsStreams["embed"] ?: return episode.hlsStreams
        return ApiClient.resolveKodikStream(embedUrl)
    }

    fun getResolvedSubtitles(episode: AnimeEpisode): List<SubtitleTrack> {
        val epId = episode.hlsStreams["hianime_ep_id"]
        val fromCache = if (epId != null) hiAnimeSubtitlesCache[epId] else null
        return fromCache ?: episode.subtitles
    }

    fun getHiAnimeSkip(episode: AnimeEpisode): Pair<SkipTimestamps?, SkipTimestamps?>? {
        val epId = episode.hlsStreams["hianime_ep_id"] ?: return null
        return hiAnimeSkipCache[epId]
    }

    suspend fun fetchAllAvailableSubtitles(anime: AnimeTitle, episode: AnimeEpisode): List<SubtitleTrack> {
        val list = mutableListOf<SubtitleTrack>()
        list.addAll(getResolvedSubtitles(episode))
        try {
            val jimaku = JimakuClient.fetchEpisodeSubtitles(
                anilistId = anime.shikimoriId,
                title = anime.titleOrig ?: anime.titleRu,
                episodeOrdinal = episode.ordinal,
            )
            list.addAll(jimaku)
        } catch (_: Exception) {}
        return list.distinctBy { it.url }
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

    // Синхронизация операций с историей: saveProgress вызывается из плеера (главный поток),
    // а restoreData — из фоновой синхронизации с ПК. Без блокировки одно из
    // обновлений могло затереться другим.
    private val historyLock = Any()

    fun saveProgress(progress: WatchProgress) {
        val cleanProgress = progress.sanitized().copy(updatedAt = System.currentTimeMillis())
        synchronized(historyLock) {
            val current = _history.value.toMutableList()
            current.removeAll { it.anime.id == cleanProgress.anime.id }
            current.add(0, cleanProgress)
            // Keep up to 50 items
            val trimmed = if (current.size > 50) current.take(50) else current
            _history.value = trimmed.toList()
            prefs.edit().putString("watch_history", gson.toJson(trimmed)).apply()
        }
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
        synchronized(historyLock) {
            _bookmarks.value = cleanBookmarks
            _history.value = cleanHistory
            prefs.edit()
                .putString("bookmarks", gson.toJson(cleanBookmarks))
                .putString("watch_history", gson.toJson(cleanHistory))
                .apply()
        }
    }
}

