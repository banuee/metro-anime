package dev.metro.anime.data.model

const val OFFLINE_STREAM_KEY = "Офлайн"

enum class AnimeSource(val label: String) {
    ANILIBRIA("AniLibria"),
    KODIK("Kodik"),
}

data class AnimeTitle(
    val id: String = "",
    val titleRu: String = "",
    val titleOrig: String? = null,
    val posterUrl: String = "",
    val description: String? = null,
    val year: Int? = null,
    val type: String? = null,
    val season: String? = null,
    val episodesCount: Int? = null,
    val genres: List<String>? = emptyList(),
    val rating: Double? = null,
    val shikimoriId: Long? = null,
    val isOngoing: Boolean = false,
    val source: AnimeSource? = AnimeSource.ANILIBRIA,
) {
    val safeGenres: List<String>
        get() = genres ?: emptyList()

    val safeSource: AnimeSource
        get() = source ?: AnimeSource.ANILIBRIA

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnimeTitle) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

data class SkipTimestamps(
    val startSec: Int,
    val endSec: Int,
)

data class AnimeEpisode(
    val ordinal: Int,
    val name: String? = null,
    val previewUrl: String? = null,
    val hlsStreams: Map<String, String> = emptyMap(), // "1080" -> url, "720" -> url, "480" -> url
    val durationSec: Int? = null,
    val opening: SkipTimestamps? = null,
    val ending: SkipTimestamps? = null,
    val isFiller: Boolean = false,
)

data class DubbingGroup(
    val id: String,
    val title: String,
    val source: AnimeSource,
    val episodes: List<AnimeEpisode> = emptyList(),
)

data class AnimeDetails(
    val title: AnimeTitle,
    val dubbings: List<DubbingGroup> = emptyList(),
    val rawDescription: String? = null,
)

data class AnimeSchedule(
    val isOngoing: Boolean = false,
    val status: String? = null,          // ongoing / released / anons
    val nextEpisode: Int? = null,
    val nextEpisodeAt: Long? = null,     // epoch millis
    val episodesTotal: Int? = null,
    val episodesAired: Int? = null,
    val broadcast: String? = null,
    val publishDay: Int? = null, // ISO: 1 = Понедельник .. 7 = Воскресенье
) {
    val statusRu: String
        get() = when (status) {
            "ongoing" -> "Онгоинг"
            "anons" -> "Анонс"
            "released" -> "Завершён"
            else -> status ?: "Неизвестно"
        }
}

data class EpisodeSubscription(
    val anime: AnimeTitle,
    val shikimoriId: Long? = null,
    val lastAiredEpisode: Int = 0,
    val nextEpisodeAt: Long? = null,
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    REMUXING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

val DownloadStatus.isActive: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING || this == DownloadStatus.REMUXING

data class DownloadedEpisode(
    val anime: AnimeTitle,
    val episodeOrdinal: Int,
    val episodeName: String? = null,
    val dubbingTitle: String = "",
    val source: AnimeSource = AnimeSource.ANILIBRIA,
    val quality: String = "720",
    val fileUri: String = "",
    val sizeBytes: Long = 0L,
    val durationSec: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val safeSource: AnimeSource
        get() = source ?: AnimeSource.ANILIBRIA
}

data class DownloadTask(
    val id: String,
    val anime: AnimeTitle,
    val episodeOrdinal: Int,
    val episodeName: String? = null,
    val dubbingTitle: String = "",
    val source: AnimeSource = AnimeSource.ANILIBRIA,
    val quality: String = "720",
    val streamUrl: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val durationSec: Int? = null,
) {
    val safeSource: AnimeSource
        get() = source ?: AnimeSource.ANILIBRIA
}

data class WatchProgress(
    val anime: AnimeTitle,
    val episodeOrdinal: Int,
    val episodeName: String? = null,
    val dubbingTitle: String = "",
    val source: AnimeSource? = AnimeSource.ANILIBRIA,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val safeSource: AnimeSource
        get() = source ?: anime.safeSource
}

fun AnimeTitle.sanitized(): AnimeTitle = copy(
    id = (id as String?).orEmpty(),
    titleRu = (titleRu as String?).orEmpty(),
    posterUrl = (posterUrl as String?).orEmpty(),
    genres = genres ?: emptyList(),
    source = source ?: AnimeSource.ANILIBRIA,
)

/** «720» -> «720p», «Офлайн» -> «Офлайн» */
fun qualityLabel(quality: String): String =
    if (quality.toIntOrNull() != null) "${quality}p" else quality

/** «1536» -> «1,5 МБ» — форматирование размера файла для UI. */
fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0) return "0 МБ"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f ГБ", gb).replace('.', ',')
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.0f МБ", mb)
        else -> String.format(java.util.Locale.US, "%.0f КБ", kb)
    }
}

fun WatchProgress.sanitized(): WatchProgress = copy(
    anime = anime.sanitized(),
    dubbingTitle = (dubbingTitle as String?).orEmpty(),
    source = source ?: anime.safeSource,
)

