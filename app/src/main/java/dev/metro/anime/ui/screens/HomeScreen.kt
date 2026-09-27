package dev.metro.anime.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.model.WatchProgress
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.ui.components.*
import dev.metro.anime.ui.theme.FrostedGlassBox
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    LATEST("Свежие серии"),
    HISTORY("История"),
    SEARCH("Поиск"),
    BOOKMARKS("Избранное"),
}

@Composable
fun HomeScreen(
    repository: AnimeRepository,
    onAnimeClick: (AnimeTitle) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(HomeTab.LATEST) }
    var searchQuery by remember { mutableStateOf("") }

    var latestList by remember { mutableStateOf<List<AnimeTitle>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<AnimeTitle>>(emptyList()) }
    val bookmarks by repository.bookmarks.collectAsState()
    val history by repository.history.collectAsState()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadLatest() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                latestList = repository.getLatestAnime()
                if (latestList.isEmpty()) {
                    errorMessage = "Не удалось загрузить каталог. Проверьте сеть."
                }
            } catch (e: Exception) {
                errorMessage = "Ошибка соединения: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    fun performSearch(q: String) {
        if (q.isBlank()) {
            searchResults = emptyList()
            return
        }
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                searchResults = repository.searchAnime(q)
            } catch (e: Exception) {
                errorMessage = "Ошибка поиска: ${e.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadLatest()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Metro Header
        MetroHeader(
            title = "Metro Anime",
            subtitle = null,
            trailingAction = {
                MetroIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Параметры",
                    onClick = onOpenSettings,
                )
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Tabs
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(HomeTab.entries) { tab ->
                MetroChip(
                    text = tab.label,
                    isSelected = activeTab == tab,
                    onClick = {
                        activeTab = tab
                        if (tab == HomeTab.LATEST && latestList.isEmpty()) {
                            loadLatest()
                        }
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search bar (visible in SEARCH tab or when typing)
        AnimatedVisibility(visible = activeTab == HomeTab.SEARCH) {
            Column {
                MetroSearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        performSearch(it)
                    },
                    onSearch = { performSearch(searchQuery) },
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        // Content
        Box(modifier = Modifier.weight(1f)) {
            when (activeTab) {
                HomeTab.HISTORY -> {
                    if (history.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "История просмотров пока пуста",
                                fontFamily = MetroFonts.text,
                                fontSize = 14.sp,
                                color = scheme.textDim,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(history, key = { it.anime.id }) { item ->
                                HistoryItemCard(
                                    item = item,
                                    onClick = { onAnimeClick(item.anime) },
                                    onDelete = { repository.removeHistoryItem(item.anime.id) },
                                )
                            }
                        }
                    }
                }

                HomeTab.LATEST -> {
                    if (isLoading && latestList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = scheme.accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    } else if (errorMessage != null && latestList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                fontFamily = MetroFonts.text,
                                fontSize = 14.sp,
                                color = scheme.red,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            MetroButton(
                                text = "Повторить",
                                onClick = { loadLatest() },
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Resume watching banner if there is history
                            if (history.isNotEmpty()) {
                                val last = history.first()
                                FrostedGlassBox(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                        .metroClickable { onAnimeClick(last.anime) },
                                    shape = MetroDimens.radiusSmall,
                                    tint = scheme.glassHover,
                                    borderColor = scheme.accent.copy(alpha = 0.45f),
                                    borderWidth = 1.dp,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                                .background(scheme.accent.copy(alpha = 0.85f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "ПРОДОЛЖИТЬ: ${last.anime.titleRu}",
                                                fontFamily = MetroFonts.headline,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 12.sp,
                                                color = scheme.text,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "Серия ${last.episodeOrdinal} • ${formatMinSec(last.positionMs)} / ${formatMinSec(last.durationMs)}",
                                                fontFamily = MetroFonts.text,
                                                fontSize = 11.sp,
                                                color = scheme.accent,
                                            )
                                        }
                                    }
                                }
                            }

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(latestList, key = { it.id }) { anime ->
                                    AnimeTileCard(
                                        anime = anime,
                                        onClick = { onAnimeClick(anime) },
                                    )
                                }
                            }
                        }
                    }
                }

                HomeTab.SEARCH, HomeTab.BOOKMARKS -> {
                    val currentList = if (activeTab == HomeTab.SEARCH) searchResults else bookmarks

                    if (isLoading && currentList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = scheme.accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    } else if (errorMessage != null && currentList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                fontFamily = MetroFonts.text,
                                fontSize = 14.sp,
                                color = scheme.red,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            MetroButton(
                                text = "Повторить",
                                onClick = { performSearch(searchQuery) },
                            )
                        }
                    } else if (currentList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val emptyText = if (activeTab == HomeTab.SEARCH) {
                                if (searchQuery.isEmpty()) "Введите название аниме для поиска" else "Ничего не найдено"
                            } else {
                                "В закладках пока пусто"
                            }
                            Text(
                                text = emptyText,
                                fontFamily = MetroFonts.text,
                                fontSize = 14.sp,
                                color = scheme.textDim,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(currentList, key = { it.id }) { anime ->
                                AnimeTileCard(
                                    anime = anime,
                                    onClick = { onAnimeClick(anime) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemCard(
    item: WatchProgress,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val progressFraction = if (item.durationMs > 0) {
        (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    FrostedGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .metroClickable(onClick = onClick),
        shape = MetroDimens.radiusSmall,
        tint = scheme.glass,
        borderColor = scheme.stroke,
        borderWidth = MetroDimens.strokeWidth,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Poster
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(84.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, scheme.strokeStrong, RoundedCornerShape(4.dp)),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.anime.posterUrl)
                            .crossfade(true)
                            .size(180, 250)
                            .build(),
                        contentDescription = item.anime.titleRu,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.anime.titleRu,
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = scheme.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Серия ${item.episodeOrdinal}${if (!item.episodeName.isNullOrBlank()) ": ${item.episodeName}" else ""}",
                        fontFamily = MetroFonts.text,
                        fontSize = 12.sp,
                        color = scheme.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.dubbingTitle} • ${formatMinSec(item.positionMs)} / ${formatMinSec(item.durationMs)}",
                        fontFamily = MetroFonts.text,
                        fontSize = 11.sp,
                        color = scheme.textDim,
                    )
                }

                // Delete button
                MetroIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Удалить из истории",
                    onClick = onDelete,
                )
            }

            // Progress bar
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

