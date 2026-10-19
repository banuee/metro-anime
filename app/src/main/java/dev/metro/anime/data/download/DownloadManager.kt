package dev.metro.anime.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.model.DownloadStatus
import dev.metro.anime.data.model.DownloadTask
import dev.metro.anime.data.model.DownloadedEpisode
import dev.metro.anime.data.model.isActive
import dev.metro.anime.data.model.sanitized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val PREFS = "metro_anime_downloads"
    private const val KEY_DOWNLOADS = "downloads"

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    @Volatile
    private var initialized = false

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadedEpisode>>(emptyList())
    val downloads: StateFlow<List<DownloadedEpisode>> = _downloads.asStateFlow()

    private var worker: Job? = null
    private val jobs = ConcurrentHashMap<String, Job>()

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        loadDownloads()
    }

    fun isInitialized(): Boolean = initialized

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadDownloads() {
        val json = prefs().getString(KEY_DOWNLOADS, null) ?: return
        try {
            val type = object : TypeToken<List<DownloadedEpisode>>() {}.type
            val list: List<DownloadedEpisode> = gson.fromJson(json, type) ?: emptyList()
            _downloads.value = list.map { it.copy(anime = it.anime.sanitized()) }
        } catch (_: Exception) {}
    }

    private fun persistDownloads() {
        try {
            prefs().edit().putString(KEY_DOWNLOADS, gson.toJson(_downloads.value)).apply()
        } catch (_: Exception) {}
    }

    private fun taskId(animeId: String, ordinal: Int): String = "${animeId}__$ordinal"

    fun isDownloaded(animeId: String, ordinal: Int): Boolean =
        _downloads.value.any { it.anime.id == animeId && it.episodeOrdinal == ordinal }

    fun getDownloaded(animeId: String, ordinal: Int): DownloadedEpisode? =
        _downloads.value.firstOrNull { it.anime.id == animeId && it.episodeOrdinal == ordinal }

    fun downloadedFor(animeId: String): List<DownloadedEpisode> =
        _downloads.value.filter { it.anime.id == animeId }.sortedBy { it.episodeOrdinal }

    fun taskFor(animeId: String, ordinal: Int): DownloadTask? {
        val id = taskId(animeId, ordinal)
        return _tasks.value.firstOrNull { it.id == id }
    }

    /**
     * Ставит серию в очередь на скачивание.
     * @return true, если задача принята; false, если уже скачана или уже в очереди.
     */
    fun enqueue(
        anime: AnimeTitle,
        episode: AnimeEpisode,
        dubbingTitle: String,
        source: AnimeSource,
        quality: String,
        streamUrl: String,
        durationSec: Int?,
    ): Boolean {
        if (!initialized || streamUrl.isBlank()) return false
        val id = taskId(anime.id, episode.ordinal)
        if (isDownloaded(anime.id, episode.ordinal)) return false
        if (_tasks.value.any { it.id == id && it.status.isActive }) return false

        val task = DownloadTask(
            id = id,
            anime = anime.sanitized(),
            episodeOrdinal = episode.ordinal,
            episodeName = episode.name,
            dubbingTitle = dubbingTitle,
            source = source,
            quality = quality,
            streamUrl = streamUrl,
            durationSec = durationSec ?: episode.durationSec,
            status = DownloadStatus.QUEUED,
        )
        _tasks.value = _tasks.value.filterNot { it.id == id } + task
        DownloadService.start(appContext)
        ensureWorker()
        return true
    }

    fun cancel(animeId: String, ordinal: Int) {
        val id = taskId(animeId, ordinal)
        // Ставим статус ДО отмены job: иначе process() успеет перезаписать его на COMPLETED.
        _tasks.value = _tasks.value.map {
            if (it.id == id) it.copy(status = DownloadStatus.CANCELLED) else it
        }
        jobs.remove(id)?.cancel()
    }

    fun clearFinishedTasks() {
        _tasks.value = _tasks.value.filter { it.status.isActive }
    }

    fun removeDownload(animeId: String, ordinal: Int) {
        val record = getDownloaded(animeId, ordinal) ?: return
        try {
            val uri = Uri.parse(record.fileUri)
            if (uri.scheme == "content") {
                appContext.contentResolver.delete(uri, null, null)
            } else {
                uri.path?.let { File(it).delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось удалить файл: ${e.message}")
        }
        _downloads.value = _downloads.value.filterNot {
            it.anime.id == animeId && it.episodeOrdinal == ordinal
        }
        persistDownloads()
    }

    fun removeAnimeDownloads(animeId: String) {
        val records = downloadedFor(animeId)
        records.forEach { removeDownload(animeId, it.episodeOrdinal) }
    }

    /**
     * Уточняет название и постер у уже скачанных серий (например, по данным Shikimori):
     * в записи могло сохраниться обрезанное название из поисковой выдачи.
     */
    fun updateAnimeMeta(animeId: String, title: AnimeTitle) {
        var changed = false
        _downloads.value = _downloads.value.map { record ->
            if (record.anime.id != animeId) {
                record
            } else {
                val merged = record.anime.copy(
                    titleRu = title.titleRu.ifBlank { record.anime.titleRu },
                    posterUrl = title.posterUrl.ifBlank { record.anime.posterUrl },
                    shikimoriId = title.shikimoriId ?: record.anime.shikimoriId,
                )
                // AnimeTitle.equals() сравнивает только id, поэтому сравниваем поля явно —
                // иначе изменения никогда не фиксировались и терялись после перезапуска.
                if (merged.titleRu != record.anime.titleRu ||
                    merged.posterUrl != record.anime.posterUrl ||
                    merged.shikimoriId != record.anime.shikimoriId
                ) {
                    changed = true
                }
                record.copy(anime = merged)
            }
        }
        if (changed) persistDownloads()
    }

    /**
     * Воркер очереди скачиваний.
     *
     * Важно: очередь проверяется ПОСЛЕ выхода из цикла ещё раз. Иначе гонка —
     * задача, добавленная в момент, когда воркер уже решил завершиться, осталась бы
     * в статусе QUEUED навсегда (никто бы её не взял).
     */
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _tasks.value.firstOrNull { it.status == DownloadStatus.QUEUED } ?: break
                // Job регистрируем ДО запуска обработки: иначе отмена попадёт в окно
                // между launch и записью в map, и загрузка продолжится, несмотря на отмену.
                val job = scope.launch { process(next.id) }
                jobs[next.id] = job
                job.join()
                jobs.remove(next.id)
            }
            // Страховка от гонки: если за время завершения воркера добавилась новая задача —
            // подхватываем её (ensureWorker() ниже запустит новый воркер).
            if (_tasks.value.any { it.status == DownloadStatus.QUEUED }) {
                ensureWorker()
                return@launch
            }
            DownloadService.stop(appContext)
            // Завершённые и упавшие задачи не нужны — убираем, чтобы список не разрастался
            clearFinishedTasks()
        }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    private suspend fun process(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        updateTask(id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = 0f, error = null, downloadedBytes = 0L, totalBytes = 0L) }

        try {
            val result = HlsDownloader.download(
                context = appContext,
                playlistUrl = task.streamUrl,
                displayName = buildFileName(task),
                onProgress = { p ->
                    updateTask(id) {
                        it.copy(
                            downloadedBytes = p.downloadedBytes,
                            totalBytes = p.downloadedBytes,
                            progress = p.fraction,
                        )
                    }
                },
                onStage = { stage ->
                    updateTask(id) {
                        when (stage) {
                            HlsDownloader.Stage.DOWNLOADING -> it.copy(status = DownloadStatus.DOWNLOADING)
                            HlsDownloader.Stage.REMUXING -> it.copy(status = DownloadStatus.REMUXING, progress = 1f)
                            HlsDownloader.Stage.SAVING -> it.copy(status = DownloadStatus.REMUXING, progress = 1f)
                        }
                    }
                },
            )

            val record = DownloadedEpisode(
                anime = task.anime,
                episodeOrdinal = task.episodeOrdinal,
                episodeName = task.episodeName,
                dubbingTitle = task.dubbingTitle,
                source = task.source,
                quality = task.quality,
                fileUri = result.fileUri,
                sizeBytes = result.sizeBytes,
                durationSec = task.durationSec,
            )
            _downloads.value = _downloads.value
                .filterNot { it.anime.id == record.anime.id && it.episodeOrdinal == record.episodeOrdinal } + record
            persistDownloads()
            updateTask(id) { it.copy(status = DownloadStatus.COMPLETED, progress = 1f) }
        } catch (e: CancellationException) {
            updateTask(id) { it.copy(status = DownloadStatus.CANCELLED) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка загрузки ${task.anime.titleRu} s${task.episodeOrdinal}: ${e.message}")
            updateTask(id) { it.copy(status = DownloadStatus.FAILED, error = e.localizedMessage ?: "Ошибка загрузки") }
        }
    }

    private fun buildFileName(task: DownloadTask): String {
        val ep = task.episodeOrdinal.toString().padStart(2, '0')
        return "${task.anime.titleRu} - S$ep (${task.quality}p)"
    }
}