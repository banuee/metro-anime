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
        val plan = scanTracks(ts, videoOnly)
        if (plan.videoTrack < 0 && plan.audioTrack < 0) throw IllegalStateException("Дорожки не найдены")
        muxTracks(ts, mp4, plan)
    }

    /**
     * Первый проход по TS: считаем количество сэмплов, находим длительность дорожек и
     * количество ADTS-кадров в аудио (MediaExtractor отдаёт по несколько AAC-кадров в одном сэмпле).
     */
    private fun scanTracks(ts: File, videoOnly: Boolean): MuxPlan {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ts.absolutePath)

            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                else if (mime.startsWith("audio/") && audioTrack < 0 && !videoOnly) audioTrack = i
            }
            if (videoTrack < 0 && audioTrack < 0) throw IllegalStateException("Дорожки не найдены")

            // Без selectTrack экстрактор не продвигается по сэмплам и отдаёт одни и те же данные
            for (i in 0 until extractor.trackCount) extractor.unselectTrack(i)
            if (videoTrack >= 0) extractor.selectTrack(videoTrack)
            if (audioTrack >= 0) extractor.selectTrack(audioTrack)

            var videoSamples = 0
            var videoMinPts = Long.MAX_VALUE
            var videoMaxPts = Long.MIN_VALUE
            var audioSamples = 0
            var audioFrames = 0
            var audioMinPts = Long.MAX_VALUE
            var audioMaxPts = Long.MIN_VALUE
            var asc: ByteArray? = null

            val scratch = ByteBuffer.allocate(4 * 1024 * 1024)
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val size = extractor.readSampleData(scratch, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (track == videoTrack) {
                    videoSamples++
                    if (pts < videoMinPts) videoMinPts = pts
                    if (pts > videoMaxPts) videoMaxPts = pts
                } else if (track == audioTrack) {
                    audioSamples++
                    if (pts < audioMinPts) audioMinPts = pts
                    if (pts > audioMaxPts) audioMaxPts = pts
                    val frames = splitAdtsFrames(scratch, size)
                    audioFrames += frames.size
                    if (asc == null && frames.isNotEmpty()) {
                        val header = ByteArray(frames.first().headerLen)
                        val dup = scratch.duplicate()
                        dup.position(frames.first().offset)
                        dup.limit(frames.first().offset + frames.first().headerLen)
                        dup.get(header, 0, header.size)
                        asc = buildAsc(header)
                    }
                }
                extractor.advance()
            }

            val videoDurationUs = if (videoSamples > 1) (videoMaxPts - videoMinPts).coerceAtLeast(1L) else 0L
            val videoFrameUs = if (videoSamples > 1) videoDurationUs / (videoSamples - 1) else 0L
            val audioDurationUs = if (audioSamples > 1) (audioMaxPts - audioMinPts).coerceAtLeast(1L) else 0L
            val audioFrameUs = if (audioFrames > 0) {
                val raw = audioDurationUs / audioFrames
                raw.coerceAtLeast(1000L)
            } else 0L

            return MuxPlan(
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                videoSamples = videoSamples,
                videoFrameUs = videoFrameUs,
                audioFrameUs = audioFrameUs,
                audioFrames = audioFrames,
                asc = asc,
            ).also {
                Log.d(
                    TAG,
                    "remux plan: video=${it.videoSamples} samples frame=${it.videoFrameUs}us " +
                        "audio=${it.audioFrames} adts frames frame=${it.audioFrameUs}us"
                )
            }
        } finally {
            extractor.release()
        }
    }

    private class MuxPlan(
        val videoTrack: Int,
        val audioTrack: Int,
        val videoSamples: Int,
        val videoFrameUs: Long,
        val audioFrameUs: Long,
        val audioFrames: Int,
        val asc: ByteArray?,
    )

    /**
     * Второй проход: пишем в MP4 сэмплы с равномерным CFR-таймлайном.
     * Исходные PTS из MPEG-TS немонотонны (B-кадры), из-за чего MediaMuxer пишет
     * сломанные stts/ctts и файл не играет в ExoPlayer. Аудио-семплы дополнительно
     * делим на отдельные ADTS-кадры, иначе декодер съедает только первый кадр.
     */
    private fun muxTracks(ts: File, mp4: File, plan: MuxPlan) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ts.absolutePath)

            val muxer = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxVideo = -1
            var muxAudio = -1
            if (plan.videoTrack >= 0) {
                muxVideo = muxer.addTrack(extractor.getTrackFormat(plan.videoTrack))
            }
            if (plan.audioTrack >= 0) {
                val audioFormat = extractor.getTrackFormat(plan.audioTrack)
                // Без csd-0 (AudioSpecificConfig) декодер AAC не сможет ничего распаковать
                if (plan.asc != null && !audioFormat.containsKey("csd-0")) {
                    audioFormat.setByteBuffer("csd-0", ByteBuffer.wrap(plan.asc))
                }
                muxAudio = muxer.addTrack(audioFormat)
            }

            for (i in 0 until extractor.trackCount) extractor.unselectTrack(i)
            if (plan.videoTrack >= 0) extractor.selectTrack(plan.videoTrack)
            if (plan.audioTrack >= 0) extractor.selectTrack(plan.audioTrack)

            muxer.start()
            try {
                val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                var videoIndex = 0L
                var audioIndex = 0L

                while (true) {
                    val track = extractor.sampleTrackIndex
                    if (track < 0) break
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    val originalPts = extractor.sampleTime

                    if (track == plan.videoTrack && muxVideo >= 0) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = videoIndex * plan.videoFrameUs
                        info.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(muxVideo, buffer, info)
                        videoIndex++
                    } else if (track == plan.audioTrack && muxAudio >= 0) {
                        val frames = splitAdtsFrames(buffer, size)
                        if (frames.isEmpty()) {
                            info.offset = 0
                            info.size = size
                            info.presentationTimeUs = originalPts
                            info.flags = 0
                            muxer.writeSampleData(muxAudio, buffer, info)
                        } else {
                            for (frame in frames) {
                                // В MP4 AAC пишется без ADTS-заголовка
                                info.offset = frame.offset + frame.headerLen
                                info.size = frame.size - frame.headerLen
                                info.presentationTimeUs = audioIndex * plan.audioFrameUs
                                info.flags = 0
                                muxer.writeSampleData(muxAudio, buffer, info)
                                audioIndex++
                            }
                        }
                    }
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

    private data class AdtsFrame(val offset: Int, val size: Int, val headerLen: Int)

    /**
     * Разбивает ADTS-буфер на отдельные AAC-кадры (в сэмпле их может быть несколько).
     * В MP4 AAC хранится без ADTS-заголовков, поэтому возвращаем и длину заголовка,
     * и смещение полезной нагрузки.
     */
    private fun splitAdtsFrames(buffer: ByteBuffer, size: Int): List<AdtsFrame> {
        if (size < 7) return emptyList()
        val data = ByteArray(size)
        val dup = buffer.duplicate()
        dup.position(0)
        dup.limit(size)
        dup.get(data, 0, size)

        val frames = mutableListOf<AdtsFrame>()
        var pos = 0
        while (pos + 7 <= size) {
            if ((data[pos].toInt() and 0xFF) != 0xFF || (data[pos + 1].toInt() and 0xF0) != 0xF0) break
            val frameLen = ((data[pos + 3].toInt() and 0x03) shl 11) or
                    ((data[pos + 4].toInt() and 0xFF) shl 3) or
                    ((data[pos + 5].toInt() and 0xE0) shr 5)
            val headerLen = if ((data[pos + 1].toInt() and 0x01) == 1) 9 else 7
            if (frameLen <= headerLen || pos + frameLen > size) break
            frames.add(AdtsFrame(pos, frameLen, headerLen))
            pos += frameLen
        }
        return frames
    }

    private fun countAdtsFrames(buffer: ByteBuffer, size: Int): Int = splitAdtsFrames(buffer, size).size

    /**
     * Строит AudioSpecificConfig (2 байта) из ADTS-заголовка — без него декодер AAC
     * в MP4 не может быть сконфигурирован и не выдаёт ни одного фрейма.
     */
    private fun buildAsc(header: ByteArray): ByteArray? {
        if (header.size < 7) return null
        val freqIndex = ((header[2].toInt() and 0x3C) shr 2)
        val channelConfig = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() and 0xC0) shr 6)
        val profileField = ((header[2].toInt() and 0xC0) shr 6)
        if (freqIndex > 12 || channelConfig == 0) return null
        // Поддерживаем только AAC-LC (в ADTS profile field = 1) — для остальных нужен csd-0 экстрактора
        if (profileField != 1) return null
        val objectType = 2
        val b0 = (objectType shl 3) or (freqIndex shr 1)
        val b1 = ((freqIndex and 1) shl 7) or (channelConfig shl 3)
        return byteArrayOf(b0.toByte(), b1.toByte())
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