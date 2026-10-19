package dev.metro.anime.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.metro.anime.data.download.DownloadManager
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.model.formatDownloadBytes
import dev.metro.anime.data.model.isActive
import dev.metro.anime.data.model.sanitized
import dev.metro.anime.data.repository.AnimeRepository
import kotlin.math.roundToInt
import dev.metro.anime.data.settings.MetroSettingsRepository
import androidx.core.content.ContextCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import dev.metro.anime.ui.components.MetroButton
import dev.metro.anime.ui.components.MetroChip
import dev.metro.anime.ui.theme.LocalMetroScheme
import dev.metro.anime.ui.theme.MetroDimens
import dev.metro.anime.ui.theme.MetroFonts
import dev.metro.anime.ui.theme.metroClickable
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.LocalHazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    val settingsRepo = remember { MetroSettingsRepository.getInstance(context) }
    val currentSettings by settingsRepo.settings.collectAsState()
    val playerOpacity = currentSettings.playerControlsOpacity
    val hudPillGlass = Color(0xFF101016).copy(alpha = playerOpacity)
    val hudCardGlass = hudPillGlass
    val hudBorder = Color.White.copy(alpha = (playerOpacity * 0.10f).coerceIn(0.03f, 0.08f))

    val hazeState = remember { HazeState() }
    // Уведомление о загрузке: на Android 13+ без разрешения его не видно,
    // и пользователь не понимает, идёт ли скачивание.
    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )
    // На Android 8-9 запись в публичную папку «Фильмы» требует WRITE_EXTERNAL_STORAGE
    // (на Android 10+ работает MediaStore, разрешение не нужно).
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {},
    )
    val playerBlurEnabled = currentSettings.playerBlurEnabled
    val playerBlurRadius = currentSettings.playerBlurRadius.dp
    val playerHazeStyle = remember(playerBlurRadius) {
        HazeDefaults.style(
            backgroundColor = Color.Transparent,
            blurRadius = playerBlurRadius,
        )
    }

    fun Modifier.playerHaze(state: HazeState): Modifier {
        return if (playerBlurEnabled) {
            this.hazeEffect(state = state) {
                backgroundColor = Color.Transparent
                blurRadius = playerBlurRadius
            }
        } else {
            this
        }
    }

    var playPausePulseVisible by remember { mutableStateOf(false) }
    var playPausePulseIsPlay by remember { mutableStateOf(false) }

    LaunchedEffect(playPausePulseVisible) {
        if (playPausePulseVisible) {
            delay(500)
            playPausePulseVisible = false
        }
    }

    var streamsState by remember { mutableStateOf<Pair<Map<String, String>, String>>(Pair(emptyMap(), "720")) }
    val streams = streamsState.first
    val selectedQuality = streamsState.second
    // Скачанная серия (если есть) — отдельный источник со своим реальным качеством
    var offlineDownload by remember { mutableStateOf<dev.metro.anime.data.model.DownloadedEpisode?>(null) }
    // Скачивание текущей серии в галерею из меню настроек
    var downloadRequestInFlight by remember { mutableStateOf(false) }
    var downloadHudText by remember { mutableStateOf<String?>(null) }
    var downloadHudVisible by remember { mutableStateOf(false) }
    // Активная задача скачивания текущей серии — для показа прогресса прямо в плеере
    val downloadTasks by DownloadManager.tasks.collectAsState()
    val currentDownloadTask = downloadTasks.firstOrNull {
        it.anime.id == anime.id && it.episodeOrdinal == episode.ordinal
    }
    fun updateQuality(newQuality: String) {
        streamsState = Pair(streamsState.first, newQuality)
    }
    var isResolvingStreams by remember { mutableStateOf(true) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var currentLoadedUrl by remember(episode.ordinal) { mutableStateOf<String?>(null) }

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

    var seekCumulativeSeconds by remember { mutableStateOf(0) }
    var seekIndicatorSide by remember { mutableStateOf(0) } // -1 left, +1 right
    var lastSeekSec by remember { mutableIntStateOf(10) }
    var lastSeekSide by remember { mutableIntStateOf(1) }
    var seekIndicatorVisible by remember { mutableStateOf(false) }
    var seekJob by remember { mutableStateOf<Job?>(null) }

    val currentEpisode by rememberUpdatedState(episode)
    // Фоновый резолв онлайн-ссылок: должен отменяться при смене серии,
    // иначе старый ответ подставит URL чужой серии.
    var backgroundResolveJob by remember { mutableStateOf<Job?>(null) }
    val currentAnime by rememberUpdatedState(anime)
    val currentDubbingTitle by rememberUpdatedState(dubbingTitle)
    val currentSource by rememberUpdatedState(source)
    val currentAllEpisodes by rememberUpdatedState(allEpisodes)

    val sortedEpisodes = remember(allEpisodes) {
        allEpisodes.sortedBy { it.ordinal }
    }
    val currentEpIndex = remember(sortedEpisodes, episode) {
        val idx = sortedEpisodes.indexOfFirst { it.ordinal == episode.ordinal }
        if (idx >= 0) idx else sortedEpisodes.indexOf(episode)
    }
    val nextEpisode = remember(sortedEpisodes, currentEpIndex, episode) {
        if (currentEpIndex in sortedEpisodes.indices) {
            sortedEpisodes.getOrNull(currentEpIndex + 1)
        } else {
            allEpisodes.find { it.ordinal == episode.ordinal + 1 }
        }
    }
    val prevEpisode = remember(sortedEpisodes, currentEpIndex, episode) {
        if (currentEpIndex in sortedEpisodes.indices) {
            sortedEpisodes.getOrNull(currentEpIndex - 1)
        } else {
            allEpisodes.find { it.ordinal == episode.ordinal - 1 }
        }
    }

    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    var activeOpening by remember(episode) { mutableStateOf(episode.opening) }
    var activeEnding by remember(episode) { mutableStateOf(episode.ending) }

    LaunchedEffect(episode, anime.shikimoriId) {
        if ((activeOpening == null || activeEnding == null) && anime.shikimoriId != null && anime.shikimoriId > 0) {
            val skipPair = dev.metro.anime.data.api.ApiClient.fetchAniSkip(
                malId = anime.shikimoriId,
                episodeOrdinal = episode.ordinal,
                durationSec = episode.durationSec ?: (durationMs / 1000).toInt(),
            )
            if (skipPair != null) {
                if (activeOpening == null && skipPair.first != null) {
                    activeOpening = skipPair.first
                }
                if (activeEnding == null && skipPair.second != null) {
                    activeEnding = skipPair.second
                }
            }
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

    var seekTargetInitialMs by remember(episode.ordinal, initialPositionMs) { mutableLongStateOf(initialPositionMs) }

    val playerPrefs = remember { context.getSharedPreferences("metro_player_prefs", android.content.Context.MODE_PRIVATE) }
    var isUpscaleEnabled by remember { mutableStateOf(playerPrefs.getBoolean("upscale_enabled", false)) }
    var upscaleHudVisible by remember { mutableStateOf(false) }

    // Initialize ExoPlayer
    val exoPlayer = remember {
        val codecSelector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val list = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            list.sortedBy { if (it.name.contains("goldfish", ignoreCase = true)) 1 else 0 }
        }
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                val defaultSink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioOffloadSupportProvider { _, _ -> androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED }
                    .build()

                return object : androidx.media3.exoplayer.audio.ForwardingAudioSink(defaultSink) {
                    private var currentVol = 1.0f
                    private var needsVolumeNudge = false

                    override fun setVolume(volume: Float) {
                        currentVol = volume
                        super.setVolume(volume)
                    }

                    override fun play() {
                        super.play()
                        needsVolumeNudge = true
                        nudgeVolume()
                    }

                    override fun handleBuffer(
                        buffer: java.nio.ByteBuffer,
                        presentationTimeUs: Long,
                        encodedAccessUnitCount: Int
                    ): Boolean {
                        if (needsVolumeNudge) {
                            nudgeVolume()
                            needsVolumeNudge = false
                        }
                        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    }

                    private fun nudgeVolume() {
                        if (currentVol > 0f) {
                            super.setVolume(0f)
                            super.setVolume(currentVol)
                        }
                    }
                }
            }
        }.setMediaCodecSelector(codecSelector)
         .setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    if (playing && !isMuted) {
                        volume = 0f
                        volume = 1.0f
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("PlayerScreen", "player error: ${error.errorCodeName} ${error.message}", error)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = (state == Player.STATE_BUFFERING)
                    isPlaybackEnded = (state == Player.STATE_ENDED)
                    android.util.Log.d(
                        "PlayerScreen",
                        "state=$state pos=${currentPosition} buffered=${bufferedPosition} url=${currentLoadedUrl}"
                    )
                    if (state == Player.STATE_READY) {
                        durationMs = duration.coerceAtLeast(0L)
                        if (seekTargetInitialMs > 0) {
                            if (currentPosition < seekTargetInitialMs - 3000L || currentPosition == 0L) {
                                seekTo(seekTargetInitialMs)
                            }
                            seekTargetInitialMs = 0L
                        }
                        if (playWhenReady && !isMuted) {
                            volume = 0f
                            volume = 1.0f
                        }
                    }
                }
            })
        }
    }

    fun ensureAudioAwake() {
        if (!isMuted) {
            exoPlayer.volume = 0f
            exoPlayer.volume = 1.0f
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying && !isMuted) {
            ensureAudioAwake()
            delay(150)
            if (isPlaying && !isMuted) {
                ensureAudioAwake()
            }
            delay(350)
            if (isPlaying && !isMuted) {
                ensureAudioAwake()
            }
        }
    }

    /**
     * Сохраняет прогресс просмотра.
     * @param episodeOverride серия, к которой относится позиция. Нужен при смене серии:
     * rememberUpdatedState обновляется ДО вызова onDispose, поэтому currentEpisode уже «новая»
     * серия, а позиция — от «старой». Без явного переопределения 20 минут просмотра серии 4
     * записывались бы на серию 5.
     */
    fun saveCurrentProgress(episodeOverride: AnimeEpisode? = null) {
        val ep = episodeOverride ?: currentEpisode
        val livePos = try { exoPlayer.currentPosition.coerceAtLeast(0L) } catch (_: Throwable) { 0L }
        val liveDur = try { exoPlayer.duration.coerceAtLeast(0L) } catch (_: Throwable) { 0L }
        val pos = if (livePos > 0L) livePos else currentPositionMs
        val dur = when {
            liveDur > 0L -> liveDur
            durationMs > 0L -> durationMs
            (ep.durationSec ?: 0) > 0 -> (ep.durationSec!! * 1000L)
            else -> 0L
        }

        if (pos > 1000L) {
            val ed = activeEnding
            val isNearEndOrEnding = when {
                ed != null && ed.startSec > 30 && pos >= (ed.startSec - 10) * 1000L -> true
                dur > 60_000L && pos >= dur - 45_000L -> true
                else -> false
            }

            val nextEp = nextEpisode ?: currentAllEpisodes.find { it.ordinal == ep.ordinal + 1 }
            if (isNearEndOrEnding && nextEp != null) {
                repository.saveProgress(
                    dev.metro.anime.data.model.WatchProgress(
                        anime = currentAnime,
                        episodeOrdinal = nextEp.ordinal,
                        episodeName = nextEp.name,
                        dubbingTitle = currentDubbingTitle.ifBlank { currentSource.label },
                        source = currentSource,
                        positionMs = 0L,
                        durationMs = 0L,
                    )
                )
            } else {
                repository.saveProgress(
                    dev.metro.anime.data.model.WatchProgress(
                        anime = currentAnime,
                        episodeOrdinal = ep.ordinal,
                        episodeName = ep.name,
                        dubbingTitle = currentDubbingTitle.ifBlank { currentSource.label },
                        source = currentSource,
                        positionMs = pos,
                        durationMs = dur,
                    )
                )
            }
        }
    }

    fun switchToEpisode(target: AnimeEpisode) {
        android.util.Log.d("PlayerScreen", "[switchToEpisode] Switching to episode ${target.ordinal}, name=${target.name}")
        saveCurrentProgress()
        // Pre-save target episode immediately so rapid app-kill or navigation doesn't revert to old episode
        repository.saveProgress(
            dev.metro.anime.data.model.WatchProgress(
                anime = currentAnime,
                episodeOrdinal = target.ordinal,
                episodeName = target.name,
                dubbingTitle = currentDubbingTitle.ifBlank { currentSource.label },
                source = currentSource,
                positionMs = 0L,
                durationMs = 0L,
            )
        )
        onNextEpisodeClick?.invoke(target)
    }

    // Lifecycle observer to guarantee saving progress when app goes to background / closes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, episode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveCurrentProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val currentNextEp by rememberUpdatedState(nextEpisode)
    val currentPrevEp by rememberUpdatedState(prevEpisode)

    val forwardingPlayer = remember(exoPlayer) {
        object : ForwardingPlayer(exoPlayer) {
            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> currentNextEp != null
                    COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> currentPrevEp != null || currentPosition > 3000L
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun getAvailableCommands(): Player.Commands {
                val builder = super.getAvailableCommands().buildUpon()
                if (currentNextEp != null) {
                    builder.add(COMMAND_SEEK_TO_NEXT)
                    builder.add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (currentPrevEp != null || currentPosition > 3000L) {
                    builder.add(COMMAND_SEEK_TO_PREVIOUS)
                    builder.add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                return builder.build()
            }

            override fun seekToNext() {
                android.util.Log.d("PlayerScreen", "[ForwardingPlayer] seekToNext() called, currentNextEp=$currentNextEp")
                val next = currentNextEp
                if (next != null) {
                    switchToEpisode(next)
                }
            }

            override fun seekToNextMediaItem() {
                seekToNext()
            }

            override fun seekToPrevious() {
                android.util.Log.d("PlayerScreen", "[ForwardingPlayer] seekToPrevious() called, pos=${currentPosition}, currentPrevEp=$currentPrevEp")
                if (currentPosition > 5000L) {
                    seekTo(0L)
                } else {
                    val prev = currentPrevEp
                    if (prev != null) {
                        switchToEpisode(prev)
                    } else {
                        seekTo(0L)
                    }
                }
            }

            override fun seekToPreviousMediaItem() {
                seekToPrevious()
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val debouncer = remember(coroutineScope) {
        dev.metro.anime.ui.player.HeadsetHookDebouncer(
            scope = coroutineScope,
            onSingleClick = {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                    playPausePulseIsPlay = false
                    playPausePulseVisible = true
                } else {
                    exoPlayer.play()
                    ensureAudioAwake()
                    playPausePulseIsPlay = true
                    playPausePulseVisible = true
                }
            },
            onDoubleClick = {
                forwardingPlayer.seekToNext()
            },
            onTripleClick = {
                forwardingPlayer.seekToPrevious()
            }
        )
    }

    val triggerSeek: (Int) -> Unit = { deltaSec ->
        val current = exoPlayer.currentPosition
        // Пока длительность неизвестна (буферизация/стрим не отдал duration),
        // верхняя граница не должна обнулять позицию.
        val upperBound = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val target = if (deltaSec < 0) {
            (current + deltaSec * 1000L).coerceAtLeast(0L)
        } else {
            (current + deltaSec * 1000L).coerceAtMost(upperBound)
        }
        exoPlayer.seekTo(target)

        val side = if (deltaSec < 0) -1 else 1
        if (seekIndicatorSide != side) {
            seekCumulativeSeconds = deltaSec
            seekIndicatorSide = side
        } else {
            seekCumulativeSeconds += deltaSec
        }
        lastSeekSec = kotlin.math.abs(seekCumulativeSeconds)
        lastSeekSide = side
        seekIndicatorVisible = true

        seekJob?.cancel()
        seekJob = coroutineScope.launch {
            delay(850)
            seekIndicatorVisible = false
            delay(250)
            seekCumulativeSeconds = 0
            seekIndicatorSide = 0
        }
    }
    val currentTriggerSeek by rememberUpdatedState(triggerSeek)
    val currentSeekCumulative by rememberUpdatedState(seekCumulativeSeconds)

    DisposableEffect(exoPlayer, forwardingPlayer, debouncer) {
        dev.metro.anime.ui.player.MediaButtonManager.onMediaKey = { keyEvent ->
            android.util.Log.d("PlayerScreen", "[MediaButtonManager] onMediaKey: keyCode=${keyEvent.keyCode}, action=${keyEvent.action}")
            if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        debouncer.onHeadsetHook()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            playPausePulseIsPlay = false
                            playPausePulseVisible = true
                        } else {
                            exoPlayer.play()
                            ensureAudioAwake()
                            playPausePulseIsPlay = true
                            playPausePulseVisible = true
                        }
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        exoPlayer.play()
                        ensureAudioAwake()
                        playPausePulseIsPlay = true
                        playPausePulseVisible = true
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        exoPlayer.pause()
                        playPausePulseIsPlay = false
                        playPausePulseVisible = true
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        forwardingPlayer.seekToNext()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        forwardingPlayer.seekToPrevious()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_STOP -> {
                        exoPlayer.stop()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        onDispose {
            dev.metro.anime.ui.player.MediaButtonManager.onMediaKey = null
            debouncer.cancel()
            seekJob?.cancel()
        }
    }

    val mediaSession = remember(forwardingPlayer) {
        MediaSession.Builder(context, forwardingPlayer)
            .setId("metro_anime_player_session")
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    val keyEvent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
                    }
                    android.util.Log.d("PlayerScreen", "[MediaSession] onMediaButtonEvent: keyEvent=$keyEvent")
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                debouncer.onHeadsetHook()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                    ensureAudioAwake()
                                }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                exoPlayer.play()
                                ensureAudioAwake()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                exoPlayer.pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                forwardingPlayer.seekToNext()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                forwardingPlayer.seekToPrevious()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                                exoPlayer.stop()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            })
            .build()
    }

    DisposableEffect(mediaSession) {
        onDispose {
            mediaSession.release()
        }
    }

    var effectsApplied by remember { mutableStateOf(false) }
    LaunchedEffect(isUpscaleEnabled) {
        playerPrefs.edit().putBoolean("upscale_enabled", isUpscaleEnabled).apply()
        try {
            if (isUpscaleEnabled) {
                exoPlayer.setVideoEffects(listOf(dev.metro.anime.ui.player.AnimeUpscaleGlEffect(0.75f)))
                effectsApplied = true
            } else if (effectsApplied) {
                exoPlayer.setVideoEffects(emptyList())
                effectsApplied = false
            }
        } catch (e: Throwable) {
            android.util.Log.e("PlayerScreen", "Error setting video effects", e)
        }
    }

    // Resolve streams
    LaunchedEffect(episode, source) {
        // Гасим предыдущий фоновый резолв (он мог остаться от прошлой серии)
        backgroundResolveJob?.cancel()
        backgroundResolveJob = null
        isResolvingStreams = true
        resolveError = null
        currentPositionMs = 0L
        durationMs = 0L
        isPlaybackEnded = false
        currentLoadedUrl = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        try {
            val offline = if (dev.metro.anime.data.download.DownloadManager.isInitialized()) {
                dev.metro.anime.data.download.DownloadManager.getDownloaded(currentAnime.id, episode.ordinal)
            } else null
            offlineDownload = offline
            android.util.Log.d(
                "PlayerScreen",
                "resolve: anime=${currentAnime.id} ord=${episode.ordinal} offline=${offline?.fileUri} source=$source"
            )
            if (offline != null && offline.fileUri.isNotBlank()) {
                // Скачанная серия играется по умолчанию, но онлайн-качества подгружаем
                // в фоне, чтобы можно было переключиться на онлайн-источник.
                streamsState = Pair(emptyMap(), dev.metro.anime.data.model.OFFLINE_STREAM_KEY)
                isResolvingStreams = false
                // Резолв привязан к текущей серии: coroutineScope переживает смену episode,
                // и без проверки старый ответ подставлял бы URL чужой серии.
                val episodeOrdinal = episode.ordinal
                backgroundResolveJob = coroutineScope.launch {
                    val online = try {
                        repository.resolveEpisodeStream(episode, source)
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    if (online.isNotEmpty() && currentEpisode.ordinal == episodeOrdinal) {
                        streamsState = Pair(
                            online.filterKeys { it != dev.metro.anime.data.model.OFFLINE_STREAM_KEY },
                            streamsState.second
                        )
                    }
                }
                return@LaunchedEffect
            }
            val resolved = repository.resolveEpisodeStream(episode, source)
            android.util.Log.d("PlayerScreen", "resolve online keys=${resolved.keys} url=${resolved.values.firstOrNull()}")
            if (resolved.isNotEmpty()) {
                val quality = when {
                    resolved.containsKey("1080") -> "1080"
                    resolved.containsKey("720") -> "720"
                    resolved.containsKey("480") -> "480"
                    else -> resolved.keys.first()
                }
                streamsState = Pair(resolved, quality)
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
    LaunchedEffect(streamsState, episode) {
        // Пока идёт резолв текущей серии, нельзя отдавать URL предыдущей серии
        // (streamsState обновляется только после резолва).
        if (isResolvingStreams) return@LaunchedEffect
        val streamUrl = if (selectedQuality == dev.metro.anime.data.model.OFFLINE_STREAM_KEY) {
            offlineDownload?.fileUri
        } else {
            streams[selectedQuality] ?: streams.values.firstOrNull()
        }
        if (streamUrl != null && streamUrl != currentLoadedUrl) {
            currentLoadedUrl = streamUrl
            val targetSeek = if (seekTargetInitialMs > 0) {
                seekTargetInitialMs
            } else {
                exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            // Сохраняем состояние воспроизведения: смена качества на паузе не должна
            // запускать видео.
            val wasPlaying = exoPlayer.playWhenReady
            android.util.Log.d("PlayerScreen", "feed url=$streamUrl seek=$targetSeek")
            val metaBuilder = MediaMetadata.Builder()
                .setTitle(currentAnime.titleRu.ifBlank { currentAnime.titleOrig ?: "" })
                .setSubtitle("Серия ${currentEpisode.ordinal}${if (!currentEpisode.name.isNullOrBlank()) " — " + currentEpisode.name else ""}")
                .setArtist(currentDubbingTitle.ifBlank { currentSource.label })
            if (currentAnime.posterUrl.isNotBlank()) {
                metaBuilder.setArtworkUri(Uri.parse(currentAnime.posterUrl))
            }
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(streamUrl))
                .setMediaMetadata(metaBuilder.build())
                .build()
            exoPlayer.setMediaItem(mediaItem, targetSeek)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = wasPlaying
        }
    }

    // Position tracking loop
    LaunchedEffect(exoPlayer, episode) {
        var lastSavedSec = 0L
        var lastLogAt = 0L
        while (isActive) {
            val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
            currentPositionMs = cur
            if (exoPlayer.duration > 0) {
                durationMs = exoPlayer.duration
            }
            val now = System.currentTimeMillis()
            if (now - lastLogAt > 3000) {
                lastLogAt = now
                android.util.Log.d(
                    "PlayerScreen",
                    "tick: state=${exoPlayer.playbackState} playing=${exoPlayer.isPlaying} pos=$cur " +
                        "buffered=${exoPlayer.bufferedPosition} dur=${exoPlayer.duration} url=${currentLoadedUrl}"
                )
            }
            val curSec = cur / 1000
            if (curSec > 3 && (curSec - lastSavedSec >= 5 || curSec < lastSavedSec)) {
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

    BackHandler {
        saveCurrentProgress()
        onBackClick()
    }

    // Cleanup on episode switch or player exit.
    // Серия захватывается в замыкании эффекта: к моменту onDispose rememberUpdatedState
    // уже обновился на новую серию, а позиция принадлежит старой.
    DisposableEffect(episode) {
        val episodeAtEffect = episode
        onDispose {
            saveCurrentProgress(episodeAtEffect)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveCurrentProgress()
            exoPlayer.release()
        }
    }

    // Main Box
    CompositionLocalProvider(LocalHazeStyle provides playerHazeStyle) {
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
                // PlayerView держит ссылку на плеер: отсоединяем его ДО release(),
                // иначе при выходе из плеера возможен «Player accessed after release».
                onRelease = { view ->
                    view.player = null
                    playerViewRef = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
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
                                currentTriggerSeek(-10)
                            },
                            onTap = {
                                if (currentSeekCumulative != 0) {
                                    currentTriggerSeek(-10)
                                } else if (!isControlsLocked) {
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
                                    // tryAwaitRelease() бросает исключение при отмене жеста
                                    // (палец вне зоны, свайп по родителю) — без finally
                                    // скорость 1.5x «залипала» навсегда.
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        isHoldingLeft = false
                                        exoPlayer.setPlaybackSpeed(baseSpeed)
                                    }
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
                                        ensureAudioAwake()
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
                                currentTriggerSeek(10)
                            },
                            onTap = {
                                if (currentSeekCumulative != 0) {
                                    currentTriggerSeek(10)
                                } else if (!isControlsLocked) {
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
                                    // finally обязателен: отмена жеста бросает исключение,
                                    // иначе скорость 2.0x «залипает» навсегда.
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        isHoldingRight = false
                                        exoPlayer.setPlaybackSpeed(baseSpeed)
                                    }
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
                    .playerHaze(hazeState)
                    .background(hudPillGlass)
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
        var lastBoostText by remember { mutableStateOf("2.0x") }
        if (isHoldingLeft) {
            lastBoostText = "1.5x"
        } else if (isHoldingRight) {
            lastBoostText = "2.0x"
        }

        AnimatedVisibility(
            visible = isHoldingLeft || isHoldingRight,
            enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.85f, animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .playerHaze(hazeState)
                    .background(hudPillGlass)
                    .border(1.dp, hudBorder, RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "УСКОРЕНИЕ $lastBoostText",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White,
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
        // HUD: Upscale Banner (CAS)
        // =====================================================================
        LaunchedEffect(upscaleHudVisible) {
            if (upscaleHudVisible) {
                delay(1200)
                upscaleHudVisible = false
            }
        }
        var lastUpscaleEnabled by remember { mutableStateOf(isUpscaleEnabled) }
        if (upscaleHudVisible) {
            lastUpscaleEnabled = isUpscaleEnabled
        }

        AnimatedVisibility(
            visible = upscaleHudVisible,
            enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.85f, animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .playerHaze(hazeState)
                    .background(hudPillGlass)
                    .border(1.dp, if (lastUpscaleEnabled) scheme.accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = if (lastUpscaleEnabled) scheme.accent else scheme.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (lastUpscaleEnabled) "AI-АПСКЕЙЛ: CAS ВЫСОКИЙ (ВКЛ)" else "AI-АПСКЕЙЛ: ВЫКЛЮЧЕН",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (lastUpscaleEnabled) scheme.accent else scheme.textDim,
                    )
                }
            }
        }

        // =====================================================================
        // HUD: Сообщение о загрузке (скачивание в галерею)
        // =====================================================================
        LaunchedEffect(downloadHudVisible) {
            if (downloadHudVisible) {
                delay(2600)
                downloadHudVisible = false
            }
        }
        var lastDownloadHudText by remember { mutableStateOf<String?>(null) }
        if (downloadHudVisible) {
            lastDownloadHudText = downloadHudText
        }
        AnimatedVisibility(
            visible = downloadHudVisible,
            enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.9f, animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.9f, animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .playerHaze(hazeState)
                    .background(hudPillGlass)
                    .border(1.dp, hudBorder, RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = lastDownloadHudText ?: "",
                    fontFamily = MetroFonts.headline,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = scheme.text,
                )
            }
        }

        // =====================================================================
        // HUD: Seek Indicator (-10s / +10s)
        // =====================================================================
        AnimatedVisibility(
            visible = seekIndicatorVisible,
            enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.85f, animationSpec = tween(200)),
            modifier = Modifier.align(
                if (lastSeekSide < 0) Alignment.CenterStart else Alignment.CenterEnd
            ).padding(horizontal = 48.dp),
        ) {
            val displayText = if (lastSeekSide < 0) "-$lastSeekSec сек" else "+$lastSeekSec сек"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radius))
                    .playerHaze(hazeState)
                    .background(hudPillGlass)
                    .border(1.dp, hudBorder, RoundedCornerShape(MetroDimens.radius))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (lastSeekSide < 0) {
                        Icon(
                            imageVector = Icons.Default.FastRewind,
                            contentDescription = null,
                            tint = scheme.accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        text = displayText,
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = scheme.text,
                    )
                    if (lastSeekSide > 0) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = null,
                            tint = scheme.accent,
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
        val op = activeOpening
        val ed = activeEnding
        val isOp = op != null && curSec in op.startSec..op.endSec
        val isEd = ed != null && curSec in ed.startSec..ed.endSec

        val showSkip = isOp || isEd
        val currentSkipTargetSec = when {
            isOp -> op.endSec
            isEd -> ed.endSec
            else -> null
        }
        val currentIsEdNext = isEd && nextEpisode != null
        val currentSkipLabel = when {
            isOp -> "Пропустить опенинг"
            currentIsEdNext -> "Следующая серия"
            isEd -> "Пропустить эндинг"
            else -> "Пропустить заставку"
        }

        var lastSkipLabel by remember { mutableStateOf("Пропустить опенинг") }
        var lastSkipTargetSec by remember { mutableStateOf<Int?>(null) }
        var lastIsEdNext by remember { mutableStateOf(false) }

        if (showSkip) {
            lastSkipLabel = currentSkipLabel
            lastSkipTargetSec = currentSkipTargetSec
            lastIsEdNext = currentIsEdNext
        }

        AnimatedVisibility(
            visible = showSkip && (currentSkipTargetSec != null || currentIsEdNext) && !isControlsLocked,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.85f, animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(250)) + scaleOut(targetScale = 0.85f, animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 76.dp, end = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .playerHaze(hazeState)
                    .background(hudPillGlass)
                    .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.radiusSmall))
                    .metroClickable {
                        if (lastIsEdNext && nextEpisode != null) {
                            switchToEpisode(nextEpisode)
                        } else if (lastSkipTargetSec != null) {
                            exoPlayer.seekTo(lastSkipTargetSec!! * 1000L)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lastSkipLabel,
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

        // =====================================================================
        // Lock Screen Unlock Button (When controls are locked)
        // =====================================================================
        AnimatedVisibility(
            visible = isControlsLocked,
            enter = fadeIn(animationSpec = tween(150)) + scaleIn(initialScale = 0.85f, animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.85f, animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 20.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .playerHaze(hazeState)
                    .background(hudPillGlass)
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
                                Color.Black.copy(alpha = 0.35f * playerOpacity),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.45f * playerOpacity),
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
                            .playerHaze(hazeState)
                            .background(hudPillGlass)
                            .border(1.dp, hudBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            saveCurrentProgress()
                            onBackClick()
                        }) {
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
                            .playerHaze(hazeState)
                            .background(hudPillGlass)
                            .border(1.dp, hudBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            enabled = prevEpisode != null,
                            onClick = {
                                if (prevEpisode != null) {
                                    switchToEpisode(prevEpisode)
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
                            Text(
                                text = "${episode.ordinal} Серия",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = scheme.text,
                            )
                            if (episode.isFiller) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFFF9800).copy(alpha = 0.25f))
                                        .border(1.dp, Color(0xFFFF9800).copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
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

                        IconButton(
                            enabled = nextEpisode != null,
                            onClick = {
                                if (nextEpisode != null) {
                                    switchToEpisode(nextEpisode)
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

                // RIGHT PILL: [Upscale] [Settings] [Lock]
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(12.dp))
                        .playerHaze(hazeState)
                        .background(hudPillGlass)
                        .border(1.dp, hudBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                )
 {
                        IconButton(onClick = {
                            isUpscaleEnabled = !isUpscaleEnabled
                            upscaleHudVisible = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = "AI-Апскейл",
                                tint = if (isUpscaleEnabled) scheme.accent else scheme.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }

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
                    enter = fadeIn(animationSpec = tween(180)) +
                            scaleIn(initialScale = 0.90f, animationSpec = tween(180)) +
                            slideInVertically(animationSpec = tween(180)) { -it / 6 },
                    exit = fadeOut(animationSpec = tween(150)) +
                            scaleOut(targetScale = 0.90f, animationSpec = tween(150)) +
                            slideOutVertically(animationSpec = tween(150)) { -it / 6 },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 70.dp, start = 20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .playerHaze(hazeState)
                            .background(hudCardGlass)
                            .border(1.dp, hudBorder, RoundedCornerShape(12.dp))
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
                            val formattedSpeed = if ((baseSpeed * 10).roundToInt() % 10 == 0) {
                                String.format(java.util.Locale.US, "%.1fx", baseSpeed)
                            } else {
                                String.format(java.util.Locale.US, "%.2fx", baseSpeed)
                            }
                            Text(
                                text = formattedSpeed,
                                fontFamily = MetroFonts.text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = scheme.accent,
                                modifier = Modifier.metroClickable {
                                    baseSpeed = 1.0f
                                    exoPlayer.setPlaybackSpeed(1.0f)
                                },
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = baseSpeed,
                            onValueChange = {
                                val raw = (it * 20).roundToInt() / 20f
                                val snapped = if (raw in 0.95f..1.05f) 1.0f else raw.coerceIn(0.25f, 3.0f)
                                baseSpeed = snapped
                                exoPlayer.setPlaybackSpeed(snapped)
                            },
                            valueRange = 0.25f..3.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = scheme.accent,
                                activeTrackColor = scheme.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }

                // -------------------------------------------------------------
                // Settings Popup Card (Under right pill)
                // -------------------------------------------------------------
                AnimatedVisibility(
                    visible = showSettingsDialog,
                    enter = fadeIn(animationSpec = tween(180)) +
                            scaleIn(initialScale = 0.90f, animationSpec = tween(180)) +
                            slideInVertically(animationSpec = tween(180)) { -it / 6 },
                    exit = fadeOut(animationSpec = tween(150)) +
                            scaleOut(targetScale = 0.90f, animationSpec = tween(150)) +
                            slideOutVertically(animationSpec = tween(150)) { -it / 6 },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 70.dp, end = 20.dp),
                ) {
                    val settingsScrollState = rememberScrollState()
                    val configuration = LocalConfiguration.current
                    val maxMenuHeight = (configuration.screenHeightDp.dp - 150.dp).coerceAtLeast(160.dp)
                    Column(
                        modifier = Modifier
                            .width(280.dp)
                            .heightIn(max = maxMenuHeight)
                            .clip(RoundedCornerShape(12.dp))
                            .playerHaze(hazeState)
                            .background(hudCardGlass)
                            .border(1.dp, hudBorder, RoundedCornerShape(12.dp))
                            .verticalScroll(settingsScrollState)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
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

                        Spacer(modifier = Modifier.height(8.dp))

                        // --- Секция «Скачано» ---
                        if (offlineDownload != null) {
                            Text(
                                text = "Скачано",
                                fontFamily = MetroFonts.text,
                                fontSize = 11.sp,
                                color = scheme.textDim,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val offlineLabel = dev.metro.anime.data.model.qualityLabel(offlineDownload?.quality ?: "720")
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (selectedQuality == dev.metro.anime.data.model.OFFLINE_STREAM_KEY) scheme.accent
                                            else scheme.glass
                                        )
                                        .border(
                                            1.dp,
                                            if (selectedQuality == dev.metro.anime.data.model.OFFLINE_STREAM_KEY) scheme.accent
                                            else scheme.stroke,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .metroClickable {
                                            updateQuality(dev.metro.anime.data.model.OFFLINE_STREAM_KEY)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = offlineLabel,
                                        fontFamily = MetroFonts.headline,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = if (selectedQuality == dev.metro.anime.data.model.OFFLINE_STREAM_KEY) Color.White else scheme.text,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // --- Секция «Онлайн» ---
                        if (streams.isNotEmpty()) {
                            Text(
                                text = "Онлайн",
                                fontFamily = MetroFonts.text,
                                fontSize = 11.sp,
                                color = scheme.textDim,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                streams.keys.sortedDescending().forEach { q ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (selectedQuality == q) scheme.accent else scheme.glass)
                                            .border(1.dp, if (selectedQuality == q) scheme.accent else scheme.stroke, RoundedCornerShape(6.dp))
                                            .metroClickable { updateQuality(q) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = dev.metro.anime.data.model.qualityLabel(q),
                                            fontFamily = MetroFonts.headline,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.sp,
                                            color = if (selectedQuality == q) Color.White else scheme.text,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Скачать в галерею (текущая серия) / прогресс текущей загрузки
                        val activeTask = currentDownloadTask
                        val taskRunning = activeTask != null && activeTask.status.isActive
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.glass)
                                .border(1.dp, scheme.stroke, RoundedCornerShape(8.dp))
                                .metroClickable {
                                    if (taskRunning) {
                                        // Повторное нажатие — отмена загрузки
                                        DownloadManager.cancel(anime.id, episode.ordinal)
                                        downloadHudText = "Скачивание отменено"
                                        downloadHudVisible = true
                                        coroutineScope.launch {
                                            delay(2000)
                                            downloadHudVisible = false
                                        }
                                        return@metroClickable
                                    }
                                    if (downloadRequestInFlight) return@metroClickable
                                    downloadRequestInFlight = true
                                    // Разрешение на уведомления (Android 13+): без него
                                    // не видно прогресса загрузки в шторке.
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.POST_NOTIFICATIONS,
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationsPermissionLauncher.launch(
                                            android.Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        storagePermissionLauncher.launch(
                                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        )
                                    }
                                    coroutineScope.launch {
                                        val result = runCatching {
                                            // Берём уже готовые ссылки, если они есть (иначе резолвим)
                                            val online = streams.filterKeys { it != dev.metro.anime.data.model.OFFLINE_STREAM_KEY }
                                            val resolved = if (online.isNotEmpty()) {
                                                online
                                            } else {
                                                repository.resolveEpisodeStream(episode, source)
                                                    .filterKeys { it != dev.metro.anime.data.model.OFFLINE_STREAM_KEY }
                                            }
                                            val quality = selectedQuality.takeIf { resolved.containsKey(it) }
                                                ?: resolved.keys.maxByOrNull { it.toIntOrNull() ?: 0 }
                                            val url = quality?.let { resolved[it] }
                                            if (url == null) {
                                                "Не удалось получить ссылку на серию"
                                            } else {
                                                val accepted = DownloadManager.enqueue(
                                                    anime = anime.sanitized(),
                                                    episode = episode,
                                                    dubbingTitle = dubbingTitle,
                                                    source = source,
                                                    quality = quality ?: "720",
                                                    streamUrl = url,
                                                    durationSec = episode.durationSec,
                                                )
                                                if (accepted) {
                                                    "Скачивание началось"
                                                } else {
                                                    "Серия уже скачана или уже в очереди"
                                                }
                                            }
                                        }.getOrElse { "Ошибка: ${it.message}" }
                                        downloadHudText = result
                                        downloadHudVisible = true
                                        delay(2500)
                                        downloadHudVisible = false
                                        downloadRequestInFlight = false
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        activeTask?.status == dev.metro.anime.data.model.DownloadStatus.REMUXING ->
                                            "Обработка файла…"
                                        taskRunning -> "Скачивание… ${((activeTask?.progress ?: 0f) * 100).toInt()}%"
                                        activeTask?.status == dev.metro.anime.data.model.DownloadStatus.COMPLETED ->
                                            "Уже скачано в галерею"
                                        activeTask?.status == dev.metro.anime.data.model.DownloadStatus.FAILED ->
                                            "Ошибка загрузки"
                                        else -> "Скачать в галерею"
                                    },
                                    fontFamily = MetroFonts.headline,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = if (taskRunning) scheme.accent else scheme.text,
                                )
                                Text(
                                    text = when {
                                        taskRunning && (activeTask?.downloadedBytes ?: 0L) > 0L ->
                                            "${formatDownloadBytes(activeTask?.downloadedBytes ?: 0L)} • нажмите, чтобы отменить"
                                        taskRunning -> "Нажмите, чтобы отменить"
                                        else -> "Текущая серия в MP4"
                                    },
                                    fontFamily = MetroFonts.text,
                                    fontSize = 10.sp,
                                    color = scheme.textDim,
                                )
                            }
                            if (taskRunning) {
                                // Кольцевой индикатор прогресса
                                Box(
                                    modifier = Modifier.size(20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        progress = { activeTask?.progress ?: 0f },
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = scheme.accent,
                                        trackColor = scheme.stroke,
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Download,
                                    contentDescription = null,
                                    tint = scheme.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        // Полоса прогресса загрузки (видна, пока идёт скачивание)
                        if (taskRunning) {
                            Spacer(modifier = Modifier.height(4.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { currentDownloadTask?.progress ?: 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = scheme.accent,
                                trackColor = scheme.stroke,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // AI Upscale Switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isUpscaleEnabled) scheme.accent.copy(alpha = 0.15f) else scheme.glass)
                                .border(1.dp, if (isUpscaleEnabled) scheme.accent else scheme.stroke, RoundedCornerShape(8.dp))
                                .metroClickable {
                                    isUpscaleEnabled = !isUpscaleEnabled
                                    upscaleHudVisible = true
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI-Апскейл (CAS)",
                                    fontFamily = MetroFonts.headline,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = if (isUpscaleEnabled) scheme.accent else scheme.text,
                                )
                                Text(
                                    text = "Четкость контуров без лагов",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 10.sp,
                                    color = scheme.textDim,
                                )
                            }
                            Switch(
                                checked = isUpscaleEnabled,
                                onCheckedChange = {
                                    isUpscaleEnabled = it
                                    upscaleHudVisible = true
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = scheme.accent,
                                    checkedTrackColor = scheme.accent.copy(alpha = 0.35f),
                                ),
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Player UI Transparency Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Прозрачность UI:",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = scheme.text,
                            )
                            val transparencyPct = ((1f - playerOpacity) * 100).toInt()
                            Text(
                                text = "$transparencyPct%",
                                fontFamily = MetroFonts.text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = scheme.accent,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Slider(
                            value = 1f - playerOpacity,
                            onValueChange = {
                                settingsRepo.setPlayerControlsOpacity(1f - it)
                            },
                            valueRange = 0.15f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = scheme.accent,
                                activeTrackColor = scheme.accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                            ),
                        )

                        // Player Blur Toggle & Slider
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Акриловый блюр",
                                fontFamily = MetroFonts.headline,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = scheme.text,
                            )
                            Switch(
                                checked = currentSettings.playerBlurEnabled,
                                onCheckedChange = { settingsRepo.setPlayerBlurEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = scheme.accent,
                                    checkedTrackColor = scheme.glassHover,
                                    uncheckedThumbColor = scheme.textDim,
                                    uncheckedTrackColor = scheme.glass,
                                ),
                            )
                        }

                        if (currentSettings.playerBlurEnabled) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Сила блюра",
                                    fontFamily = MetroFonts.text,
                                    fontSize = 11.sp,
                                    color = scheme.textDim,
                                )
                                Text(
                                    text = "${currentSettings.playerBlurRadius} dp",
                                    fontFamily = MetroFonts.text,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = scheme.accent,
                                )
                            }
                            Slider(
                                value = currentSettings.playerBlurRadius.toFloat(),
                                onValueChange = { settingsRepo.setPlayerBlurRadius(it.roundToInt()) },
                                valueRange = 4f..48f,
                                colors = SliderDefaults.colors(
                                    thumbColor = scheme.accent,
                                    activeTrackColor = scheme.accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                                ),
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Источник: ${source.label}",
                            fontFamily = MetroFonts.text,
                            fontSize = 11.sp,
                            color = scheme.textDim,
                        )
                        if (dubbingTitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Озвучка: $dubbingTitle",
                                fontFamily = MetroFonts.text,
                                fontSize = 10.sp,
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
                        .playerHaze(hazeState)
                        .background(hudPillGlass)
                        .border(1.dp, hudBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Play / Pause Glass Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.accent.copy(alpha = 0.16f))
                                .border(1.dp, scheme.accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                .metroClickable {
                                    if (isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        exoPlayer.play()
                                        ensureAudioAwake()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Пауза" else "Играть",
                                tint = scheme.accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // Current Time
                        Text(
                            text = formatTime(currentPositionMs),
                            fontFamily = MetroFonts.headline,
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
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
