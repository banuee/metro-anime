package dev.metro.anime.data.api

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.metro.anime.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object ApiClient {
    private const val TAG = "ApiClient"
    private const val ANILIBRIA_BASE = "https://anilibria.top"
    private const val KODIK_API_URL = "https://kodik-api.com/search"
    private const val KODIK_TOKEN = "56a768d08f43091901c44b54fe970049"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // =========================================================================
    // AniLibria API v1
    // =========================================================================

    suspend fun getAniLibriaLatest(limit: Int = 24): List<AnimeTitle> = withContext(Dispatchers.IO) {
        val url = "$ANILIBRIA_BASE/api/v1/anime/releases/latest?limit=$limit"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MetroAnime/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = gson.fromJson(body, JsonArray::class.java)
                val list = mutableListOf<AnimeTitle>()
                for (item in jsonArray) {
                    val obj = item.asJsonObject
                    list.add(parseAniLibriaTitle(obj))
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching AniLibria latest", e)
            emptyList()
        }
    }

    suspend fun getShikimoriSchedule(shikimoriId: Long): AnimeSchedule? = withContext(Dispatchers.IO) {
        if (shikimoriId <= 0) return@withContext null
        val domains = listOf("https://shikimori.io", "https://shikimori.one")
        for (base in domains) {
            val url = "$base/api/animes/$shikimoriId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MetroAnime/1.0")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val obj = gson.fromJson(body, JsonObject::class.java) ?: return@use
                    val status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString
                    val nextAt = obj.get("next_episode_at")?.takeIf { !it.isJsonNull }?.asString?.let { parseIsoToMillis(it) }
                    val episodesTotal = obj.get("episodes")?.takeIf { !it.isJsonNull }?.asInt?.takeIf { it > 0 }
                    val episodesAired = obj.get("episodes_aired")?.takeIf { !it.isJsonNull }?.asInt
                    val broadcast = obj.get("broadcast")?.takeIf { !it.isJsonNull }?.asString

                    return@withContext AnimeSchedule(
                        isOngoing = status == "ongoing",
                        status = status,
                        nextEpisode = if (status == "ongoing" && episodesAired != null) episodesAired + 1 else null,
                        nextEpisodeAt = nextAt,
                        episodesTotal = episodesTotal,
                        episodesAired = episodesAired,
                        broadcast = broadcast,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching Shikimori schedule at $base", e)
            }
        }
        null
    }

    suspend fun getAniLibriaScheduleFor(releaseId: String): AnimeSchedule? = withContext(Dispatchers.IO) {
        val idNorm = releaseId.removePrefix("kodik_").trim()
        if (idNorm.isBlank()) return@withContext null
        val url = "$ANILIBRIA_BASE/api/v1/anime/schedule/week"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MetroAnime/1.0")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val jsonArray = gson.fromJson(body, JsonArray::class.java) ?: return@withContext null
                for (elem in jsonArray) {
                    val item = elem.asJsonObject
                    val releaseObj = item.getAsJsonObject("release") ?: continue
                    val relId = releaseObj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    if (relId != idNorm) continue
                    val nextEp = item.get("next_release_episode_number")?.takeIf { !it.isJsonNull }?.asInt
                    val published = item.getAsJsonObject("published_release_episode")
                        ?.get("ordinal")?.takeIf { !it.isJsonNull }?.asInt
                    val publishDay = releaseObj.getAsJsonObject("publish_day")
                        ?.get("value")?.takeIf { !it.isJsonNull }?.asInt
                    val isOngoing = releaseObj.get("is_ongoing")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    return@withContext AnimeSchedule(
                        isOngoing = isOngoing,
                        status = if (isOngoing) "ongoing" else "released",
                        nextEpisode = nextEp,
                        nextEpisodeAt = null,
                        episodesTotal = releaseObj.get("episodes_total")?.takeIf { !it.isJsonNull }?.asInt?.takeIf { it > 0 },
                        episodesAired = published,
                        publishDay = publishDay,
                    )
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching AniLibria schedule for release $releaseId", e)
            null
        }
    }

    private fun parseIsoToMillis(value: String): Long? = try {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getShikimoriFinishedIds(shikimoriIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        val ids = shikimoriIds.filter { it > 0 }.distinct().take(50)
        if (ids.isEmpty()) return@withContext emptySet()
        val idParam = ids.joinToString(",")
        val domains = listOf("https://shikimori.io", "https://shikimori.one")
        for (base in domains) {
            val url = "$base/api/animes?ids=$idParam&limit=50"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MetroAnime/1.0")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val arr = gson.fromJson(body, JsonArray::class.java) ?: return@use
                    val finished = mutableSetOf<Long>()
                    for (elem in arr) {
                        val obj = elem.asJsonObject
                        val id = obj.get("id")?.asLong ?: continue
                        val status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString
                        if (status != null && status != "ongoing" && status != "anons") {
                            finished.add(id)
                        }
                    }
                    return@withContext finished
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching Shikimori statuses at $base", e)
            }
        }
        emptySet()
    }

    suspend fun searchAniLibria(query: String, limit: Int = 24): List<AnimeTitle> = withContext(Dispatchers.IO) {
        val url = "$ANILIBRIA_BASE/api/v1/anime/catalog/releases?f[search]=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MetroAnime/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val root = gson.fromJson(body, JsonObject::class.java)
                val data = root.getAsJsonArray("data") ?: return@withContext emptyList()
                val list = mutableListOf<AnimeTitle>()
                for (item in data) {
                    list.add(parseAniLibriaTitle(item.asJsonObject))
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching AniLibria", e)
            emptyList()
        }
    }

    suspend fun resolveShikimoriRussianTitle(query: String): String? = withContext(Dispatchers.IO) {
        val cleanQuery = query.replace(
            Regex("(?i)\\b(\\d+\\s*сезон|сезон\\s*\\d+|\\d+\\s*(?:st|nd|rd|th)?\\s*season|season\\s*\\d+|тв-?\\d+|тв\\s*\\d+)\\b"),
            ""
        ).trim()
        val searchQuery = if (cleanQuery.isNotBlank()) cleanQuery else query
        val isExplicitSeason1 = query.contains("1 сезон", ignoreCase = true) ||
                query.contains("сезон 1", ignoreCase = true) ||
                query.contains("тв-1", ignoreCase = true) ||
                query.contains("тв 1", ignoreCase = true) ||
                query.contains("1st season", ignoreCase = true) ||
                query.contains("season 1", ignoreCase = true)

        val url = "https://shikimori.io/api/animes?search=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}&limit=5"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MetroAnime/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val arr = gson.fromJson(body, JsonArray::class.java)
                if (arr != null && arr.size() > 0) {
                    if (isExplicitSeason1) {
                        for (elem in arr) {
                            val obj = elem.asJsonObject
                            val nameRu = obj.get("russian")?.takeIf { !it.isJsonNull }?.asString
                            val nameEn = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                            val title = nameRu ?: nameEn ?: continue
                            val kind = obj.get("kind")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            val lower = title.lowercase()
                            val isSeason2 = lower.contains(" 2") || lower.contains(" 2nd") || lower.contains("второй") || lower.contains("2 сезон")
                            val isSpecial = kind in listOf("special", "ova", "movie")
                            if (!isSeason2 && !isSpecial) {
                                return@withContext title
                            }
                        }
                    }
                    val first = arr[0].asJsonObject
                    return@withContext first.get("russian")?.takeIf { !it.isJsonNull }?.asString
                        ?: first.get("name")?.takeIf { !it.isJsonNull }?.asString
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getShikimoriDetails(shikimoriId: Long): AnimeTitle? = withContext(Dispatchers.IO) {
        val domains = listOf("https://shikimori.io", "https://shikimori.one")
        for (base in domains) {
            val url = "$base/api/animes/$shikimoriId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MetroAnime/1.0")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val obj = gson.fromJson(body, JsonObject::class.java)
                    val ru = obj.get("russian")?.takeIf { !it.isJsonNull }?.asString
                    val en = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                    val imgObj = obj.getAsJsonObject("image")
                    val origImg = imgObj?.get("original")?.takeIf { !it.isJsonNull }?.asString
                    val poster = if (origImg != null) {
                        if (origImg.startsWith("http")) origImg else "https://shikimori.io$origImg"
                    } else "https://shikimori.io/system/animes/original/$shikimoriId.jpg"

                    val descRaw = obj.get("description")?.takeIf { !it.isJsonNull }?.asString
                    val cleanDesc = descRaw?.replace(Regex("\\[/?(?:character|anime|person|manga|b|i|u|url|code|spoiler)[^\\]]*\\]"), "")?.trim()

                    val airedOn = obj.get("aired_on")?.takeIf { !it.isJsonNull }?.asString
                    val year = airedOn?.take(4)?.toIntOrNull()
                    val score = obj.get("score")?.takeIf { !it.isJsonNull }?.asDouble
                    val eps = obj.get("episodes")?.takeIf { !it.isJsonNull }?.asInt

                    val genresList = mutableListOf<String>()
                    obj.getAsJsonArray("genres")?.forEach { g ->
                        if (g.isJsonObject) {
                            g.asJsonObject.get("russian")?.takeIf { !it.isJsonNull }?.asString?.let { genresList.add(it) }
                                ?: g.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { genresList.add(it) }
                        }
                    }

                    val kind = obj.get("kind")?.takeIf { !it.isJsonNull }?.asString
                    val kindRu = when (kind?.lowercase()) {
                        "tv" -> "ТВ Сериал"
                        "movie" -> "Фильм"
                        "ova" -> "OVA"
                        "ona" -> "ONA"
                        "special" -> "Спешл"
                        else -> kind
                    }

                    return@withContext AnimeTitle(
                        id = "kodik_shiki_$shikimoriId",
                        titleRu = ru ?: en ?: "Аниме",
                        titleOrig = en,
                        posterUrl = poster,
                        description = cleanDesc,
                        year = year,
                        type = kindRu,
                        episodesCount = eps,
                        genres = genresList,
                        rating = score,
                        shikimoriId = shikimoriId,
                        source = AnimeSource.KODIK,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching Shikimori details at $base", e)
            }
        }
        null
    }

    suspend fun searchShikimori(query: String, limit: Int = 15): List<AnimeTitle> = withContext(Dispatchers.IO) {
        val cleanQuery = query.replace(
            Regex("(?i)\\b(\\d+\\s*сезон|сезон\\s*\\d+|\\d+\\s*(?:st|nd|rd|th)?\\s*season|season\\s*\\d+|тв-?\\d+|тв\\s*\\d+)\\b"),
            ""
        ).trim()
        val searchQuery = if (cleanQuery.isNotBlank()) cleanQuery else query
        val domains = listOf("https://shikimori.io", "https://shikimori.one")
        for (base in domains) {
            val url = "$base/api/animes?search=${java.net.URLEncoder.encode(searchQuery, "UTF-8")}&limit=$limit"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MetroAnime/1.0")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val arr = gson.fromJson(body, JsonArray::class.java) ?: return@use
                    val list = mutableListOf<AnimeTitle>()
                    for (elem in arr) {
                        val obj = elem.asJsonObject
                        val id = obj.get("id")?.asLong ?: continue
                        val ru = obj.get("russian")?.takeIf { !it.isJsonNull }?.asString
                        val en = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                        val title = ru ?: en ?: continue
                        val imgObj = obj.getAsJsonObject("image")
                        val origImg = imgObj?.get("original")?.takeIf { !it.isJsonNull }?.asString
                        val poster = if (origImg != null) {
                            if (origImg.startsWith("http")) origImg else "https://shikimori.io$origImg"
                        } else "https://shikimori.io/system/animes/original/$id.jpg"

                        val kind = obj.get("kind")?.takeIf { !it.isJsonNull }?.asString
                        val kindRu = when (kind?.lowercase()) {
                            "tv" -> "ТВ Сериал"
                            "movie" -> "Фильм"
                            "ova" -> "OVA"
                            "ona" -> "ONA"
                            "special" -> "Спешл"
                            else -> kind
                        }
                        val score = obj.get("score")?.takeIf { !it.isJsonNull }?.asDouble
                        val eps = obj.get("episodes")?.takeIf { !it.isJsonNull }?.asInt
                        val airedOn = obj.get("aired_on")?.takeIf { !it.isJsonNull }?.asString
                        val year = airedOn?.take(4)?.toIntOrNull()

                        list.add(
                            AnimeTitle(
                                id = "kodik_shiki_$id",
                                titleRu = title,
                                titleOrig = en,
                                posterUrl = poster,
                                year = year,
                                type = kindRu,
                                episodesCount = eps,
                                rating = score,
                                shikimoriId = id,
                                source = AnimeSource.KODIK,
                            )
                        )
                    }
                    if (list.isNotEmpty()) return@withContext list
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error searching Shikimori at $base", e)
            }
        }
        emptyList()
    }

    suspend fun searchKodikTitles(query: String, limit: Int = 100): List<AnimeTitle> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("token", KODIK_TOKEN)
            .add("title", query)
            .add("limit", limit.coerceIn(1, 100).toString())
            .add("types", "anime,anime-serial")
            .add("with_material_data", "true")

        val request = Request.Builder()
            .url(KODIK_API_URL)
            .post(formBuilder.build())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val root = gson.fromJson(body, JsonObject::class.java)
                val results = root.getAsJsonArray("results") ?: return@withContext emptyList()

                val list = mutableListOf<AnimeTitle>()
                val seenTitles = mutableSetOf<String>()

                for (res in results) {
                    val obj = res.asJsonObject
                    val rawTitleRu = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val titleOrig = obj.get("title_orig")?.takeIf { !it.isJsonNull }?.asString
                    val shikiId = obj.get("shikimori_id")?.takeIf { !it.isJsonNull }?.asLong

                    val matData = obj.getAsJsonObject("material_data")
                    val matTitle = matData?.get("anime_title")?.takeIf { !it.isJsonNull }?.asString
                    val matPoster = matData?.get("poster_url")?.takeIf { !it.isJsonNull }?.asString
                    val matDesc = matData?.get("description")?.takeIf { !it.isJsonNull }?.asString
                    val matRating = matData?.get("shikimori_rating")?.takeIf { !it.isJsonNull }?.asDouble
                        ?: matData?.get("kinopoisk_rating")?.takeIf { !it.isJsonNull }?.asDouble

                    // Preserve season indicators in titles (e.g. [ТВ-1], [ТВ-2], etc.)
                    val displayTitle = rawTitleRu.trim()
                    val uniqueKey = if (shikiId != null && shikiId > 0) "shiki_$shikiId" else displayTitle.lowercase()
                    if (seenTitles.contains(uniqueKey)) continue
                    seenTitles.add(uniqueKey)

                    val year = obj.get("year")?.takeIf { !it.isJsonNull }?.asInt
                        ?: matData?.get("year")?.takeIf { !it.isJsonNull }?.asInt

                    val rawType = obj.get("type")?.takeIf { !it.isJsonNull }?.asString
                    val type = when (rawType?.lowercase()) {
                        "anime-serial" -> "Сериал"
                        "anime" -> "Фильм"
                        else -> rawType
                    }

                    val screens = obj.getAsJsonArray("screenshots")
                    val poster = if (shikiId != null && shikiId > 0) {
                        "https://shikimori.io/system/animes/original/$shikiId.jpg"
                    } else if (!matPoster.isNullOrBlank()) {
                        if (matPoster.startsWith("//")) "https:$matPoster" else matPoster
                    } else if (screens != null && screens.size() > 0) {
                        var p = screens[0].asString
                        if (p.startsWith("//")) p = "https:$p"
                        p
                    } else ""

                    val episodesCount = obj.get("episodes_count")?.takeIf { !it.isJsonNull }?.asInt
                        ?: matData?.get("episodes_total")?.takeIf { !it.isJsonNull }?.asInt

                    val genresList = mutableListOf<String>()
                    matData?.getAsJsonArray("anime_genres")?.forEach { g ->
                        if (g.isJsonPrimitive) genresList.add(g.asString)
                    }

                    val animeId = if (shikiId != null && shikiId > 0) "kodik_shiki_$shikiId" else "kodik_${obj.get("id")?.asString ?: rawTitleRu}"

                    list.add(
                        AnimeTitle(
                            id = animeId,
                            titleRu = displayTitle,
                            titleOrig = titleOrig,
                            posterUrl = poster,
                            description = matDesc,
                            year = year,
                            type = type,
                            episodesCount = episodesCount,
                            genres = genresList,
                            rating = matRating,
                            shikimoriId = shikiId,
                            source = AnimeSource.KODIK,
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getAniLibriaDetails(idOrAlias: String): AnimeDetails? = withContext(Dispatchers.IO) {
        val url = "$ANILIBRIA_BASE/api/v1/anime/releases/$idOrAlias"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MetroAnime/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val obj = gson.fromJson(body, JsonObject::class.java)
                val title = parseAniLibriaTitle(obj)

                val episodesArray = obj.getAsJsonArray("episodes") ?: JsonArray()
                val episodeList = mutableListOf<AnimeEpisode>()
                for (ep in episodesArray) {
                    val epObj = ep.asJsonObject
                    val ordinal = epObj.get("ordinal")?.asInt ?: 1
                    val epName = epObj.get("name")?.takeIf { !it.isJsonNull }?.asString

                    val previewObj = epObj.getAsJsonObject("preview")
                    val previewUrl = previewObj?.get("src")?.takeIf { !it.isJsonNull }?.asString?.let {
                        if (it.startsWith("http")) it else "$ANILIBRIA_BASE$it"
                    }

                    val streams = mutableMapOf<String, String>()
                    epObj.get("hls_1080")?.takeIf { !it.isJsonNull }?.asString?.let { streams["1080"] = cleanHlsUrl(it) }
                    epObj.get("hls_720")?.takeIf { !it.isJsonNull }?.asString?.let { streams["720"] = cleanHlsUrl(it) }
                    epObj.get("hls_480")?.takeIf { !it.isJsonNull }?.asString?.let { streams["480"] = cleanHlsUrl(it) }

                    val opObj = epObj.getAsJsonObject("opening")
                    val opStart = opObj?.get("start")?.takeIf { !it.isJsonNull }?.asInt
                    val opStop = opObj?.get("stop")?.takeIf { !it.isJsonNull }?.asInt
                    val opening = if (opStart != null && opStop != null) SkipTimestamps(opStart, opStop) else null

                    val edObj = epObj.getAsJsonObject("ending")
                    val edStart = edObj?.get("start")?.takeIf { !it.isJsonNull }?.asInt
                    val edStop = edObj?.get("stop")?.takeIf { !it.isJsonNull }?.asInt
                    val ending = if (edStart != null && edStop != null) SkipTimestamps(edStart, edStop) else null

                    episodeList.add(
                        AnimeEpisode(
                            ordinal = ordinal,
                            name = epName,
                            previewUrl = previewUrl,
                            hlsStreams = streams,
                            durationSec = epObj.get("duration")?.takeIf { !it.isJsonNull }?.asInt,
                            opening = opening,
                            ending = ending,
                        )
                    )
                }

                episodeList.sortBy { it.ordinal }

                val anilibriaDub = DubbingGroup(
                    id = "anilibria",
                    title = "AniLibria (Оригинал)",
                    source = AnimeSource.ANILIBRIA,
                    episodes = episodeList,
                )

                AnimeDetails(
                    title = title,
                    dubbings = listOf(anilibriaDub),
                    rawDescription = obj.get("description")?.takeIf { !it.isJsonNull }?.asString,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching AniLibria details", e)
            null
        }
    }

    private fun cleanHlsUrl(rawUrl: String): String {
        return rawUrl.substringBefore("?")
    }

    private fun parseAniLibriaTitle(obj: JsonObject): AnimeTitle {
        val id = obj.get("id")?.asString ?: ""
        val nameObj = obj.getAsJsonObject("name")
        val titleRu = nameObj?.get("main")?.takeIf { !it.isJsonNull }?.asString
            ?: obj.get("name")?.takeIf { !it.isJsonNull }?.asString
            ?: "Без названия"
        val titleOrig = nameObj?.get("english")?.takeIf { !it.isJsonNull }?.asString

        val posterObj = obj.getAsJsonObject("poster")
        var posterUrl = posterObj?.getAsJsonObject("optimized")?.get("src")?.takeIf { !it.isJsonNull }?.asString
        if (posterUrl == null) {
            posterUrl = posterObj?.get("src")?.takeIf { !it.isJsonNull }?.asString ?: ""
        }
        if (posterUrl.isNotEmpty() && !posterUrl.startsWith("http")) {
            posterUrl = "$ANILIBRIA_BASE$posterUrl"
        }

        val year = obj.get("year")?.takeIf { !it.isJsonNull }?.asInt
        val typeDesc = obj.getAsJsonObject("type")?.get("description")?.takeIf { !it.isJsonNull }?.asString
        val seasonDesc = obj.getAsJsonObject("season")?.get("description")?.takeIf { !it.isJsonNull }?.asString
        val episodesCount = obj.get("episodes_total")?.takeIf { !it.isJsonNull }?.asInt

        val genres = mutableListOf<String>()
        obj.getAsJsonArray("genres")?.forEach { g ->
            if (g.isJsonObject) {
                g.asJsonObject.get("name")?.asString?.let { genres.add(it) }
            } else if (g.isJsonPrimitive) {
                genres.add(g.asString)
            }
        }

        val shikimoriId = obj.getAsJsonObject("shikimori")?.get("id")?.takeIf { !it.isJsonNull }?.asLong
        val rating = obj.getAsJsonObject("shikimori")?.get("rating")?.takeIf { !it.isJsonNull }?.asDouble
        val isOngoing = obj.get("is_ongoing")?.takeIf { !it.isJsonNull }?.asBoolean ?: false

        return AnimeTitle(
            id = id,
            titleRu = titleRu,
            titleOrig = titleOrig,
            posterUrl = posterUrl,
            description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString,
            year = year,
            type = typeDesc,
            season = seasonDesc,
            episodesCount = episodesCount,
            genres = genres,
            rating = rating,
            shikimoriId = shikimoriId,
            isOngoing = isOngoing,
            source = AnimeSource.ANILIBRIA,
        )
    }

    // =========================================================================
    // Kodik API
    // =========================================================================

    suspend fun getKodikDubs(shikimoriId: Long?, titleQuery: String?): List<DubbingGroup> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("token", KODIK_TOKEN)
            .add("with_episodes", "true")
            .add("limit", "100")

        if (shikimoriId != null && shikimoriId > 0) {
            formBuilder.add("shikimori_id", shikimoriId.toString())
        } else if (!titleQuery.isNullOrBlank()) {
            formBuilder.add("title", titleQuery)
        } else {
            return@withContext emptyList()
        }

        val request = Request.Builder()
            .url(KODIK_API_URL)
            .post(formBuilder.build())
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val root = gson.fromJson(body, JsonObject::class.java)
                val results = root.getAsJsonArray("results") ?: return@withContext emptyList()

                val queryLower = titleQuery?.lowercase() ?: ""
                val isSpecialQuery = queryLower.contains("спешл") ||
                        queryLower.contains("спэшл") ||
                        queryLower.contains("special") ||
                        queryLower.contains("ova") ||
                        queryLower.contains("ова")

                val isSeason2Query = queryLower.contains("[тв-2]") ||
                        queryLower.contains("2 сезон") ||
                        queryLower.contains("сезон 2") ||
                        queryLower.contains("2nd season") ||
                        queryLower.contains("тв 2")

                val isSeason1Query = queryLower.contains("[тв-1]") ||
                        queryLower.contains("1 сезон") ||
                        queryLower.contains("сезон 1") ||
                        queryLower.contains("1st season") ||
                        queryLower.contains("тв 1")

                val dubGroups = mutableListOf<DubbingGroup>()
                for (res in results) {
                    val resObj = res.asJsonObject
                    val resTitle = resObj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val resTitleLower = resTitle.lowercase()
                    val resShiki = resObj.get("shikimori_id")?.takeIf { !it.isJsonNull }?.asLong
                    val resType = resObj.get("type")?.takeIf { !it.isJsonNull }?.asString ?: ""

                    // If a specific shikimoriId was requested and Kodik result has a shikimori_id, verify match
                    if (shikimoriId != null && shikimoriId > 0 && resShiki != null && resShiki != shikimoriId) {
                        continue
                    }

                    if (isSpecialQuery) {
                        val isTvSeries = resTitleLower.contains("[тв-") ||
                                resTitleLower.contains("сезон") ||
                                (resType == "anime-serial" && (resObj.get("last_episode")?.asInt ?: 0) > 15)
                        val isSpecialCandidate = resTitleLower.contains("спец") ||
                                resTitleLower.contains("спешл") ||
                                resTitleLower.contains("спэшл") ||
                                resTitleLower.contains("ova") ||
                                resTitleLower.contains("ова") ||
                                resType == "anime"
                        if (isTvSeries || !isSpecialCandidate) {
                            continue
                        }
                    } else if (isSeason2Query) {
                        val matchesS2 = resTitleLower.contains("[тв-2]") ||
                                resTitleLower.contains("2 сезон") ||
                                resTitleLower.contains("сезон 2") ||
                                (resShiki != null && resShiki == 61967L)
                        if (!matchesS2) {
                            continue
                        }
                    } else if (isSeason1Query) {
                        val isS2 = resTitleLower.contains("[тв-2]") ||
                                resTitleLower.contains("2 сезон") ||
                                resTitleLower.contains("сезон 2") ||
                                (resShiki != null && resShiki == 61967L)
                        val isSpecial = resTitleLower.contains("спец") ||
                                resTitleLower.contains("спешл") ||
                                resTitleLower.contains("спэшл") ||
                                resTitleLower.contains("ova") ||
                                resTitleLower.contains("ова")
                        if (isS2 || isSpecial) {
                            continue
                        }
                    }

                    val transObj = resObj.getAsJsonObject("translation") ?: continue
                    val transId = transObj.get("id")?.asString ?: "0"
                    val transTitle = transObj.get("title")?.asString ?: "Озвучка"

                    val seasonsObj = resObj.getAsJsonObject("seasons")
                    val episodesList = mutableListOf<AnimeEpisode>()

                    if (seasonsObj != null) {
                        val seasonKeys = seasonsObj.keySet().toList().sortedWith { s1, s2 ->
                            val n1 = s1.toIntOrNull() ?: 999
                            val n2 = s2.toIntOrNull() ?: 999
                            val rank1 = if (n1 == 0) 10000 else n1
                            val rank2 = if (n2 == 0) 10000 else n2
                            rank1.compareTo(rank2)
                        }

                        val hasRegularSeason = seasonKeys.any { (it.toIntOrNull() ?: 0) > 0 }

                        for (seasonKey in seasonKeys) {
                            val seasonNum = seasonKey.toIntOrNull() ?: 1
                            val isSpecialSeason = seasonNum == 0 && hasRegularSeason
                            val seasonData = seasonsObj.getAsJsonObject(seasonKey)
                            val epObj = seasonData?.getAsJsonObject("episodes")
                            if (epObj != null) {
                                val epKeys = epObj.keySet().toList().sortedBy { it.toIntOrNull() ?: 0 }
                                for (epKey in epKeys) {
                                    // Значение может быть не строкой (объект/JsonNull) —
                                    // asString бросил бы исключение и обрушил разбор
                                    // ВСЕХ озвучек тайтла. Поэтому проверяем тип.
                                    val epUrl = epObj.get(epKey)
                                        ?.takeIf { it.isJsonPrimitive }
                                        ?.asString
                                        ?.takeIf { it.isNotBlank() }
                                        ?: continue
                                    val fullUrl = if (epUrl.startsWith("//")) "https:$epUrl" else epUrl
                                    val rawOrd = epKey.toIntOrNull() ?: 1
                                    val ord = if (isSpecialSeason) 1000 + rawOrd else rawOrd
                                    val epName = if (isSpecialSeason) "Спешл $rawOrd" else "Серия $rawOrd"
                                    episodesList.add(
                                        AnimeEpisode(
                                            ordinal = ord,
                                            name = epName,
                                            // Store embed link in streams map temporarily
                                            hlsStreams = mapOf("embed" to fullUrl),
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // Movie / single video
                        val link = resObj.get("link")?.asString
                        if (link != null) {
                            val fullUrl = if (link.startsWith("//")) "https:$link" else link
                            episodesList.add(
                                AnimeEpisode(
                                    ordinal = 1,
                                    name = "Фильм / Серия 1",
                                    hlsStreams = mapOf("embed" to fullUrl),
                                )
                            )
                        }
                    }

                    if (episodesList.isNotEmpty()) {
                        episodesList.sortBy { it.ordinal }
                        val resId = resObj.get("id")?.asString ?: transId
                        dubGroups.add(
                            DubbingGroup(
                                id = "kodik_${transId}_$resId",
                                title = transTitle,
                                source = AnimeSource.KODIK,
                                episodes = episodesList,
                            )
                        )
                    }
                }
                dubGroups
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Kodik dubs", e)
            emptyList()
        }
    }

    /**
     * Resolves a Kodik embed URL into direct playable video streams (.m3u8).
     */
    suspend fun resolveKodikStream(embedUrl: String): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val req1 = Request.Builder().url(embedUrl).build()
            val html = client.newCall(req1).execute().use { it.body?.string().orEmpty() }

            // Extract script URL
            val scriptMatcher = Pattern.compile("<script[^>]+src=[\"']([^\"']+)[\"']").matcher(html)
            var scriptUrl: String? = null
            while (scriptMatcher.find()) {
                val s = scriptMatcher.group(1)
                if (s?.contains("app.player_single") == true) {
                    scriptUrl = s
                    break
                }
            }
            if (scriptUrl == null) return@withContext emptyMap()

            val scriptText = client.newCall(Request.Builder().url("https://kodikplayer.com$scriptUrl").build())
                .execute().use { it.body?.string().orEmpty() }

            val ajaxIdx = scriptText.indexOf("$.ajax")
            val cacheIdx = scriptText.indexOf("cache:!1")
            if (ajaxIdx == -1 || cacheIdx == -1) return@withContext emptyMap()
            val encodedPostLink = scriptText.substring(ajaxIdx + 30, cacheIdx - 3)
            val postLink = String(Base64.decode(encodedPostLink, Base64.DEFAULT)).trim()

            // Extract urlParams
            val upPattern = Pattern.compile("var urlParams = '([^']+)';").matcher(html)
            if (!upPattern.find()) return@withContext emptyMap()
            val urlParamsJson = gson.fromJson(upPattern.group(1).orEmpty(), JsonObject::class.java)

            // Extract type, videoId, hash
            val typePattern = Pattern.compile("var type = \"([^\"]+)\";").matcher(html)
            val type = if (typePattern.find()) typePattern.group(1).orEmpty() else "seria"

            val idPattern = Pattern.compile("var videoId = \"([^\"]+)\";").matcher(html)
            val videoId = if (idPattern.find()) idPattern.group(1).orEmpty() else ""

            val pathParts = embedUrl.split("/")
            val videoHash = pathParts.getOrNull(5) ?: ""

            val formBuilder = FormBody.Builder()
                .add("hash", videoHash)
                .add("id", videoId)
                .add("type", type)
                .add("d", urlParamsJson.get("d")?.asString ?: "kodikplayer.com")
                .add("d_sign", urlParamsJson.get("d_sign")?.asString ?: "")
                .add("pd", urlParamsJson.get("pd")?.asString ?: "kodikplayer.com")
                .add("pd_sign", urlParamsJson.get("pd_sign")?.asString ?: "")
                .add("ref", "")
                .add("ref_sign", urlParamsJson.get("ref_sign")?.asString ?: "")
                .add("bad_user", "true")
                .add("cdn_is_working", "true")

            val postReq = Request.Builder()
                .url("https://kodikplayer.com$postLink")
                .post(formBuilder.build())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val streamJson = client.newCall(postReq).execute().use { it.body?.string().orEmpty() }
            val streamRoot = gson.fromJson(streamJson, JsonObject::class.java)
            val linksObj = streamRoot.getAsJsonObject("links") ?: return@withContext emptyMap()

            val resultStreams = mutableMapOf<String, String>()
            for (q in listOf("1080", "720", "480", "360")) {
                val qArr = linksObj.getAsJsonArray(q) ?: continue
                if (qArr.size() > 0) {
                    val rawSrc = qArr[0].asJsonObject.get("src")?.asString ?: continue
                    val decoded = decodeKodikUrl(rawSrc)
                    if (decoded != null) {
                        val fullStream = if (decoded.startsWith("//")) "https:$decoded" else decoded
                        resultStreams[q] = fullStream
                    }
                }
            }
            resultStreams
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving Kodik stream", e)
            emptyMap()
        }
    }

    private fun decodeKodikUrl(encoded: String): String? {
        fun convertChar(c: Char, rot: Int): Char {
            val alph = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val isLower = c.isLowerCase()
            val up = c.uppercaseChar()
            val idx = alph.indexOf(up)
            if (idx != -1) {
                val shifted = alph[(idx + rot) % alph.length]
                return if (isLower) shifted.lowercaseChar() else shifted
            }
            return c
        }

        for (rot in 0 until 26) {
            val sb = StringBuilder()
            for (ch in encoded) {
                sb.append(convertChar(ch, rot))
            }
            var s = sb.toString()
            val pad = (4 - (s.length % 4)) % 4
            s += "=".repeat(pad)
            try {
                val decoded = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
                if (decoded.contains("mp4:hls:manifest") || decoded.contains(".m3u8")) {
                    return decoded
                }
            } catch (_: Exception) {}
        }
        return null
    }

    // =========================================================================
    // AniSkip API v2 (Openings & Endings timestamps)
    // =========================================================================

    suspend fun fetchAniSkip(malId: Long, episodeOrdinal: Int, durationSec: Int = 0): Pair<SkipTimestamps?, SkipTimestamps?>? = withContext(Dispatchers.IO) {
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeOrdinal?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&episodeLength=$durationSec"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MetroAnime/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val root = gson.fromJson(body, JsonObject::class.java) ?: return@withContext null
                if (root.get("found")?.asBoolean != true) return@withContext null

                val results = root.getAsJsonArray("results") ?: return@withContext null
                var opening: SkipTimestamps? = null
                var ending: SkipTimestamps? = null

                for (elem in results) {
                    val resObj = elem.asJsonObject
                    val skipType = resObj.get("skipType")?.asString ?: continue
                    val interval = resObj.getAsJsonObject("interval") ?: continue
                    val start = interval.get("startTime")?.asDouble?.toInt() ?: continue
                    val end = interval.get("endTime")?.asDouble?.toInt() ?: continue

                    if (skipType == "op" || (skipType == "mixed-op" && opening == null)) {
                        opening = SkipTimestamps(start, end)
                    } else if (skipType == "ed" || (skipType == "mixed-ed" && ending == null)) {
                        ending = SkipTimestamps(start, end)
                    }
                }

                Pair(opening, ending)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AniSkip error for MAL $malId ep $episodeOrdinal: ${e.message}")
            null
        }
    }

    // =========================================================================
    // AnimeFillerList Scraper (Filler episode ordinals)
    // =========================================================================

    private val fillerCache = java.util.concurrent.ConcurrentHashMap<String, Set<Int>>()
    private var animeFillerShowsList: List<Pair<String, String>>? = null

    private fun normalizeTitleForSearch(s: String): String {
        return s.lowercase()
            .replace("uu", "u")
            .replace("ou", "o")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
    }

    private fun buildCandidateSlug(title: String): String {
        return title.lowercase()
            .replace(":", "")
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    suspend fun fetchFillerEpisodes(titleOrig: String?, titleRu: String?): Set<Int> = withContext(Dispatchers.IO) {
        val cacheKey = "${titleOrig.orEmpty()}_${titleRu.orEmpty()}".trim()
        if (cacheKey.isBlank()) return@withContext emptySet()
        fillerCache[cacheKey]?.let { return@withContext it }

        // Clean titles of common season tags e.g. "TV-1", "2 сезон", "1st Season"
        val seasonRegex = Regex("(?i)\\b(\\d+\\s*сезон|сезон\\s*\\d+|\\d+\\s*(?:st|nd|rd|th)?\\s*season|season\\s*\\d+|тв-?\\d+|тв\\s*\\d+)\\b")
        val cleanOrig = titleOrig?.replace(seasonRegex, "")?.trim()

        val candidateSlugs = mutableListOf<String>()
        if (!cleanOrig.isNullOrBlank()) {
            candidateSlugs.add(buildCandidateSlug(cleanOrig))
            val mainPart = cleanOrig.substringBefore(':').substringBefore('-').trim()
            if (mainPart.isNotBlank() && mainPart != cleanOrig) {
                candidateSlugs.add(buildCandidateSlug(mainPart))
            }
        }
        if (!titleOrig.isNullOrBlank() && titleOrig != cleanOrig) {
            candidateSlugs.add(buildCandidateSlug(titleOrig))
        }

        var html: String? = null
        for (slug in candidateSlugs.distinct()) {
            if (slug.isBlank()) continue
            html = fetchFillerListHtml(slug)
            if (html != null) break
        }

        // If direct slugs didn't hit, search in /shows index
        if (html == null && (!cleanOrig.isNullOrBlank() || !titleOrig.isNullOrBlank())) {
            val query = cleanOrig ?: titleOrig.orEmpty()
            val matchedSlug = findBestShowSlug(query)
            if (matchedSlug != null) {
                html = fetchFillerListHtml(matchedSlug)
            }
        }

        if (html == null) {
            fillerCache[cacheKey] = emptySet()
            return@withContext emptySet()
        }

        val result = parseFillerEpisodesFromHtml(html)
        fillerCache[cacheKey] = result
        result
    }

    private fun fetchFillerListHtml(slug: String): String? {
        val url = "https://www.animefillerlist.com/shows/$slug"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findBestShowSlug(query: String): String? {
        val shows = getOrFetchFillerShowsList() ?: return null
        val normQuery = normalizeTitleForSearch(query)
        val queryWords = normQuery.split(Regex("\\s+")).filter { it.length > 1 && it !in setOf("the", "and", "season") }
        if (queryWords.isEmpty()) return null

        var bestSlug: String? = null
        var bestScore = 0.0

        for ((slug, name) in shows) {
            val normName = normalizeTitleForSearch(name)
            val nameWords = normName.split(Regex("\\s+")).toSet()
            val common = queryWords.count { nameWords.contains(it) }
            val score = common.toDouble() / queryWords.size.toDouble()

            val firstWordMatch = queryWords.firstOrNull()?.let { nameWords.contains(it) } == true
            val totalScore = score + (if (firstWordMatch) 0.35 else 0.0)

            if (totalScore > bestScore && totalScore >= 0.7) {
                bestScore = totalScore
                bestSlug = slug
            }
        }
        return bestSlug
    }

    private fun getOrFetchFillerShowsList(): List<Pair<String, String>>? {
        animeFillerShowsList?.let { return it }
        val url = "https://www.animefillerlist.com/shows"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val pattern = Pattern.compile("<a href=\"/shows/([^\"]+)\">([^<]+)</a>")
                val matcher = pattern.matcher(body)
                val list = mutableListOf<Pair<String, String>>()
                while (matcher.find()) {
                    val slug = matcher.group(1) ?: continue
                    val name = matcher.group(2) ?: continue
                    list.add(Pair(slug, name))
                }
                animeFillerShowsList = list
                list
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFillerEpisodesFromHtml(html: String): Set<Int> {
        val fillers = mutableSetOf<Int>()

        // 1. Try parsing from the summary: <div class="filler">...<span class="Episodes">(...)</span></div>
        val summaryMatcher = Pattern.compile("<div class=\"filler\">.*?<span class=\"Episodes\">(.*?)</span>", Pattern.DOTALL).matcher(html)
        if (summaryMatcher.find()) {
            val rawEpisodes = summaryMatcher.group(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
            for (token in rawEpisodes.split(",")) {
                val part = token.trim()
                if (part.contains("-")) {
                    val bounds = part.split("-")
                    val start = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                    val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                    if (start != null && end != null && start <= end) {
                        for (i in start..end) fillers.add(i)
                    }
                } else {
                    part.toIntOrNull()?.let { fillers.add(it) }
                }
            }
        }

        // 2. Also parse table rows: <tr class="...filler..." id="eps-XX">...<td class="Number">XX</td>
        val rowMatcher = Pattern.compile("<tr class=\"([^\"]*filler[^\"]*)\"[^>]*id=\"eps-(\\d+)\"").matcher(html)
        while (rowMatcher.find()) {
            val cls = rowMatcher.group(1) ?: ""
            val epNum = rowMatcher.group(2)?.toIntOrNull() ?: continue
            val classes = cls.split(Regex("\\s+"))
            if (classes.contains("filler")) {
                fillers.add(epNum)
            }
        }

        return fillers
    }
}
