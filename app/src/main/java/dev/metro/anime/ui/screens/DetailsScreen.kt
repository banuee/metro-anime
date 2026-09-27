package dev.metro.anime.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.metro.anime.data.model.*
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.ui.components.MetroChip
import dev.metro.anime.ui.components.MetroIconButton
import dev.metro.anime.ui.components.MetroTopBar
import dev.metro.anime.ui.theme.FrostedGlassBox
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(
    anime: AnimeTitle,
    repository: AnimeRepository,
    onBackClick: () -> Unit,
    onPlayEpisode: (AnimeEpisode, AnimeSource, AnimeTitle, String, Long, List<AnimeEpisode>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val scope = rememberCoroutineScope()

    var details by remember { mutableStateOf<AnimeDetails?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedDubIndex by remember { mutableIntStateOf(0) }
    var isAscending by remember { mutableStateOf(true) }
    var selectedRangeIndex by remember { mutableIntStateOf(0) }
    val bookmarks by repository.bookmarks.collectAsState()
    val isBookmarked = remember(bookmarks, anime.id) {
        bookmarks.any { it.id == anime.id }
    }
    val watchProgress = remember(anime.id) {
        repository.getProgress(anime.id)
    }

    LaunchedEffect(anime.id) {
        isLoading = true
        val loaded = repository.getAnimeDetails(anime.id, anime.shikimoriId, anime.titleRu)
        details = loaded
        // Match active dubbing to previously watched if present
        val saved = repository.getProgress(anime.id)
        if (saved != null && loaded != null) {
            val idx = loaded.dubbings.indexOfFirst { it.title.equals(saved.dubbingTitle, ignoreCase = true) }
            if (idx >= 0) {
                selectedDubIndex = idx
            }
        }
        isLoading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Bar
        MetroTopBar(
            title = anime.titleRu,
            onBackClick = onBackClick,
            trailingAction = {
                MetroIconButton(
                    icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (isBookmarked) "Удалить из избранного" else "В избранное",
                    tint = if (isBookmarked) scheme.accent else scheme.text,
                    onClick = { repository.toggleBookmark(anime) },
                )
            },
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = scheme.accent, strokeWidth = 2.dp)
            }
        } else {
            val loadedDetails = details
            val currentDubs = loadedDetails?.dubbings.orEmpty()
            val activeDub = currentDubs.getOrNull(selectedDubIndex) ?: currentDubs.firstOrNull()
            val episodes = activeDub?.episodes.orEmpty()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                // Header Banner with Poster
                item {
                    val isSerost = dev.metro.anime.ui.theme.LocalSerostMode.current
                    val detailsPosterData: Any? = if (isSerost) dev.metro.anime.R.drawable.serost_cat else anime.posterUrl

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    ) {
                        AsyncImage(
                            model = detailsPosterData,
                            contentDescription = anime.titleRu,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Gradient fade
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.3f),
                                            Color(0xFF0A0A0D).copy(alpha = 0.85f),
                                            scheme.background,
                                        ),
                                    )
                                )
                        )

                        // Info row at banner bottom
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            // Poster thumbnail
                            Box(
                                modifier = Modifier
                                    .width(96.dp)
                                    .height(136.dp)
                                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                    .border(MetroDimens.strokeWidth, scheme.strokeStrong, RoundedCornerShape(MetroDimens.radiusSmall)),
                            ) {
                                AsyncImage(
                                    model = detailsPosterData,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column {
                                Text(
                                    text = anime.titleRu,
                                    fontFamily = MetroFonts.headline,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    color = scheme.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                if (anime.titleOrig != null) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = anime.titleOrig,
                                        fontFamily = MetroFonts.text,
                                        fontSize = 12.sp,
                                        color = scheme.textDim,
                                        maxLines = 1,
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (anime.rating != null && anime.rating > 0) {
                                        MetroChip(text = "★ ${String.format("%.1f", anime.rating)}")
                                    }
                                    if (anime.year != null) {
                                        MetroChip(text = "${anime.year}")
                                    }
                                    if (anime.season != null) {
                                        MetroChip(text = anime.season)
                                    }
                                }
                            }
                        }
                    }
                }

                // Continue Watching Hero Button (if previously watched)
                if (watchProgress != null) {
                    item {
                        FrostedGlassBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .metroClickable {
                                    val targetEp = episodes.find { it.ordinal == watchProgress.episodeOrdinal }
                                        ?: episodes.firstOrNull()
                                    if (targetEp != null && activeDub != null) {
                                        onPlayEpisode(
                                            targetEp,
                                            activeDub.source,
                                            anime,
                                            activeDub.title,
                                            watchProgress.positionMs,
                                            episodes,
                                        )
                                    }
                                },
                            shape = MetroDimens.radiusSmall,
                            tint = scheme.glassHover,
                            borderColor = scheme.accent.copy(alpha = 0.50f),
                            borderWidth = 1.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                        .background(scheme.accent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Продолжить просмотр",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ПРОДОЛЖИТЬ ПРОСМОТР",
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.sp,
                                        color = scheme.accent,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Серия ${watchProgress.episodeOrdinal} • ${formatMinSec(watchProgress.positionMs)} / ${formatMinSec(watchProgress.durationMs)}",
                                        fontFamily = MetroFonts.text,
                                        fontSize = 13.sp,
                                        color = scheme.text,
                                    )
                                    Text(
                                        text = "Озвучка: ${watchProgress.dubbingTitle}",
                                        fontFamily = MetroFonts.text,
                                        fontSize = 11.sp,
                                        color = scheme.textDim,
                                    )
                                }
                            }
                        }
                    }
                }

                // Genres
                if (anime.genres.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(anime.genres) { genre ->
                                MetroChip(text = genre)
                            }
                        }
                    }
                }

                // Description
                val desc = loadedDetails?.rawDescription ?: anime.description
                if (!desc.isNullOrBlank()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text = "ОПИСАНИЕ",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = scheme.textDim,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = desc.replace(Regex("<[^>]*>"), "").trim(),
                                fontFamily = MetroFonts.text,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = scheme.text,
                            )
                        }
                    }
                }

                // Dubbings selector
                if (currentDubs.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            Text(
                                text = "ОЗВУЧКА",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = scheme.textDim,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(currentDubs.indices.toList()) { idx ->
                                    val dub = currentDubs[idx]
                                    MetroChip(
                                        text = dub.title,
                                        isSelected = idx == selectedDubIndex,
                                        onClick = {
                                            selectedDubIndex = idx
                                            selectedRangeIndex = 0
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Episodes Section
                val sortedEpisodes = if (isAscending) episodes.sortedBy { it.ordinal } else episodes.sortedByDescending { it.ordinal }
                val hasManyEpisodes = episodes.size > 50
                val rangeChunkSize = 50
                val totalRanges = if (hasManyEpisodes) (episodes.size + rangeChunkSize - 1) / rangeChunkSize else 0

                val displayedEpisodes = if (!hasManyEpisodes || selectedRangeIndex == 0) {
                    sortedEpisodes
                } else {
                    val start = (selectedRangeIndex - 1) * rangeChunkSize
                    val end = minOf(start + rangeChunkSize, sortedEpisodes.size)
                    if (start < sortedEpisodes.size) sortedEpisodes.subList(start, end) else sortedEpisodes
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "СЕРИИ (${episodes.size})",
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            letterSpacing = 1.2.sp,
                            color = scheme.text,
                        )

                        // Order toggle button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(scheme.glass)
                                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                                .metroClickable { isAscending = !isAscending }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = if (isAscending) "1 → ${episodes.size}" else "${episodes.size} → 1",
                                fontFamily = MetroFonts.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.accent,
                            )
                        }
                    }

                    if (hasManyEpisodes) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item {
                                MetroChip(
                                    text = "Все",
                                    isSelected = selectedRangeIndex == 0,
                                    onClick = { selectedRangeIndex = 0 },
                                )
                            }
                            items(totalRanges) { rIdx ->
                                val rStart = rIdx * rangeChunkSize + 1
                                val rEnd = minOf((rIdx + 1) * rangeChunkSize, episodes.size)
                                MetroChip(
                                    text = "$rStart–$rEnd",
                                    isSelected = selectedRangeIndex == rIdx + 1,
                                    onClick = { selectedRangeIndex = rIdx + 1 },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (episodes.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Серии для этой озвучки не найдены",
                                fontFamily = MetroFonts.text,
                                color = scheme.textDim,
                                fontSize = 13.sp,
                            )
                        }
                    }
                } else {
                    items(displayedEpisodes, key = { "${it.ordinal}_${it.name ?: ""}" }) { episode ->
                        EpisodeItemRow(
                            episode = episode,
                            watchProgress = watchProgress,
                            onClick = {
                                val startPos = if (watchProgress?.episodeOrdinal == episode.ordinal) {
                                    watchProgress.positionMs
                                } else 0L
                                onPlayEpisode(
                                    episode,
                                    activeDub?.source ?: AnimeSource.ANILIBRIA,
                                    anime,
                                    activeDub?.title ?: "",
                                    startPos,
                                    episodes,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeItemRow(
    episode: AnimeEpisode,
    watchProgress: WatchProgress? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val isCurrentWatched = watchProgress != null && watchProgress.episodeOrdinal == episode.ordinal
    val progressFraction = if (isCurrentWatched && watchProgress.durationMs > 0) {
        (watchProgress.positionMs.toFloat() / watchProgress.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    FrostedGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .metroClickable(onClick = onClick),
        shape = MetroDimens.radiusSmall,
        tint = if (isCurrentWatched) scheme.glassHover else scheme.glass,
        borderColor = if (isCurrentWatched) scheme.accent.copy(alpha = 0.6f) else scheme.stroke,
        borderWidth = MetroDimens.strokeWidth,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Play icon circle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(if (isCurrentWatched) scheme.accent else scheme.accent.copy(alpha = 0.2f))
                        .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.radiusSmall)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Смотреть",
                        tint = if (isCurrentWatched) Color.White else scheme.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Серия ${episode.ordinal}",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = scheme.text,
                    )
                    if (!episode.name.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = episode.name,
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (isCurrentWatched) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Остановлено на ${formatMinSec(watchProgress.positionMs)}",
                            fontFamily = MetroFonts.text,
                            fontSize = 11.sp,
                            color = scheme.accent,
                        )
                    }
                }

                if (episode.durationSec != null && episode.durationSec > 0) {
                    val mins = episode.durationSec / 60
                    Text(
                        text = "$mins мин.",
                        fontFamily = MetroFonts.text,
                        fontSize = 11.sp,
                        color = scheme.textDim,
                    )
                }
            }

            if (progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.1f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(scheme.accent)
                    )
                }
            }
        }
    }
}

private fun formatMinSec(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}

