package dev.metro.anime.data.api

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.SkipTimestamps
import dev.metro.anime.data.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class HiAnimeResolvedStream(
    val streamUrl: String,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val opening: SkipTimestamps? = null,
    val ending: SkipTimestamps? = null,
)

object HiAnimeClient {
    private const val TAG = "HiAnimeClient"
    private const val BASE_URL = "https://hianime.at"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val MEGACDN_KEY_STR = "i?LMTAx0Q6,:}50U"
    private const val MEGACDN_IV_STR = "W0;27ToaUpl_P%'c"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Search anime on HiAnime by title.
     */
    suspend fun searchHiAnime(query: String): List<AnimeTitle> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "$BASE_URL/search?keyword=${URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val html = response.body?.string().orEmpty()
                parseSearchHtml(html)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching HiAnime for $query", e)
            emptyList()
        }
    }

    private fun parseSearchHtml(html: String): List<AnimeTitle> {
        val list = mutableListOf<AnimeTitle>()
        // Match each item in search results:
        // <h3 class="film-name"><a href="/my-hero-academia-1395" title="My Hero Academia"
        val itemPattern = Pattern.compile(
            "<h3 class=\"film-name\">\\s*<a\\s+href=\"([^\"]+)\"\\s+title=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
        )
        val posterPattern = Pattern.compile("data-src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)

        val matcher = itemPattern.matcher(html)
        val seenIds = mutableSetOf<String>()

        while (matcher.find()) {
            val href = matcher.group(1)?.trim() ?: continue
            val rawTitle = matcher.group(2)?.trim() ?: continue
            val slugWithId = href.removePrefix("https://hianime.at").removePrefix("/").removePrefix("watch/")
            val cleanId = slugWithId.substringBefore("?")
            if (cleanId.isBlank() || seenIds.contains(cleanId)) continue
            seenIds.add(cleanId)

            // Look backward in the segment for poster
            val segStart = (matcher.start() - 600).coerceAtLeast(0)
            val seg = html.substring(segStart, matcher.start())
            val posterMatcher = posterPattern.matcher(seg)
            var posterUrl = ""
            while (posterMatcher.find()) {
                posterUrl = posterMatcher.group(1).orEmpty()
            }

            list.add(
                AnimeTitle(
                    id = "hianime_$cleanId",
                    titleRu = rawTitle,
                    titleOrig = rawTitle,
                    posterUrl = posterUrl,
                    source = AnimeSource.HIANIME,
                )
            )
        }
        return list
    }

    /**
     * Get episode list for an anime id (e.g. "my-hero-academia-1395").
     */
    suspend fun getEpisodes(animeId: String): List<AnimeEpisode> = withContext(Dispatchers.IO) {
        val cleanId = animeId.removePrefix("hianime_")
        val numericId = cleanId.substringAfterLast("-")
        val url = "$BASE_URL/api/theme/episode/list/$numericId"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/watch/$cleanId")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                val json = gson.fromJson(body, JsonObject::class.java) ?: return@withContext emptyList()
                val html = json.get("html")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                parseEpisodesHtml(html, cleanId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching HiAnime episodes for $animeId", e)
            emptyList()
        }
    }

    private fun parseEpisodesHtml(html: String, animeId: String): List<AnimeEpisode> {
        val list = mutableListOf<AnimeEpisode>()
        // Match: <a title="Izuku Midoriya: Origin" class="ssl-item ep-item" data-number="1" data-id="24557"
        val epPattern = Pattern.compile(
            "<a[^>]+class=[\"'][^\"']*ep-item[^\"']*[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE
        )
        val idPattern = Pattern.compile("data-id=[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE)
        val numPattern = Pattern.compile("data-number=[\"'](\\d+)[\"']", Pattern.CASE_INSENSITIVE)
        val titlePattern = Pattern.compile("title=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE)

        val matcher = epPattern.matcher(html)
        while (matcher.find()) {
            val tag = matcher.group(0) ?: continue
            val idM = idPattern.matcher(tag)
            val numM = numPattern.matcher(tag)
            val titleM = titlePattern.matcher(tag)

            val epId = if (idM.find()) idM.group(1) else null
            val epNum = if (numM.find()) numM.group(1)?.toIntOrNull() else null
            val epTitle = if (titleM.find()) titleM.group(1)?.trim() else null

            if (epId != null && epNum != null) {
                list.add(
                    AnimeEpisode(
                        ordinal = epNum,
                        name = if (!epTitle.isNullOrBlank()) epTitle else "Episode $epNum",
                        hlsStreams = mapOf(
                            "hianime_ep_id" to epId,
                            "hianime_anime_id" to animeId,
                        ),
                    )
                )
            }
        }
        return list.sortedBy { it.ordinal }
    }

    /**
     * Resolves the stream (.m3u8) and subtitles (.vtt) for an episode.
     */
    suspend fun resolveStream(
        episodeId: String,
        isDub: Boolean,
    ): HiAnimeResolvedStream? = withContext(Dispatchers.IO) {
        val serversUrl = "$BASE_URL/api/theme/episode/servers?episodeId=$episodeId"
        val request = Request.Builder()
            .url(serversUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/watch/")
            .build()

        val embedUrl: String
        try {
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val json = gson.fromJson(body, JsonObject::class.java)
                json?.get("html")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            }
            embedUrl = extractEmbedUrl(html, isDub) ?: return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching HiAnime servers for ep $episodeId", e)
            return@withContext null
        }

        resolveMegaplayStream(embedUrl)
    }

    private fun extractEmbedUrl(html: String, isDub: Boolean): String? {
        val targetCategory = if (isDub) "dub" else "sub"
        val serverItemPattern = Pattern.compile(
            "<div[^>]+class=[\"'][^\"']*server-item[^\"']*[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE
        )
        val typePattern = Pattern.compile("data-type=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val hashPattern = Pattern.compile("data-hash=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val namePattern = Pattern.compile("data-server-name=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)

        val matcher = serverItemPattern.matcher(html)
        var fallbackHash: String? = null
        var bestHash: String? = null

        while (matcher.find()) {
            val tag = matcher.group(0) ?: continue
            val typeM = typePattern.matcher(tag)
            val hashM = hashPattern.matcher(tag)
            val nameM = namePattern.matcher(tag)

            val sType = if (typeM.find()) typeM.group(1)?.lowercase() else null
            val sHash = if (hashM.find()) hashM.group(1) else null
            val sName = if (nameM.find()) nameM.group(1)?.lowercase().orEmpty() else ""

            if (sType == targetCategory && !sHash.isNullOrBlank()) {
                if (fallbackHash == null) fallbackHash = sHash
                if (sName.contains("hd-1") || sName.contains("megacloud") || sName.contains("megaplay")) {
                    bestHash = sHash
                    break
                }
            }
        }

        val chosenHash = bestHash ?: fallbackHash ?: return null
        return try {
            val decoded = String(Base64.decode(chosenHash, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("http")) decoded else null
        } catch (e: Exception) {
            Log.w(TAG, "Error decoding server hash", e)
            null
        }
    }

    /**
     * Resolves the megaplay embed link into playable m3u8 and subtitles.
     */
    private fun resolveMegaplayStream(embedUrl: String): HiAnimeResolvedStream? {
        return try {
            // URL format: https://megaplay.buzz/stream/s-2/6210/dub?s=tcdn
            val uri = android.net.Uri.parse(embedUrl)
            val id = uri.pathSegments.getOrNull(2) ?: uri.pathSegments.lastOrNull() ?: return null
            val sParam = uri.getQueryParameter("s") ?: "tcdn"

            val getSourcesUrl = "https://megaplay.buzz/stream/getSources?id=$id&s=$sParam"
            val req = Request.Builder()
                .url(getSourcesUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", embedUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                val json = gson.fromJson(body, JsonObject::class.java) ?: return null

                // Decrypt sources
                val enc = json.get("enc")?.takeIf { !it.isJsonNull }?.asString
                val streamUrl = if (!enc.isNullOrBlank()) {
                    decryptMegaplayEnc(enc)
                } else null

                if (streamUrl.isNullOrBlank()) return null

                // Subtitle tracks
                val subtitles = mutableListOf<SubtitleTrack>()
                val tracks = json.getAsJsonArray("tracks")
                if (tracks != null) {
                    for (t in tracks) {
                        val trackObj = t.asJsonObject
                        val file = trackObj.get("file")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val label = trackObj.get("label")?.takeIf { !it.isJsonNull }?.asString ?: "English"
                        val kind = trackObj.get("kind")?.takeIf { !it.isJsonNull }?.asString
                        if (kind == "thumbnails") continue
                        val isDefault = trackObj.get("default")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        subtitles.add(
                            SubtitleTrack(
                                id = "hianime_${subtitles.size}_$label",
                                label = "$label (HiAnime)",
                                url = file,
                                language = if (label.contains("english", ignoreCase = true)) "en" else "und",
                                isDefault = isDefault || label.contains("english", ignoreCase = true),
                            )
                        )
                    }
                }

                // Intro & outro
                var opening: SkipTimestamps? = null
                val introObj = json.getAsJsonObject("intro")
                val inStart = introObj?.get("start")?.takeIf { !it.isJsonNull }?.asInt
                val inEnd = introObj?.get("end")?.takeIf { !it.isJsonNull }?.asInt
                if (inStart != null && inEnd != null && inEnd > inStart) {
                    opening = SkipTimestamps(inStart, inEnd)
                }

                var ending: SkipTimestamps? = null
                val outroObj = json.getAsJsonObject("outro")
                val outStart = outroObj?.get("start")?.takeIf { !it.isJsonNull }?.asInt
                val outEnd = outroObj?.get("end")?.takeIf { !it.isJsonNull }?.asInt
                if (outStart != null && outEnd != null && outEnd > outStart) {
                    ending = SkipTimestamps(outStart, outEnd)
                }

                HiAnimeResolvedStream(
                    streamUrl = streamUrl,
                    subtitles = subtitles,
                    opening = opening,
                    ending = ending,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving megaplay stream for $embedUrl", e)
            null
        }
    }

    private fun decryptMegaplayEnc(enc: String): String? {
        return try {
            val keyBytes = padBytes(MEGACDN_KEY_STR.toByteArray(StandardCharsets.UTF_8), 32)
            val ivBytes = padBytes(MEGACDN_IV_STR.toByteArray(StandardCharsets.UTF_8), 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decodedBytes = Base64.decode(enc, Base64.URL_SAFE)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val decryptedJson = String(decryptedBytes, StandardCharsets.UTF_8)

            val root = gson.fromJson(decryptedJson, JsonObject::class.java)
            root?.get("file")?.takeIf { !it.isJsonNull }?.asString
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting megaplay enc", e)
            null
        }
    }

    private fun padBytes(input: ByteArray, targetLen: Int): ByteArray {
        val result = ByteArray(targetLen)
        val copyLen = minOf(input.size, targetLen)
        System.arraycopy(input, 0, result, 0, copyLen)
        return result
    }
}
