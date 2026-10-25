package dev.metro.anime.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import dev.metro.anime.data.model.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object JimakuClient {
    private const val TAG = "JimakuClient"
    private const val BASE_URL = "https://jimaku.cc/api"
    private const val API_KEY = "AAAAAAAAR8MuAS5CA5UeHiRWBcF8Dr9puYkp5266IUj46nmstrbW5XFQtg"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun fetchEpisodeSubtitles(
        anilistId: Long?,
        title: String?,
        episodeOrdinal: Int,
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        val entryId = findEntryId(anilistId, title) ?: return@withContext emptyList()
        val filesUrl = "$BASE_URL/entries/$entryId/files"
        val request = Request.Builder()
            .url(filesUrl)
            .header("Authorization", API_KEY)
            .header("User-Agent", "MetroAnime/1.0")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val array = gson.fromJson(body, JsonArray::class.java) ?: return@withContext emptyList()
                val result = mutableListOf<SubtitleTrack>()

                val epPad = String.format("%02d", episodeOrdinal)
                val epPatterns = listOf(
                    Pattern.compile("(?i)(?:e|ep|episode)[\\s._-]*0*$episodeOrdinal\\b"),
                    Pattern.compile("(?i)[\\s._-]0*$episodeOrdinal\\b"),
                    Pattern.compile("(?i)\\b$epPad\\b"),
                    Pattern.compile("(?i)-\\s*0*$episodeOrdinal\\s*\\."),
                )

                for (elem in array) {
                    val obj = elem.asJsonObject
                    val name = obj.get("name")?.asString ?: continue
                    val url = obj.get("url")?.asString ?: continue
                    if (!name.endsWith(".srt", ignoreCase = true) &&
                        !name.endsWith(".vtt", ignoreCase = true) &&
                        !name.endsWith(".ass", ignoreCase = true)
                    ) {
                        continue
                    }
                    val matches = epPatterns.any { it.matcher(name).find() }
                    if (matches) {
                        result.add(
                            SubtitleTrack(
                                id = "jimaku_$name",
                                label = "Japanese (Jimaku)",
                                url = url,
                                language = "ja",
                                isDefault = false,
                            )
                        )
                    }
                }
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Jimaku subtitles", e)
            emptyList()
        }
    }

    private fun findEntryId(anilistId: Long?, title: String?): Long? {
        val searchUrl = if (anilistId != null && anilistId > 0) {
            "$BASE_URL/entries/search?anilist_id=$anilistId"
        } else if (!title.isNullOrBlank()) {
            "$BASE_URL/entries/search?query=${URLEncoder.encode(title, "UTF-8")}"
        } else return null

        val request = Request.Builder()
            .url(searchUrl)
            .header("Authorization", API_KEY)
            .header("User-Agent", "MetroAnime/1.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val arr = gson.fromJson(body, JsonArray::class.java) ?: return null
                if (arr.size() == 0) return null
                arr[0].asJsonObject.get("id")?.asLong
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finding Jimaku entry", e)
            null
        }
    }
}
