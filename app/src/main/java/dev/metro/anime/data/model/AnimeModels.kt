package dev.metro.anime.data.model

enum class AnimeSource(val label: String) {
    ANILIBRIA("AniLibria"),
    KODIK("Kodik"),
}

data class AnimeTitle(
    val id: String,
    val titleRu: String,
    val titleOrig: String? = null,
    val posterUrl: String,
    val description: String? = null,
    val year: Int? = null,
    val type: String? = null,
    val season: String? = null,
    val episodesCount: Int? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val shikimoriId: Long? = null,
    val source: AnimeSource = AnimeSource.ANILIBRIA,
)

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
    val dubbingTitle: String,
    val source: AnimeSource,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

