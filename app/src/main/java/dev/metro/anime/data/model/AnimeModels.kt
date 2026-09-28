package dev.metro.anime.data.model

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WatchProgress) return false
        return anime.id == other.anime.id && episodeOrdinal == other.episodeOrdinal
    }

    override fun hashCode(): Int {
        var result = anime.id.hashCode()
        result = 31 * result + episodeOrdinal
        return result
    }
}

fun AnimeTitle.sanitized(): AnimeTitle = copy(
    id = (id as String?).orEmpty(),
    titleRu = (titleRu as String?).orEmpty(),
    posterUrl = (posterUrl as String?).orEmpty(),
    genres = genres ?: emptyList(),
    source = source ?: AnimeSource.ANILIBRIA,
)

fun WatchProgress.sanitized(): WatchProgress = copy(
    anime = anime.sanitized(),
    dubbingTitle = (dubbingTitle as String?).orEmpty(),
    source = source ?: anime.safeSource,
)

