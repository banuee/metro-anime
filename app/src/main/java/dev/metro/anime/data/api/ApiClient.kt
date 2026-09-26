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
        val url = "https://shikimori.one/api/animes?search=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=1"
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

    suspend fun searchKodikTitles(query: String, limit: Int = 20): List<AnimeTitle> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("token", KODIK_TOKEN)
            .add("title", query)
            .add("limit", limit.toString())
            .add("types", "anime,anime-serial")

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
                    val titleRu = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val titleOrig = obj.get("title_orig")?.takeIf { !it.isJsonNull }?.asString
                    val cleanKey = titleRu.lowercase().trim()
                    if (seenTitles.contains(cleanKey)) continue
                    seenTitles.add(cleanKey)

                    val shikiId = obj.get("shikimori_id")?.takeIf { !it.isJsonNull }?.asLong
                    val year = obj.get("year")?.takeIf { !it.isJsonNull }?.asInt
                    val type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString
                    val screens = obj.getAsJsonArray("screenshots")
                    val poster = if (screens != null && screens.size() > 0) {
                        screens[0].asString
                    } else ""

                    list.add(
                        AnimeTitle(
                            id = "kodik_${obj.get("id")?.asString ?: titleRu}",
                            titleRu = titleRu,
                            titleOrig = titleOrig,
                            posterUrl = poster,
                            year = year,
                            type = type,
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

                val dubGroups = mutableListOf<DubbingGroup>()
                for (res in results) {
                    val resObj = res.asJsonObject
                    val transObj = resObj.getAsJsonObject("translation") ?: continue
                    val transId = transObj.get("id")?.asString ?: "0"
                    val transTitle = transObj.get("title")?.asString ?: "Озвучка"

                    val seasonsObj = resObj.getAsJsonObject("seasons")
                    val episodesList = mutableListOf<AnimeEpisode>()

                    if (seasonsObj != null) {
                        for (seasonKey in seasonsObj.keySet()) {
                            val seasonData = seasonsObj.getAsJsonObject(seasonKey)
                            val epObj = seasonData?.getAsJsonObject("episodes")
                            if (epObj != null) {
                                for (epKey in epObj.keySet()) {
                                    val epUrl = epObj.get(epKey)?.asString ?: continue
                                    val fullUrl = if (epUrl.startsWith("//")) "https:$epUrl" else epUrl
                                    val ord = epKey.toIntOrNull() ?: 1
                                    episodesList.add(
                                        AnimeEpisode(
                                            ordinal = ord,
                                            name = "Серия $ord",
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
                        dubGroups.add(
                            DubbingGroup(
                                id = "kodik_$transId",
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
}
