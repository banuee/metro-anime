package dev.metro.anime.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Downloads an HLS (m3u8) episode, concatenates its MPEG-TS segments and
 * remuxes the result into a standalone MP4 saved to the public Movies folder,
 * so it is visible to the gallery and other apps (CapCut, etc.).
 */
object HlsDownloader {
    private const val TAG = "HlsDownloader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Result(val fileUri: String, val sizeBytes: Long)

    suspend fun download(
        context: Context,
        playlistUrl: String,
        displayName: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        val workDir = File(context.cacheDir, "hls_downloads").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "_").take(80)
        val tsFile = File(workDir, "$safeName.ts")
        val mp4File = File(workDir, "$safeName.mp4")
        try {
            val segmentUrls = resolveSegmentUrls(playlistUrl)
            ctx.ensureActive()
            if (segmentUrls.isEmpty()) throw IllegalStateException("Пустой плейлист")
            downloadSegments(segmentUrls, tsFile, onProgress) { ctx.ensureActive() }
            remuxToMp4(tsFile, mp4File, videoOnly = false)
            ctx.ensureActive()
            saveToMediaStore(context, mp4File, safeName)
        } finally {
            tsFile.delete()
            mp4File.delete()
        }
    }

    private fun resolveSegmentUrls(url: String): List<String> {
        var current = url
        var guard = 0
        while (guard++ < 5) {
            val lines = fetchText(current)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            val variantHeader = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            if (variantHeader >= 0) {
                val variantUri = lines.drop(variantHeader + 1).firstOrNull { !it.startsWith("#") }
                    ?: throw IllegalStateException("Не найден вариант потока")
                current = resolveUrl(current, variantUri)
                continue
            }
            return lines.filter { !it.startsWith("#") }.map { resolveUrl(current, it) }
        }
        return emptyList()
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return base.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: relative
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MetroAnime/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string() ?: throw IllegalStateException("Пустой ответ")
        }
    }

    private fun downloadSegments(
        urls: List<String>,
        out: File,
        onProgress: (Long, Long) -> Unit,
        checkActive: () -> Unit,
    ) {
        val total = urls.size.toLong()
        FileOutputStream(out).use { fos ->
            urls.forEachIndexed { index, segmentUrl ->
                checkActive()
                val request = Request.Builder()
                    .url(segmentUrl)
                    .header("User-Agent", "MetroAnime/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Сегмент ${index + 1}: HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Пустой сегмент ${index + 1}")
                    body.byteStream().use { input -> input.copyTo(fos, 64 * 1024) }
                }
                onProgress(index + 1L, total)
            }
        }
    }

    private fun remuxToMp4(ts: File, mp4: File, videoOnly: Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ts.absolutePath)

            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                else if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i
            }
            if (videoTrack < 0 && audioTrack < 0) throw IllegalStateException("Дорожки не найдены")

            val trackMap = mutableMapOf<Int, Int>()
            val muxer = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (videoTrack >= 0) trackMap[videoTrack] = muxer.addTrack(extractor.getTrackFormat(videoTrack))
            if (!videoOnly && audioTrack >= 0) trackMap[audioTrack] = muxer.addTrack(extractor.getTrackFormat(audioTrack))

            for (i in 0 until extractor.trackCount) extractor.unselectTrack(i)
            trackMap.keys.forEach { extractor.selectTrack(it) }

            muxer.start()
            try {
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val track = extractor.sampleTrackIndex
                    if (track < 0) break
                    val muxTrack = trackMap[track] ?: run { extractor.advance(); continue }
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(muxTrack, buffer, info)
                    extractor.advance()
                }
            } finally {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer stop failed: ${e.message}")
                }
                muxer.release()
            }

            if (!mp4.exists() || mp4.length() <= 0) throw IllegalStateException("Ремукс не дал файла")
        } finally {
            extractor.release()
        }
    }

    private fun saveToMediaStore(context: Context, src: File, displayName: String): Result {
        val resolver = context.contentResolver
        val fileName = "$displayName.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MetroAnime")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Не удалось создать файл в галерее")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IllegalStateException("Не удалось открыть поток записи")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Result(uri.toString(), src.length())
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "MetroAnime")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, fileName)
            src.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf("video/mp4"), null)
            return Result(Uri.fromFile(out).toString(), out.length())
        }
    }
}