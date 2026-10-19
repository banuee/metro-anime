package dev.metro.anime.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import dev.metro.anime.data.EpisodeNotificationHelper
import dev.metro.anime.data.download.DownloadManager
import dev.metro.anime.data.model.*
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.ui.components.MetroChip
import dev.metro.anime.ui.components.MetroIconButton
import dev.metro.anime.ui.components.MetroTopBar
import dev.metro.anime.ui.theme.FrostedGlassBox
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroBlurEffect
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
    var schedule by remember { mutableStateOf<AnimeSchedule?>(null) }
    var scheduleExpanded by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedEpisodes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var deleteDownloadPrompt by remember { mutableStateOf<Int?>(null) }
    val downloadTasks by DownloadManager.tasks.collectAsState()
    val downloadedEpisodes by DownloadManager.downloads.collectAsState()
    val tasksByOrdinal = remember(downloadTasks, anime.id) {
        downloadTasks.filter { it.anime.id == anime.id }.associateBy { it.episodeOrdinal }
    }
    val downloadedByOrdinal = remember(downloadedEpisodes, anime.id) {
        downloadedEpisodes.filter { it.anime.id == anime.id }.associateBy { it.episodeOrdinal }
    }
    val bookmarks by repository.bookmarks.collectAsState()
    val isBookmarked = remember(bookmarks, anime.id) {
        bookmarks.any { it.id == anime.id }
    }
    val history by repository.history.collectAsState()
    val watchProgress = remember(history, anime.id) {
        history.find { it.anime.id == anime.id } ?: repository.getProgress(anime.id)
    }
    val subscriptions by repository.subscriptions.collectAsState()
    val isSubscribed = remember(subscriptions, anime.id) {
        subscriptions.any { it.anime.id == anime.id }
    }
    val context = LocalContext.current
    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingDownloadAction
        pendingDownloadAction = null
        if (granted) action?.invoke()
    }
    fun withStorageAccess(action: () -> Unit) {
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingDownloadAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(anime.id) {
        isLoading = true
        val loaded = repository.getAnimeDetails(anime.id, anime.shikimoriId, anime.titleRu)
        details = loaded
        // Match active dubbing to previously watched if present
        val saved = repository.getProgress(anime.id)
        if (saved != null && loaded != null && loaded.dubbings.isNotEmpty()) {
            fun cleanDubTitle(t: String): String = t.replace(Regex("\\s*\\(.*\\)"), "").trim().lowercase()
            var idx = loaded.dubbings.indexOfFirst { it.title.equals(saved.dubbingTitle, ignoreCase = true) }
            if (idx < 0) {
                idx = loaded.dubbings.indexOfFirst { cleanDubTitle(it.title) == cleanDubTitle(saved.dubbingTitle) }
            }
            if (idx < 0) {
                idx = loaded.dubbings.indexOfFirst { it.source == saved.source && it.episodes.any { ep -> ep.ordinal == saved.episodeOrdinal } }
            }
            if (idx < 0) {
                idx = loaded.dubbings.indexOfFirst { it.episodes.any { ep -> ep.ordinal == saved.episodeOrdinal } }
            }
            if (idx >= 0) {
                selectedDubIndex = idx
            }
        }
        isLoading = false

        // Release schedule (works even when AniLibria doesn't host the title)
        schedule = try {
            repository.getAnimeSchedule(
                animeId = anime.id,
                shikimoriId = loaded?.title?.shikimoriId ?: anime.shikimoriId,
                titleRu = anime.titleRu,
            )
        } catch (_: Exception) {
            null
        }
    }

    deleteDownloadPrompt?.let { ordinal ->
        AlertDialog(
            onDismissRequest = { deleteDownloadPrompt = null },
            containerColor = Color(0xFF17171C),
            titleContentColor = scheme.text,
            textContentColor = scheme.text,
            title = {
                Text(
                    text = "Удалить скачанную серию?",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Text(
                    text = "Серия $ordinal будет удалена с устройства.",
                    fontFamily = MetroFonts.text,
                    fontSize = 13.sp,
                    color = scheme.textDim,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    DownloadManager.removeDownload(anime.id, ordinal)
                    deleteDownloadPrompt = null
                }) {
                    Text("Удалить", color = Color(0xFFFF6B6B))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDownloadPrompt = null }) {
                    Text("Отмена", color = scheme.accent)
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Bar
        val effectiveTopTitle = details?.title?.titleRu?.ifBlank { null } ?: anime.titleRu
        MetroTopBar(
            title = effectiveTopTitle,
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
                    val detailsPosterData: Any? = if (isSerost) {
                        dev.metro.anime.R.drawable.serost_cat
                    } else {
                        loadedDetails?.title?.posterUrl?.ifBlank { null } ?: anime.posterUrl
                    }
                    val displayTitleRu = loadedDetails?.title?.titleRu?.ifBlank { null } ?: anime.titleRu
                    val displayTitleOrig = loadedDetails?.title?.titleOrig ?: anime.titleOrig
                    val displayRating = loadedDetails?.title?.rating ?: anime.rating
                    val displayYear = loadedDetails?.title?.year ?: anime.year
                    val displaySeason = loadedDetails?.title?.season ?: anime.season
                    val displayType = loadedDetails?.title?.type ?: anime.type

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Single crisp poster thumbnail on clean acrylic
                        Box(
                            modifier = Modifier
                                .width(96.dp)
                                .height(138.dp)
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(scheme.glass)
                                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall)),
                        ) {
                            AsyncImage(
                                model = detailsPosterData,
                                contentDescription = displayTitleRu,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayTitleRu,
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                                color = scheme.text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 24.sp,
                            )

                            if (!displayTitleOrig.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = displayTitleOrig,
                                    fontFamily = MetroFonts.text,
                                    fontSize = 12.sp,
                                    color = scheme.textDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (displayRating != null && displayRating > 0) {
                                    Text(
                                        text = "★ ${String.format(java.util.Locale.US, "%.1f", displayRating)}",
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = scheme.accent,
                                    )
                                }
                                val otherMeta = listOfNotNull(
                                    displayYear?.toString(),
                                    displaySeason ?: displayType
                                ).joinToString(" • ")
                                if (otherMeta.isNotBlank()) {
                                    if (displayRating != null && displayRating > 0) {
                                        Text(
                                            text = "•",
                                            fontFamily = MetroFonts.text,
                                            fontSize = 11.sp,
                                            color = scheme.textDim.copy(alpha = 0.5f),
                                        )
                                    }
                                    Text(
                                        text = otherMeta,
                                        fontFamily = MetroFonts.text,
                                        fontSize = 12.sp,
                                        color = scheme.textDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }


                // Genres
                val displayGenres = loadedDetails?.title?.safeGenres?.ifEmpty { null } ?: anime.safeGenres
                if (displayGenres.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(displayGenres) { genre ->
                                MetroChip(text = genre)
                            }
                        }
                    }
                }

                // Description
                val desc = (loadedDetails?.rawDescription ?: loadedDetails?.title?.description ?: anime.description)
                    ?.takeIf { !it.startsWith("Просмотр через Kodik") }
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

                // Release schedule (collapsible, show only for ongoing titles)
                val currentSchedule = schedule
                if (currentSchedule != null && currentSchedule.isOngoing) {
                    item {
                        AnimeScheduleSection(
                            schedule = currentSchedule,
                            expanded = scheduleExpanded,
                            onToggleExpand = { scheduleExpanded = !scheduleExpanded },
                            isSubscribed = isSubscribed,
                            onToggleSubscription = {
                                if (!isSubscribed &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !EpisodeNotificationHelper.canPostNotifications(context)
                                ) {
                                    notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                repository.toggleEpisodeSubscription(
                                    anime = anime.sanitized(),
                                    shikimoriId = details?.title?.shikimoriId ?: anime.shikimoriId,
                                    schedule = currentSchedule,
                                )
                            },
                        )
                    }
                }

                // Dubbings selector (Windows Phone Pivot style)
                if (currentDubs.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 18.dp)) {
                            Text(
                                text = "ОЗВУЧКА",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                letterSpacing = 2.sp,
                                color = scheme.textDim,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val dubListState = rememberLazyListState(initialFirstVisibleItemIndex = selectedDubIndex)
                            LaunchedEffect(selectedDubIndex) {
                                if (selectedDubIndex in currentDubs.indices) {
                                    dubListState.animateScrollToItem(selectedDubIndex)
                                }
                            }
                            LazyRow(
                                state = dubListState,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                            ) {
                                items(currentDubs.indices.toList()) { idx ->
                                    val dub = currentDubs[idx]
                                    val isSelected = idx == selectedDubIndex
                                    val alpha by animateFloatAsState(
                                        targetValue = if (isSelected) 1f else 0.35f,
                                        animationSpec = tween(durationMillis = 200),
                                        label = "dub_pivot_alpha",
                                    )
                                    Text(
                                        text = dub.title.lowercase(),
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.Light,
                                        fontSize = 28.sp,
                                        letterSpacing = (-0.5).sp,
                                        color = scheme.text.copy(alpha = alpha),
                                        modifier = Modifier.metroClickable(targetScale = 0.96f) {
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

                // Continue Watching Card (near episodes)
                if (watchProgress != null) {
                    item {
                        val currentTargetIndex = episodes.indexOfFirst { it.ordinal == watchProgress.episodeOrdinal }
                        val currentTargetEp = if (currentTargetIndex >= 0) episodes[currentTargetIndex] else null
                        val isNearEnd = currentTargetEp != null && (
                            (currentTargetEp.ending != null && watchProgress.positionMs >= (currentTargetEp.ending.startSec - 10) * 1000L) ||
                            (watchProgress.durationMs > 60_000L && watchProgress.positionMs >= watchProgress.durationMs - 45_000L)
                        )
                        val nextEp = if (currentTargetIndex >= 0) {
                            episodes.getOrNull(currentTargetIndex + 1)
                        } else {
                            episodes.find { it.ordinal == watchProgress.episodeOrdinal + 1 }
                        }
                        val (targetEp, targetPositionMs, isNewEpisode) = when {
                            isNearEnd && nextEp != null -> Triple(nextEp, 0L, true)
                            watchProgress.positionMs == 0L -> Triple(currentTargetEp ?: episodes.firstOrNull(), 0L, true)
                            currentTargetEp != null -> Triple(currentTargetEp, watchProgress.positionMs, false)
                            else -> Triple(episodes.firstOrNull(), 0L, true)
                        }

                        // Ensure we use a dubbing group that contains the target episode
                        val effectiveDub = if (targetEp != null && activeDub?.episodes?.any { it.ordinal == targetEp.ordinal } == true) {
                            activeDub
                        } else {
                            loadedDetails?.dubbings?.firstOrNull { dub -> dub.episodes.any { it.ordinal == targetEp?.ordinal } } ?: activeDub
                        }
                        val effectiveEpisodes = effectiveDub?.episodes.orEmpty()

                        FrostedGlassBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .metroClickable {
                                    if (targetEp != null && effectiveDub != null) {
                                        onPlayEpisode(
                                            targetEp,
                                            effectiveDub.source,
                                            anime.sanitized(),
                                            effectiveDub.title,
                                            targetPositionMs,
                                            effectiveEpisodes,
                                        )
                                    }
                                },
                            shape = MetroDimens.radiusSmall,
                            tint = Color.White.copy(alpha = 0.05f),
                            borderColor = scheme.accent.copy(alpha = 0.35f),
                            borderWidth = 1.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                        .background(scheme.accent.copy(alpha = 0.16f))
                                        .border(1.dp, scheme.accent.copy(alpha = 0.55f), RoundedCornerShape(MetroDimens.radiusSmall)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Продолжить просмотр",
                                        tint = scheme.accent,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ПРОДОЛЖИТЬ ПРОСМОТР",
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 1.2.sp,
                                        color = scheme.accent,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val displayOrdinal = targetEp?.ordinal ?: watchProgress.episodeOrdinal
                                    val subtitle = if (isNewEpisode) {
                                        "Серия $displayOrdinal • Новая серия"
                                    } else {
                                        "Серия $displayOrdinal • ${formatMinSec(targetPositionMs)} / ${formatMinSec(watchProgress.durationMs)}"
                                    }
                                    Text(
                                        text = subtitle,
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = scheme.text,
                                    )
                                    Text(
                                        text = "Озвучка: ${effectiveDub?.title ?: watchProgress.dubbingTitle}",
                                        fontFamily = MetroFonts.text,
                                        fontSize = 11.sp,
                                        color = scheme.textDim,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(14.dp))
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                    .background(if (selectionMode) scheme.accent else scheme.glass)
                                    .border(1.dp, if (selectionMode) scheme.accent else scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                                    .metroClickable {
                                        selectionMode = !selectionMode
                                        selectedEpisodes = emptySet()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (selectionMode) "Отмена" else "Выбрать",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectionMode) Color.White else scheme.accent,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
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
                    }

                    if (hasManyEpisodes) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item {
                                val isSelected = selectedRangeIndex == 0
                                val alpha by animateFloatAsState(
                                    targetValue = if (isSelected) 1f else 0.35f,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "range_all_alpha",
                                )
                                Text(
                                    text = "все",
                                    fontFamily = MetroFonts.headline,
                                    fontWeight = FontWeight.Light,
                                    fontSize = 24.sp,
                                    letterSpacing = (-0.5).sp,
                                    color = scheme.text.copy(alpha = alpha),
                                    modifier = Modifier.metroClickable(targetScale = 0.96f) {
                                        selectedRangeIndex = 0
                                    },
                                )
                            }
                            items(totalRanges) { rIdx ->
                                val rStart = rIdx * rangeChunkSize + 1
                                val rEnd = minOf((rIdx + 1) * rangeChunkSize, episodes.size)
                                val isSelected = selectedRangeIndex == rIdx + 1
                                val alpha by animateFloatAsState(
                                    targetValue = if (isSelected) 1f else 0.35f,
                                    animationSpec = tween(durationMillis = 200),
                                    label = "range_${rIdx}_alpha",
                                )
                                Text(
                                    text = "$rStart–$rEnd",
                                    fontFamily = MetroFonts.headline,
                                    fontWeight = FontWeight.Light,
                                    fontSize = 24.sp,
                                    letterSpacing = (-0.5).sp,
                                    color = scheme.text.copy(alpha = alpha),
                                    modifier = Modifier.metroClickable(targetScale = 0.96f) {
                                        selectedRangeIndex = rIdx + 1
                                    },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
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
                    itemsIndexed(displayedEpisodes, key = { index, episode -> "${episode.ordinal}_${index}_${episode.name ?: ""}" }) { _, episode ->
                        val activeTask = tasksByOrdinal[episode.ordinal]?.takeIf { it.status.isActive }
                        EpisodeItemRow(
                            episode = episode,
                            watchProgress = watchProgress,
                            selectionMode = selectionMode,
                            isSelected = selectedEpisodes.contains(episode.ordinal),
                            downloaded = downloadedByOrdinal.containsKey(episode.ordinal),
                            activeTask = activeTask,
                            onClick = {
                                if (selectionMode) {
                                    selectedEpisodes = if (selectedEpisodes.contains(episode.ordinal)) {
                                        selectedEpisodes - episode.ordinal
                                    } else {
                                        selectedEpisodes + episode.ordinal
                                    }
                                } else {
                                    val startPos = if (watchProgress?.episodeOrdinal == episode.ordinal) {
                                        watchProgress.positionMs
                                    } else 0L
                                    onPlayEpisode(
                                        episode,
                                        activeDub?.source ?: AnimeSource.ANILIBRIA,
                                        anime.sanitized(),
                                        activeDub?.title ?: "",
                                        startPos,
                                        episodes,
                                    )
                                }
                            },
                            onCancelDownload = {
                                DownloadManager.cancel(anime.id, episode.ordinal)
                            },
                            onDeleteDownloaded = {
                                deleteDownloadPrompt = episode.ordinal
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
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    downloaded: Boolean = false,
    activeTask: DownloadTask? = null,
    onCancelDownload: (() -> Unit)? = null,
    onDeleteDownloaded: (() -> Unit)? = null,
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
        tint = if (isCurrentWatched) scheme.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
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
                // Play icon glass container
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(if (isCurrentWatched) scheme.accent.copy(alpha = 0.26f) else scheme.accent.copy(alpha = 0.12f))
                        .border(1.dp, if (isCurrentWatched) scheme.accent else scheme.accent.copy(alpha = 0.50f), RoundedCornerShape(MetroDimens.radiusSmall)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Смотреть",
                        tint = scheme.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Серия ${episode.ordinal}",
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = scheme.text,
                        )
                        if (episode.isFiller) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFF9800).copy(alpha = 0.20f))
                                    .border(1.dp, Color(0xFFFF9800).copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Филлер",
                                    fontFamily = MetroFonts.text,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = Color(0xFFFFB74D),
                                )
                            }
                        }
                    }
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

                val mins = if (episode.durationSec != null && episode.durationSec > 0) {
                    "${episode.durationSec / 60} мин."
                } else {
                    null
                }

                Spacer(modifier = Modifier.width(8.dp))

                when {
                    selectionMode -> Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) scheme.accent else scheme.textDim,
                        modifier = Modifier.size(24.dp),
                    )

                    activeTask != null -> DownloadProgressBadge(activeTask = activeTask, onCancel = onCancelDownload)

                    downloaded -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Та же кнопка, что и «Скачать», но с галочкой: серия уже на устройстве.
                        // Нажатие — удалить скачанную серию.
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(scheme.glass)
                                .border(1.dp, scheme.accent.copy(alpha = 0.45f), RoundedCornerShape(MetroDimens.radiusSmall))
                                .metroClickable { onDeleteDownloaded?.invoke() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Скачано",
                                tint = scheme.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (mins != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "офлайн",
                                fontFamily = MetroFonts.text,
                                fontSize = 9.sp,
                                color = scheme.accent,
                            )
                        }
                    }

                    mins != null -> Text(
                        text = mins,
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

@Composable
private fun DownloadProgressBadge(activeTask: DownloadTask, onCancel: (() -> Unit)?) {
    val scheme = LocalMetroScheme.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .metroClickable(enabled = onCancel != null) { onCancel?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        when (activeTask.status) {
            DownloadStatus.DOWNLOADING -> {
                CircularProgressIndicator(
                    progress = { activeTask.progress },
                    color = scheme.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = "${(activeTask.progress * 100).toInt()}",
                    fontFamily = MetroFonts.text,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.text,
                )
            }
            DownloadStatus.REMUXING -> {
                CircularProgressIndicator(
                    color = scheme.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }
            else -> {
                CircularProgressIndicator(
                    color = scheme.textDim,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
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

@Composable
private fun AnimeScheduleSection(
    schedule: AnimeSchedule,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    isSubscribed: Boolean,
    onToggleSubscription: () -> Unit,
) {
    val scheme = LocalMetroScheme.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "schedule_chevron",
    )

    val summary = buildString {
        if (schedule.nextEpisode != null) append("Серия ${schedule.nextEpisode}")
        val whenText = scheduleWhenText(schedule)
        if (whenText != null) {
            if (isNotEmpty()) append(" • ")
            append(whenText)
        }
    }.ifBlank { "Онгоинг" }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        FrostedGlassBox(
            modifier = Modifier.fillMaxWidth(),
            shape = MetroDimens.radiusSmall,
            tint = Color.White.copy(alpha = 0.05f),
            borderColor = if (expanded) scheme.accent.copy(alpha = 0.45f) else scheme.stroke,
            borderWidth = MetroDimens.strokeWidth,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .metroClickable(onClick = onToggleExpand)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(scheme.accent.copy(alpha = 0.16f))
                            .border(1.dp, scheme.accent.copy(alpha = 0.5f), RoundedCornerShape(MetroDimens.radiusSmall)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = scheme.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "РАСПИСАНИЕ ВЫХОДА",
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp,
                            color = scheme.textDim,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = summary,
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = scheme.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Свернуть" else "Развернуть",
                        tint = scheme.accent,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(chevronRotation),
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MetroDimens.strokeWidth)
                                .background(scheme.stroke),
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(scheme.accent.copy(alpha = 0.16f))
                                    .border(1.dp, scheme.accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = schedule.statusRu,
                                    fontFamily = MetroFonts.text,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = scheme.accent,
                                )
                            }
                            val progressText = scheduleProgressText(schedule)
                            if (progressText != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = progressText,
                                    fontFamily = MetroFonts.text,
                                    fontSize = 11.sp,
                                    color = scheme.textDim,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = scheduleNextEpisodeLine(schedule),
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = scheme.text,
                        )
                        val whenLong = scheduleWhenLong(schedule)
                        if (whenLong != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = whenLong,
                                fontFamily = MetroFonts.text,
                                fontSize = 12.sp,
                                color = scheme.accent,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Уведомлять о новых сериях",
                                    fontFamily = MetroFonts.headline,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = scheme.text,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Пришлём уведомление, когда выйдет новая серия",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 11.sp,
                                    color = scheme.textDim,
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Switch(
                                checked = isSubscribed,
                                onCheckedChange = { onToggleSubscription() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = scheme.accent,
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                                    uncheckedBorderColor = Color.White.copy(alpha = 0.25f),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun scheduleNextEpisodeLine(schedule: AnimeSchedule): String {
    val ep = schedule.nextEpisode
    return when {
        ep != null -> "Следующая серия: $ep"
        schedule.status == "anons" -> "Премьера ожидается"
        else -> "Информация о серии уточняется"
    }
}

private fun scheduleWhenText(schedule: AnimeSchedule): String? {
    schedule.nextEpisodeAt?.let { return scheduleShortDate(it) }
    schedule.publishDay?.let { return scheduleWeekdayRu(it) }
    return null
}

private fun scheduleWhenLong(schedule: AnimeSchedule): String? {
    val at = schedule.nextEpisodeAt
    if (at != null) {
        val countdown = scheduleCountdown(at)
        return if (countdown != null) "${scheduleFullDate(at)} • $countdown" else scheduleFullDate(at)
    }
    schedule.publishDay?.let { return "${scheduleWeekdayRu(it)} • время уточняется" }
    return null
}

private fun scheduleProgressText(schedule: AnimeSchedule): String? {
    val aired = schedule.episodesAired ?: return null
    val total = schedule.episodesTotal
    return if (total != null && total > 0) {
        "Вышло $aired из $total серий"
    } else {
        "Вышло серий: $aired"
    }
}

private fun scheduleShortDate(ms: Long): String =
    java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(ms))

private fun scheduleFullDate(ms: Long): String =
    java.text.SimpleDateFormat("d MMMM, EEEE, HH:mm", java.util.Locale("ru")).format(java.util.Date(ms))

private fun scheduleCountdown(ms: Long): String? {
    val diff = ms - System.currentTimeMillis()
    if (diff <= 0) return null
    val days = diff / 86_400_000L
    val hours = (diff % 86_400_000L) / 3_600_000L
    val minutes = (diff % 3_600_000L) / 60_000L
    return when {
        days > 0 -> "через $days д. $hours ч."
        hours > 0 -> "через $hours ч. $minutes мин."
        else -> "через $minutes мин."
    }
}

private fun scheduleWeekdayRu(isoDay: Int): String = when (isoDay) {
    1 -> "Понедельник"
    2 -> "Вторник"
    3 -> "Среда"
    4 -> "Четверг"
    5 -> "Пятница"
    6 -> "Суббота"
    else -> "Воскресенье"
}

