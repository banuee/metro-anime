package dev.metro.anime.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.metro.anime.data.UpdateRepository
import dev.metro.anime.data.model.*
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.data.settings.MetroSettingsRepository
import dev.metro.anime.data.sync.MetroSyncClient
import dev.metro.anime.ui.components.*
import dev.metro.anime.ui.theme.FrostedGlassBox
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable
import kotlinx.coroutines.launch

enum class HomeTab(val label: String, val pivotLabel: String) {
    LATEST("Свежие серии", "свежее"),
    SCHEDULE("Расписание", "график"),
    HISTORY("История", "история"),
    SEARCH("Поиск", "поиск"),
    BOOKMARKS("Избранное", "избранное"),
    SETTINGS("Параметры", "параметры"),
}

@Composable
fun HomeScreen(
    repository: AnimeRepository,
    settingsRepo: MetroSettingsRepository,
    updateRepo: UpdateRepository,
    syncClient: MetroSyncClient? = null,
    onAnimeClick: (AnimeTitle) -> Unit,
    onOpenSettings: () -> Unit,
    usePivotHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val scheme = LocalMetroScheme.current
    val scope = rememberCoroutineScope()

    val tabsCount = HomeTab.entries.size
    val infinitePageCount = tabsCount * 2000
    val initialPage = (infinitePageCount / 2) - ((infinitePageCount / 2) % tabsCount)

    fun pageToTab(page: Int): HomeTab {
        val mod = ((page % tabsCount) + tabsCount) % tabsCount
        return HomeTab.entries[mod]
    }

    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { infinitePageCount })
    var activeTab by remember { mutableStateOf(HomeTab.LATEST) }
    var searchQuery by remember { mutableStateOf("") }

    var latestList by remember { mutableStateOf<List<AnimeTitle>>(emptyList()) }
    var scheduleList by remember { mutableStateOf<List<ScheduleEntry>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<AnimeTitle>>(emptyList()) }
    val bookmarks by repository.bookmarks.collectAsState()
    val history by repository.history.collectAsState()

    var isLoading by remember { mutableStateOf(false) }
    var isLoadingSchedule by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scheduleError by remember { mutableStateOf<String?>(null) }

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

    fun loadSchedule() {
        scope.launch {
            isLoadingSchedule = true
            scheduleError = null
            try {
                scheduleList = repository.getSchedule()
                if (scheduleList.isEmpty()) {
                    scheduleError = "Расписание пусто. Проверьте сеть."
                }
            } catch (e: Exception) {
                scheduleError = "Ошибка соединения: ${e.localizedMessage}"
            } finally {
                isLoadingSchedule = false
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

    // Sync infinite HorizontalPager page changes with activeTab
    LaunchedEffect(pagerState.currentPage) {
        if (usePivotHeader) {
            val pageTab = pageToTab(pagerState.currentPage)
            if (activeTab != pageTab) {
                activeTab = pageTab
                if (pageTab == HomeTab.LATEST && latestList.isEmpty()) {
                    loadLatest()
                }
                if (pageTab == HomeTab.SCHEDULE && scheduleList.isEmpty()) {
                    loadSchedule()
                }
            }
        }
    }

    val onSelectClassicTab: (HomeTab) -> Unit = { tab ->
        activeTab = tab
        if (tab == HomeTab.LATEST && latestList.isEmpty()) {
            loadLatest()
        }
        if (tab == HomeTab.SCHEDULE && scheduleList.isEmpty()) {
            loadSchedule()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        if (usePivotHeader) {
            // =================================================================
            // Windows Phone Pivot / Metro Hub Header
            // =================================================================
            Text(
                text = "METRO ANIME",
                fontFamily = MetroFonts.headline,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                color = scheme.textDim,
                modifier = Modifier.padding(top = 6.dp, bottom = 0.dp),
            )

            // Infinite Pivot Header Titles Row (прижато вплотную к METRO ANIME)
            val pivotListState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
            LaunchedEffect(pagerState.currentPage) {
                pivotListState.animateScrollToItem(pagerState.currentPage)
            }

            LazyRow(
                state = pivotListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 0.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(infinitePageCount) { index ->
                    val tab = pageToTab(index)
                    val isSelected = index == pagerState.currentPage
                    val alpha by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0.35f,
                        animationSpec = tween(durationMillis = 200),
                        label = "pivot_tab_alpha",
                    )
                    Text(
                        text = tab.pivotLabel,
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Light,
                        fontSize = 38.sp,
                        letterSpacing = (-0.5).sp,
                        color = scheme.text.copy(alpha = alpha),
                        modifier = Modifier.metroClickable(targetScale = 0.96f) {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Infinite Swipeable Content with HorizontalPager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 1,
            ) { page ->
                when (pageToTab(page)) {
                    HomeTab.LATEST -> LatestTabContent(
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        latestList = latestList,
                        history = history,
                        onRetry = { loadLatest() },
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.SCHEDULE -> ScheduleTabContent(
                        isLoading = isLoadingSchedule,
                        errorMessage = scheduleError,
                        schedule = scheduleList,
                        onRetry = { loadSchedule() },
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.HISTORY -> HistoryTabContent(
                        history = history,
                        onAnimeClick = onAnimeClick,
                        onDelete = { repository.removeHistoryItem(it) },
                    )

                    HomeTab.SEARCH -> SearchTabContent(
                        includeSearchBar = true,
                        query = searchQuery,
                        onQueryChange = {
                            searchQuery = it
                            performSearch(it)
                        },
                        onSearch = { performSearch(searchQuery) },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        searchResults = searchResults,
                        onRetry = { performSearch(searchQuery) },
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.BOOKMARKS -> BookmarksTabContent(
                        bookmarks = bookmarks,
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.SETTINGS -> {
                        SettingsScreen(
                            settingsRepo = settingsRepo,
                            updateRepo = updateRepo,
                            animeRepo = repository,
                            syncClient = syncClient,
                            onBackClick = {},
                            isEmbedded = true,
                        )
                    }
                }
            }
        } else {
            // =================================================================
            // Classic Metro Header with Chips (быстрый откат / альтернативный режим)
            // =================================================================
            Spacer(modifier = Modifier.height(16.dp))

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

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(HomeTab.entries) { tab ->
                    MetroChip(
                        text = tab.label,
                        isSelected = activeTab == tab,
                        onClick = { onSelectClassicTab(tab) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

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

            Box(modifier = Modifier.weight(1f)) {
                when (activeTab) {
                    HomeTab.LATEST -> LatestTabContent(
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        latestList = latestList,
                        history = history,
                        onRetry = { loadLatest() },
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.SCHEDULE -> ScheduleTabContent(
                        isLoading = isLoadingSchedule,
                        errorMessage = scheduleError,
                        schedule = scheduleList,
                        onRetry = { loadSchedule() },
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.HISTORY -> HistoryTabContent(
                        history = history,
                        onAnimeClick = onAnimeClick,
                        onDelete = { repository.removeHistoryItem(it) },
                    )

                    HomeTab.SEARCH -> SearchTabContent(
                        includeSearchBar = false,
                        query = searchQuery,
                        onQueryChange = {
                            searchQuery = it
                            performSearch(it)
                        },
                        onSearch = { performSearch(searchQuery) },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        searchResults = searchResults,
                        onRetry = { performSearch(searchQuery) },
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.BOOKMARKS -> BookmarksTabContent(
                        bookmarks = bookmarks,
                        onAnimeClick = onAnimeClick,
                    )

                    HomeTab.SETTINGS -> {
                        SettingsScreen(
                            settingsRepo = settingsRepo,
                            updateRepo = updateRepo,
                            animeRepo = repository,
                            syncClient = syncClient,
                            onBackClick = onOpenSettings,
                            isEmbedded = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LatestTabContent(
    isLoading: Boolean,
    errorMessage: String?,
    latestList: List<AnimeTitle>,
    history: List<WatchProgress>,
    onRetry: () -> Unit,
    onAnimeClick: (AnimeTitle) -> Unit,
) {
    val scheme = LocalMetroScheme.current

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
                text = errorMessage,
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = scheme.red,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            MetroButton(
                text = "Повторить",
                onClick = onRetry,
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (history.isNotEmpty()) {
                val last = history.first()

                FrostedGlassBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .metroClickable { onAnimeClick(last.anime.sanitized()) },
                    shape = MetroDimens.radiusSmall,
                    tint = Color.White.copy(alpha = 0.05f),
                    borderColor = scheme.accent.copy(alpha = 0.35f),
                    borderWidth = 1.dp,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(scheme.accent.copy(alpha = 0.16f))
                                .border(1.dp, scheme.accent.copy(alpha = 0.55f), RoundedCornerShape(MetroDimens.radiusSmall)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = scheme.accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = last.anime.titleRu,
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = scheme.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val posText = if (last.positionMs == 0L) "Новая серия" else "${formatMinSec(last.positionMs)} / ${formatMinSec(last.durationMs)}"
                            Text(
                                text = "Серия ${last.episodeOrdinal} • $posText",
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

@Composable
private fun ScheduleTabContent(
    isLoading: Boolean,
    errorMessage: String?,
    schedule: List<ScheduleEntry>,
    onRetry: () -> Unit,
    onAnimeClick: (AnimeTitle) -> Unit,
) {
    val scheme = LocalMetroScheme.current

    if (isLoading && schedule.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = scheme.accent, strokeWidth = 2.dp)
        }
        return
    }
    if (errorMessage != null && schedule.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = errorMessage,
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = scheme.red,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            MetroButton(text = "Повторить", onClick = onRetry)
        }
        return
    }
    if (schedule.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Расписание выхода серий недоступно",
                fontFamily = MetroFonts.text,
                fontSize = 14.sp,
                color = scheme.textDim,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val todayIso = run {
        val dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        if (dow == java.util.Calendar.SUNDAY) 7 else dow - 1
    }
    val dateFormat = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")) }

    val groups = remember(schedule, todayIso) {
        val known = schedule.filter { it.publishDay != null && it.publishDay in 1..7 }
        val unknown = schedule.filter { it.publishDay == null || it.publishDay !in 1..7 }

        val grouped = known
            .groupBy { entry -> ((entry.publishDay!! - todayIso) + 7) % 7 }
            .map { (offset, entries) ->
                val day = entries.first().publishDay ?: 1
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_MONTH, offset)
                val label = when (offset) {
                    0 -> "Сегодня"
                    1 -> "Завтра"
                    else -> weekdayNameRu(day)
                }
                ScheduleDayGroup(
                    offset = offset,
                    label = label,
                    dateLabel = dateFormat.format(cal.time),
                    entries = entries.sortedBy { it.anime.titleRu },
                )
            }
            .sortedBy { it.offset }
            .toMutableList()

        if (unknown.isNotEmpty()) {
            grouped.add(
                ScheduleDayGroup(
                    offset = 99,
                    label = "Другие",
                    dateLabel = "",
                    entries = unknown.sortedBy { it.anime.titleRu },
                )
            )
        }
        grouped
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "schedule_header") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    text = "ГРАФИК ВЫХОДА",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    letterSpacing = 1.2.sp,
                    color = scheme.textDim,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Новые серии онгоингов этого сезона",
                    fontFamily = MetroFonts.text,
                    fontSize = 12.sp,
                    color = scheme.textDim.copy(alpha = 0.8f),
                )
            }
        }

        groups.forEach { group ->
            item(key = "day_header_${group.offset}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(scheme.accent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = group.label,
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = scheme.text,
                    )
                    if (group.dateLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = group.dateLabel,
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${group.entries.size}",
                        fontFamily = MetroFonts.text,
                        fontSize = 12.sp,
                        color = scheme.accent,
                    )
                }
            }

            items(group.entries, key = { "${it.anime.id}_${it.publishDay}_${it.nextEpisode}" }) { entry ->
                ScheduleItemCard(
                    entry = entry,
                    onClick = { onAnimeClick(entry.anime) },
                )
            }
        }
    }
}

@Composable
private fun ScheduleItemCard(
    entry: ScheduleEntry,
    onClick: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val anime = entry.anime
    val isSerost = dev.metro.anime.ui.theme.LocalSerostMode.current
    val posterModel: Any? = if (isSerost) dev.metro.anime.R.drawable.serost_cat else anime.posterUrl

    FrostedGlassBox(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .metroClickable(onClick = onClick),
        shape = MetroDimens.radiusSmall,
        tint = Color.White.copy(alpha = 0.04f),
        borderColor = scheme.stroke,
        borderWidth = MetroDimens.strokeWidth,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(66.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(scheme.glass)
                    .border(1.dp, scheme.stroke, RoundedCornerShape(4.dp)),
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(posterModel)
                        .crossfade(true)
                        .size(180, 250)
                        .build(),
                    contentDescription = anime.titleRu,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anime.titleRu,
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = scheme.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                )
                Spacer(modifier = Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val epText = when {
                        entry.nextEpisode != null -> "Серия ${entry.nextEpisode}"
                        entry.publishedEpisode != null -> "Вышла ${entry.publishedEpisode}"
                        else -> "Новая серия"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(scheme.accent.copy(alpha = 0.16f))
                            .border(1.dp, scheme.accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = epText,
                            fontFamily = MetroFonts.text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = scheme.accent,
                        )
                    }
                    val meta = listOfNotNull(
                        anime.year?.toString(),
                        anime.type?.takeIf { it.isNotBlank() },
                    ).joinToString(" • ")
                    if (meta.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = meta,
                            fontFamily = MetroFonts.text,
                            fontSize = 11.sp,
                            color = scheme.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private data class ScheduleDayGroup(
    val offset: Int,
    val label: String,
    val dateLabel: String,
    val entries: List<ScheduleEntry>,
)

private fun weekdayNameRu(isoDay: Int): String = when (isoDay) {
    1 -> "Понедельник"
    2 -> "Вторник"
    3 -> "Среда"
    4 -> "Четверг"
    5 -> "Пятница"
    6 -> "Суббота"
    else -> "Воскресенье"
}

@Composable
private fun HistoryTabContent(
    history: List<WatchProgress>,
    onAnimeClick: (AnimeTitle) -> Unit,
    onDelete: (String) -> Unit,
) {
    val scheme = LocalMetroScheme.current

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
                    onClick = { onAnimeClick(item.anime.sanitized()) },
                    onDelete = { onDelete(item.anime.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchTabContent(
    includeSearchBar: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    searchResults: List<AnimeTitle>,
    onRetry: () -> Unit,
    onAnimeClick: (AnimeTitle) -> Unit,
) {
    val scheme = LocalMetroScheme.current

    Column(modifier = Modifier.fillMaxSize()) {
        if (includeSearchBar) {
            MetroSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        if (isLoading && searchResults.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = scheme.accent,
                    strokeWidth = 2.dp,
                )
            }
        } else if (errorMessage != null && searchResults.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = errorMessage,
                    fontFamily = MetroFonts.text,
                    fontSize = 14.sp,
                    color = scheme.red,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                MetroButton(
                    text = "Повторить",
                    onClick = onRetry,
                )
            }
        } else if (searchResults.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val emptyText = if (query.isEmpty()) "Введите название аниме для поиска" else "Ничего не найдено"
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
                items(searchResults, key = { it.id }) { anime ->
                    AnimeTileCard(
                        anime = anime,
                        onClick = { onAnimeClick(anime) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarksTabContent(
    bookmarks: List<AnimeTitle>,
    onAnimeClick: (AnimeTitle) -> Unit,
) {
    val scheme = LocalMetroScheme.current

    if (bookmarks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "В закладках пока пусто",
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
            items(bookmarks, key = { it.id }) { anime ->
                AnimeTileCard(
                    anime = anime,
                    onClick = { onAnimeClick(anime) },
                )
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
        tint = Color.White.copy(alpha = 0.05f),
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
                val isSerost = dev.metro.anime.ui.theme.LocalSerostMode.current
                val historyPosterModel: Any? = if (isSerost) dev.metro.anime.R.drawable.serost_cat else item.anime.posterUrl
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(84.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, scheme.strokeStrong, RoundedCornerShape(4.dp)),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(historyPosterModel)
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
                    val timeText = if (item.positionMs == 0L) "Новая серия" else "${formatMinSec(item.positionMs)} / ${formatMinSec(item.durationMs)}"
                    Text(
                        text = "${item.dubbingTitle} • $timeText",
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
