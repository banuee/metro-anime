package dev.metro.anime.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.ui.components.MetroButton
import dev.metro.anime.ui.components.MetroChip
import dev.metro.anime.ui.components.MetroIconButton
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    anime: AnimeTitle,
    episode: AnimeEpisode,
    source: AnimeSource,
    repository: AnimeRepository,
    dubbingTitle: String = "",
    initialPositionMs: Long = 0L,
    allEpisodes: List<AnimeEpisode> = emptyList(),
    onBackClick: () -> Unit,
    onNextEpisodeClick: ((AnimeEpisode) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = LocalMetroScheme.current

    var streams by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedQuality by remember { mutableStateOf("720") }
    var isResolvingStreams by remember { mutableStateOf(true) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var isPlaybackEnded by remember { mutableStateOf(false) }

    var hasRestoredInitialPosition by remember(episode) { mutableStateOf(false) }

    val nextEpisode = remember(episode, allEpisodes) {
        allEpisodes.find { it.ordinal == episode.ordinal + 1 }
    }

    fun saveCurrentProgress() {
        if (currentPositionMs > 3000L && durationMs > 10000L) {
            repository.saveProgress(
                dev.metro.anime.data.model.WatchProgress(
                    anime = anime,
                    episodeOrdinal = episode.ordinal,
                    episodeName = episode.name,
                    dubbingTitle = dubbingTitle.ifBlank { source.label },
                    source = source,
                    positionMs = currentPositionMs,
                    durationMs = durationMs,
                )
            )
        }
    }

    // Lock orientation to sensor landscape while player is open
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val origOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = origOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Initialize ExoPlayer
    val exoPlayer = remember {
        val codecSelector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val list = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            list.sortedBy { if (it.name.contains("goldfish", ignoreCase = true)) 1 else 0 }
        }
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                    isPlaybackEnded = playbackState == Player.STATE_ENDED
                    if (playbackState == Player.STATE_READY) {
                        durationMs = duration.coerceAtLeast(0L)
                        if (!hasRestoredInitialPosition && initialPositionMs > 2000L) {
                            seekTo(initialPositionMs)
                            hasRestoredInitialPosition = true
                        }
                    }
                }
            })
        }
    }

    // Resolve streams
    LaunchedEffect(episode, source) {
        isResolvingStreams = true
        resolveError = null
        try {
            val resolved = repository.resolveEpisodeStream(episode, source)
            if (resolved.isNotEmpty()) {
                streams = resolved
                selectedQuality = when {
                    resolved.containsKey("1080") -> "1080"
                    resolved.containsKey("720") -> "720"
                    resolved.containsKey("480") -> "480"
                    else -> resolved.keys.first()
                }
            } else {
                resolveError = "Не удалось получить видеопоток."
            }
        } catch (e: Exception) {
            resolveError = "Ошибка: ${e.localizedMessage}"
        } finally {
            isResolvingStreams = false
        }
    }

    // Feed URL to ExoPlayer on quality / stream change
    LaunchedEffect(streams, selectedQuality) {
        val streamUrl = streams[selectedQuality] ?: streams.values.firstOrNull()
        if (streamUrl != null) {
            val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
            val curPos = exoPlayer.currentPosition
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            if (curPos > 0) {
                exoPlayer.seekTo(curPos)
            }
            exoPlayer.play()
        }
    }

    // Periodic timecode poll & auto-save progress
    LaunchedEffect(exoPlayer, episode) {
        var lastSavedSec = 0L
        while (isActive) {
            val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
            currentPositionMs = cur
            if (exoPlayer.duration > 0) {
                durationMs = exoPlayer.duration
            }
            val curSec = cur / 1000
            if (curSec > 3 && (curSec - lastSavedSec >= 5)) {
                lastSavedSec = curSec
                saveCurrentProgress()
            }
            delay(500)
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(5000)
            showControls = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveCurrentProgress()
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Video View with TextureView to avoid emulator green screen
        AndroidView(
            factory = { ctx ->
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(dev.metro.anime.R.layout.item_player_view, null) as PlayerView
                view.apply {
                    player = exoPlayer
                    useController = false
                    isClickable = false
                    isFocusable = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Buffering Indicator
        if (isBuffering || isResolvingStreams) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = scheme.accent,
                    strokeWidth = 3.dp,
                )
            }
        }

        // Resolving Error
        if (resolveError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = resolveError ?: "",
                        fontFamily = MetroFonts.text,
                        color = scheme.red,
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    MetroButton(
                        text = "Назад",
                        onClick = onBackClick,
                    )
                }
            }
        }

        // Skip Opening / Ending Button
        val curSec = (currentPositionMs / 1000).toInt()
        val op = episode.opening
        val ed = episode.ending
        val showSkip = when {
            op != null && curSec in op.startSec..op.endSec -> true
            ed != null && curSec in ed.startSec..ed.endSec -> true
            else -> false
        }
        val skipTargetSec = when {
            op != null && curSec in op.startSec..op.endSec -> op.endSec
            ed != null && curSec in ed.startSec..ed.endSec -> ed.endSec
            else -> null
        }

        // Fullscreen touch interceptor over PlayerView when controls are hidden
        if (!showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        showControls = true
                    }
            )
        }

        AnimatedVisibility(
            visible = showSkip && skipTargetSec != null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 76.dp, end = 24.dp),
        ) {
            if (skipTargetSec != null) {
                MetroButton(
                    text = "Пропустить заставку",
                    onClick = {
                        exoPlayer.seekTo(skipTargetSec * 1000L)
                    },
                )
            }
        }

        // End of Episode Dialog / Card
        if (isPlaybackEnded && nextEpisode != null && onNextEpisodeClick != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(MetroDimens.radius))
                        .background(scheme.glassDeep)
                        .border(1.dp, scheme.strokeStrong, RoundedCornerShape(MetroDimens.radius))
                        .padding(24.dp),
                ) {
                    Text(
                        text = "Серия ${episode.ordinal} завершена",
                        fontFamily = MetroFonts.headline,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.text,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Следующая: Серия ${nextEpisode.ordinal}",
                        fontFamily = MetroFonts.text,
                        fontSize = 13.sp,
                        color = scheme.textDim,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetroButton(
                            text = "Назад",
                            onClick = onBackClick,
                        )
                        MetroButton(
                            text = "Включить след. серию",
                            onClick = {
                                saveCurrentProgress()
                                onNextEpisodeClick(nextEpisode)
                            },
                        )
                    }
                }
            }
        }

        // Metro Acrylic Controls Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        showControls = false
                    }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.70f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f),
                            ),
                        )
                    )
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetroIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        onClick = onBackClick,
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = anime.titleRu,
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = scheme.text,
                            maxLines = 1,
                        )
                        Text(
                            text = "Серия ${episode.ordinal}${if (!episode.name.isNullOrBlank()) ": ${episode.name}" else ""}",
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                            maxLines = 1,
                        )
                    }

                    // Quality Selector Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        streams.keys.sortedDescending().forEach { q ->
                            MetroChip(
                                text = "${q}p",
                                isSelected = selectedQuality == q,
                                onClick = { selectedQuality = q },
                            )
                        }
                    }
                }

                // Center Controls (Rewind, Play/Pause, Forward)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Rewind 10s
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(scheme.glassDeep)
                            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                            .metroClickable {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = "Назад 10 сек",
                            tint = scheme.text,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // Play / Pause
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(MetroDimens.radius))
                            .background(scheme.accent)
                            .border(1.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(MetroDimens.radius))
                            .metroClickable {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Играть",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Forward 10s
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                            .background(scheme.glassDeep)
                            .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                            .metroClickable {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "Вперед 10 сек",
                            tint = scheme.text,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // Next Episode (if available)
                    if (nextEpisode != null && onNextEpisodeClick != null) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                                .background(scheme.glassDeep)
                                .border(1.dp, scheme.stroke, RoundedCornerShape(MetroDimens.radiusSmall))
                                .metroClickable {
                                    saveCurrentProgress()
                                    onNextEpisodeClick(nextEpisode)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Следующая серия",
                                tint = scheme.text,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                // Bottom Bar (Progress + Timestamps + Next)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatTime(currentPositionMs),
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.text,
                        )

                        Text(
                            text = formatTime(durationMs),
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                        )
                    }

                    Slider(
                        value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                        onValueChange = { fraction ->
                            val target = (fraction * durationMs).toLong()
                            exoPlayer.seekTo(target)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = scheme.accent,
                            activeTrackColor = scheme.accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.20f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
