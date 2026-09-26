package dev.metro.anime.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.ui.components.MetroButton
import dev.metro.anime.ui.components.MetroChip
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

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

    var playPausePulseVisible by remember { mutableStateOf(false) }
    var playPausePulseIsPlay by remember { mutableStateOf(false) }

    LaunchedEffect(playPausePulseVisible) {
        if (playPausePulseVisible) {
            delay(500)
            playPausePulseVisible = false
        }
    }

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

    var baseSpeed by remember { mutableFloatStateOf(1.0f) }
    var isHoldingLeft by remember { mutableStateOf(false) }
    var isHoldingRight by remember { mutableStateOf(false) }

    var isMuted by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isControlsLocked by remember { mutableStateOf(false) }

    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var seekIndicatorText by remember { mutableStateOf<String?>(null) }
    var seekIndicatorSide by remember { mutableStateOf(0) } // -1 left, +1 right

    val nextEpisode = remember(episode, allEpisodes) {
        allEpisodes.find { it.ordinal == episode.ordinal + 1 }
    }
    val prevEpisode = remember(episode, allEpisodes) {
        allEpisodes.find { it.ordinal == episode.ordinal - 1 }
    }

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

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

    // Fullscreen Immersive Mode & Landscape Orientation
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
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

                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = (state == Player.STATE_BUFFERING)
                    isPlaybackEnded = (state == Player.STATE_ENDED)
                    if (state == Player.STATE_READY) {
                        durationMs = duration.coerceAtLeast(0L)
                        if (initialPositionMs > 0 && currentPosition == 0L) {
                            seekTo(initialPositionMs)
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

    // Feed URL to ExoPlayer
    LaunchedEffect(streams, selectedQuality) {
        val streamUrl = streams[selectedQuality] ?: streams.values.firstOrNull()
        if (streamUrl != null) {
            val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            if (initialPositionMs > 0) {
                exoPlayer.seekTo(initialPositionMs)
            }
            exoPlayer.play()
        }
    }

    // Position tracking loop
    LaunchedEffect(exoPlayer) {
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

    // Auto-hide controls after 5 seconds
    LaunchedEffect(showControls, isPlaying, showSpeedDialog, showSettingsDialog) {
        if (showControls && isPlaying && !showSpeedDialog && !showSettingsDialog) {
            delay(5000)
            showControls = false
        }
    }

    // Cleanup on exit
    DisposableEffect(Unit) {
        onDispose {
            saveCurrentProgress()
            exoPlayer.release()
        }
    }

    // Main Box
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Video View
        AndroidView(
            factory = { ctx ->
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(dev.metro.anime.R.layout.item_player_view, null) as PlayerView
                view.apply {
                    player = exoPlayer
                    useController = false
                    isClickable = false
                    isFocusable = false
                    this.resizeMode = resizeMode
                }
                playerViewRef = view
                view
            },
            update = { view ->
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        // =====================================================================
        // Gesture Layer: 3 Zones (Left: -10s/1.5x, Center: Play/Pause, Right: +10s/2.0x)
        // =====================================================================
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Half (35%)
            Box(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight()
                    .pointerInput(baseSpeed, isControlsLocked) {
                        detectTapGestures(
                            onDoubleTap = {
                                val target = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(target)
                                seekIndicatorText = "-10 сек"
                                seekIndicatorSide = -1
                            },
                            onTap = {
                                if (!isControlsLocked) {
                                    showControls = !showControls
                                    showSpeedDialog = false
                                    showSettingsDialog = false
                                }
                            },
                            onPress = {
                                val released = withTimeoutOrNull(250) {
                                    tryAwaitRelease()
                                }
                                if (released == null && !isControlsLocked) {
                                    isHoldingLeft = true
                                    exoPlayer.setPlaybackSpeed(1.5f)
                                    tryAwaitRelease()
                                    isHoldingLeft = false
                                    exoPlayer.setPlaybackSpeed(baseSpeed)
                                }
                            }
                        )
                    }
            )

            // Center Zone (30%) - Single tap = Play / Pause toggle
            Box(
                modifier = Modifier
                    .weight(0.30f)
                    .fillMaxHeight()
                    .pointerInput(isControlsLocked) {
                        detectTapGestures(
                            onTap = {
                                if (!isControlsLocked) {
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                        isPlaying = false
                                        playPausePulseIsPlay = false
                                    } else {
                                        exoPlayer.play()
                                        isPlaying = true
                                        playPausePulseIsPlay = true
                                    }
                                    playPausePulseVisible = true
                                }
                            }
                        )
                    }
            )

            // Right Half (35%)
            Box(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight()
                    .pointerInput(baseSpeed, isControlsLocked) {
                        detectTapGestures(
                            onDoubleTap = {
                                val target = (exoPlayer.currentPosition + 10000L).coerceAtMost(durationMs)
                                exoPlayer.seekTo(target)
                                seekIndicatorText = "+10 сек"
                                seekIndicatorSide = 1
                            },
                            onTap = {
                                if (!isControlsLocked) {
                                    showControls = !showControls
                                    showSpeedDialog = false
                                    showSettingsDialog = false
                                }
                            },
                            onPress = {
                                val released = withTimeoutOrNull(250) {
                                    tryAwaitRelease()
                                }
                                if (released == null && !isControlsLocked) {
                                    isHoldingRight = true
                                    exoPlayer.setPlaybackSpeed(2.0f)
                                    tryAwaitRelease()
                                    isHoldingRight = false
                                    exoPlayer.setPlaybackSpeed(baseSpeed)
                                }
                            }
                        )
                    }
            )
        }

        // Center Play/Pause Pulsating HUD Feedback
        AnimatedVisibility(
            visible = playPausePulseVisible,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 1.15f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(38.dp))
                    .background(Color(0xFF101016).copy(alpha = 0.65f))
                    .border(1.5.dp, scheme.accent.copy(alpha = 0.8f), RoundedCornerShape(38.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (playPausePulseIsPlay) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        // =====================================================================
        // HUD: Speed Hold Banner (1.5x / 2.0x)
        // =====================================================================
        AnimatedVisibility(
            visible = isHoldingLeft || isHoldingRight,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp),
        ) {
            val boost = if (isHoldingLeft) "1.5x" else "2.0x"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .background(Color(0xFF101016).copy(alpha = 0.70f))
                    .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "УСКОРЕНИЕ $boost",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = scheme.accent,
                    )
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = null,
                        tint = scheme.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // =====================================================================
        // HUD: Seek Indicator (-10s / +10s)
        // =====================================================================
        LaunchedEffect(seekIndicatorText) {
            if (seekIndicatorText != null) {
                delay(700)
                seekIndicatorText = null
            }
        }
        AnimatedVisibility(
            visible = seekIndicatorText != null,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(
                if (seekIndicatorSide < 0) Alignment.CenterStart else Alignment.CenterEnd
            ).padding(horizontal = 48.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radius))
                    .background(Color(0xFF101016).copy(alpha = 0.70f))
                    .border(1.dp, scheme.accent.copy(alpha = 0.6f), RoundedCornerShape(MetroDimens.radius))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (seekIndicatorSide < 0) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = null,
                            tint = scheme.text,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        text = seekIndicatorText ?: "",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = scheme.text,
                    )
                    if (seekIndicatorSide > 0) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = null,
                            tint = scheme.text,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // =====================================================================
        // Buffering / Loading
        // =====================================================================
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

        // =====================================================================
        // Error Overlay
        // =====================================================================
        if (resolveError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
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

        // =====================================================================
        // Skip Opening / Ending Button
        // =====================================================================
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

        AnimatedVisibility(
            visible = showSkip && skipTargetSec != null && !isControlsLocked,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 76.dp, end = 24.dp),
        ) {
            if (skipTargetSec != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                        .background(Color(0xFF101016).copy(alpha = 0.65f))
                        .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.radiusSmall))
                        .metroClickable {
                            exoPlayer.seekTo(skipTargetSec * 1000L)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Пропустить заставку",
                            fontFamily = MetroFonts.headline,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.accent,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = null,
                            tint = scheme.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        // =====================================================================
        // Lock Screen Unlock Button (When controls are locked)
        // =====================================================================
        if (isControlsLocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 20.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF101016).copy(alpha = 0.65f))
                    .border(1.dp, scheme.accent, RoundedCornerShape(10.dp))
                    .metroClickable { isControlsLocked = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Разблокировать",
                    tint = scheme.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // =====================================================================
        // Metro Acrylic Controls Overlay (Top Pills + Bottom Dock)
        // =====================================================================
        AnimatedVisibility(
            visible = showControls && !isControlsLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.45f),
                            ),
                        )
                    )
            ) {
                // -------------------------------------------------------------
                // TOP BAR: 3 Acrylic Pills (Left, Dead-Center, Right)
                // -------------------------------------------------------------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    // LEFT PILL: [Back] [Mute] [Speed] [Aspect]
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF101016).copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = scheme.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        IconButton(onClick = {
                            isMuted = !isMuted
                            exoPlayer.volume = if (isMuted) 0f else 1f
                        }) {
                            Icon(
                                imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = "Звук",
                                tint = if (isMuted) scheme.red else scheme.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        IconButton(onClick = {
                            showSpeedDialog = !showSpeedDialog
                            showSettingsDialog = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Скорость",
                                tint = if (baseSpeed != 1.0f) scheme.accent else scheme.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        IconButton(onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Масштаб",
                                tint = if (resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) scheme.accent else scheme.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // CENTER PILL: [<] [🎬 24 Серия] [>] (Centered exactly)
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF101016).copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            enabled = prevEpisode != null,
                            onClick = {
                                if (prevEpisode != null && onNextEpisodeClick != null) {
                                saveCurrentProgress()
                                onNextEpisodeClick(prevEpisode)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Предыдущая серия",
                            tint = if (prevEpisode != null) scheme.text else scheme.textDim.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = scheme.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${episode.ordinal} Серия",
                            fontFamily = MetroFonts.headline,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = scheme.text,
                        )
                    }

                    IconButton(
                        enabled = nextEpisode != null,
                        onClick = {
                            if (nextEpisode != null && onNextEpisodeClick != null) {
                                saveCurrentProgress()
                                onNextEpisodeClick(nextEpisode)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Следующая серия",
                            tint = if (nextEpisode != null) scheme.text else scheme.textDim.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // RIGHT PILL: [Settings] [Lock]
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF101016).copy(alpha = 0.65f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                        IconButton(onClick = {
                            showSettingsDialog = !showSettingsDialog
                            showSpeedDialog = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Параметры",
                                tint = scheme.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        IconButton(onClick = {
                            isControlsLocked = true
                            showControls = false
                        }) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Заблокировать",
                                tint = scheme.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                // -------------------------------------------------------------
                // Speed Popup Card (Under left pill)
                // -------------------------------------------------------------
                AnimatedVisibility(
                    visible = showSpeedDialog,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 64.dp, start = 20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF101016).copy(alpha = 0.88f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Скорость воспроизведения",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = scheme.text,
                            )
                            Text(
                                text = "${baseSpeed}x",
                                fontFamily = MetroFonts.text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = scheme.accent,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = baseSpeed,
                            onValueChange = {
                                val snapped = when {
                                    it < 0.65f -> 0.5f
                                    it < 0.88f -> 0.75f
                                    it < 1.15f -> 1.0f
                                    it < 1.38f -> 1.25f
                                    it < 1.65f -> 1.5f
                                    it < 1.88f -> 1.75f
                                    else -> 2.0f
                                }
                                baseSpeed = snapped
                                exoPlayer.setPlaybackSpeed(snapped)
                            },
                            valueRange = 0.5f..2.0f,
                            steps = 5,
                            colors = SliderDefaults.colors(
                                thumbColor = scheme.accent,
                                activeTrackColor = scheme.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                            ),
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (baseSpeed == spd) scheme.accent else scheme.glass)
                                        .metroClickable {
                                             baseSpeed = spd
                                             exoPlayer.setPlaybackSpeed(spd)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = "${spd}x",
                                        fontFamily = MetroFonts.text,
                                        fontSize = 11.sp,
                                        color = if (baseSpeed == spd) Color.White else scheme.textDim,
                                    )
                                }
                            }
                        }
                    }
                }

                // -------------------------------------------------------------
                // Settings Popup Card (Under right pill)
                // -------------------------------------------------------------
                AnimatedVisibility(
                    visible = showSettingsDialog,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF101016).copy(alpha = 0.88f))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Параметры видео",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = scheme.text,
                            )
                            IconButton(
                                onClick = { showSettingsDialog = false },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = scheme.textDim,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Качество видео:",
                            fontFamily = MetroFonts.text,
                            fontSize = 11.sp,
                            color = scheme.textDim,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            streams.keys.sortedDescending().forEach { q ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selectedQuality == q) scheme.accent else scheme.glass)
                                        .border(1.dp, if (selectedQuality == q) scheme.accent else scheme.stroke, RoundedCornerShape(6.dp))
                                        .metroClickable { selectedQuality = q }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        text = "${q}p",
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = if (selectedQuality == q) Color.White else scheme.text,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Источник: ${source.label}",
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                        )
                        if (dubbingTitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Озвучка: $dubbingTitle",
                                fontFamily = MetroFonts.text,
                                fontSize = 11.sp,
                                color = scheme.textDim,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // -------------------------------------------------------------
                // BOTTOM DOCK: [Play/Pause] [00:00] [====Slider====] [23:51] [FullscreenExit]
                // -------------------------------------------------------------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF101016).copy(alpha = 0.68f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Play / Pause Button
                        IconButton(
                            onClick = {
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Пауза" else "Играть",
                                tint = scheme.accent,
                                modifier = Modifier.size(26.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Current Time
                        Text(
                            text = formatTime(currentPositionMs),
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.text,
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Progress Seekbar
                        Slider(
                            value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                            onValueChange = { fraction ->
                                val target = (fraction * durationMs).toLong()
                                exoPlayer.seekTo(target)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = scheme.accent,
                                activeTrackColor = scheme.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                            ),
                            modifier = Modifier.weight(1f),
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Duration Time
                        Text(
                            text = formatTime(durationMs),
                            fontFamily = MetroFonts.text,
                            fontSize = 12.sp,
                            color = scheme.textDim,
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // Exit fullscreen / exit player
                        IconButton(
                            onClick = {
                                saveCurrentProgress()
                                onBackClick()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = "Выйти из плеера",
                                tint = scheme.text,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
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
