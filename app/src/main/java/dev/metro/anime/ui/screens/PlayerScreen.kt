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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.data.settings.MetroSettingsRepository
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
    val settingsRepo = remember { MetroSettingsRepository(context) }
    val currentSettings by settingsRepo.settings.collectAsState()
    val playerOpacity = currentSettings.playerControlsOpacity
    val hudPillGlass = Color(0xFF101016).copy(alpha = playerOpacity)
    val hudCardGlass = Color(0xFF101016).copy(alpha = (playerOpacity * 1.25f).coerceIn(0.12f, 0.95f))
    val hudBorder = Color.White.copy(alpha = (playerOpacity * 0.35f).coerceIn(0.05f, 0.25f))

    val hazeState = remember { HazeState() }
    val playerHazeStyle = remember {
        HazeDefaults.style(
            backgroundColor = Color.Transparent,
            blurRadius = 24.dp,
        )
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
    fun updateQuality(newQuality: String) {
        streamsState = Pair(streamsState.first, newQuality)
    }
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

    val currentEpisode by rememberUpdatedState(episode)
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

    fun saveCurrentProgress() {
        val ep = currentEpisode
        val pos = currentPositionMs
        val dur = durationMs
        if (pos > 3000L && dur > 10000L) {
            val ed = activeEnding
            val isNearEndOrEnding = when {
                ed != null && pos >= (ed.startSec - 10) * 1000L -> true
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

                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = (state == Player.STATE_BUFFERING)
                    isPlaybackEnded = (state == Player.STATE_ENDED)
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

    var currentLoadedUrl by remember(episode.ordinal) { mutableStateOf<String?>(null) }

    // Resolve streams
    LaunchedEffect(episode, source) {
        isResolvingStreams = true
        resolveError = null
        currentPositionMs = 0L
        durationMs = 0L
        isPlaybackEnded = false
        currentLoadedUrl = null
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        try {
            val resolved = repository.resolveEpisodeStream(episode, source)
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
        val streamUrl = streamsState.first[streamsState.second] ?: streamsState.first.values.firstOrNull()
        if (streamUrl != null && streamUrl != currentLoadedUrl) {
            currentLoadedUrl = streamUrl
            val targetSeek = if (seekTargetInitialMs > 0) {
                seekTargetInitialMs
            } else {
                exoPlayer.currentPosition.coerceAtLeast(0L)
            }
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
            exoPlayer.play()
        }
    }

    // Position tracking loop
    LaunchedEffect(exoPlayer, episode) {
        var lastSavedSec = 0L
        while (isActive) {
            val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
            currentPositionMs = cur
            if (exoPlayer.duration > 0) {
                durationMs = exoPlayer.duration
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

    // Cleanup on episode switch or player exit
    DisposableEffect(episode) {
        onDispose {
            saveCurrentProgress()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
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
                    .hazeEffect(state = hazeState) {
                        backgroundColor = Color.Transparent
                        blurRadius = 24.dp
                    }
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
                    .hazeEffect(state = hazeState) {
                        backgroundColor = Color.Transparent
                        blurRadius = 24.dp
                    }
                    .background(hudPillGlass)
                    .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.radiusSmall))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            )
 {
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
        // HUD: Upscale Banner (CAS)
        // =====================================================================
        LaunchedEffect(upscaleHudVisible) {
            if (upscaleHudVisible) {
                delay(1200)
                upscaleHudVisible = false
            }
        }
        AnimatedVisibility(
            visible = upscaleHudVisible,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .hazeEffect(state = hazeState) {
                        backgroundColor = Color.Transparent
                        blurRadius = 24.dp
                    }
                    .background(hudPillGlass)
                    .border(1.dp, if (isUpscaleEnabled) scheme.accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(MetroDimens.radiusSmall))
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
                        tint = if (isUpscaleEnabled) scheme.accent else scheme.textDim,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isUpscaleEnabled) "AI-АПСКЕЙЛ: CAS ВЫСОКИЙ (ВКЛ)" else "AI-АПСКЕЙЛ: ВЫКЛЮЧЕН",
                        fontFamily = MetroFonts.headline,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = if (isUpscaleEnabled) scheme.accent else scheme.textDim,
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
                    .hazeEffect(state = hazeState) {
                        backgroundColor = Color.Transparent
                        blurRadius = 24.dp
                    }
                    .background(hudPillGlass)
                    .border(1.dp, scheme.accent.copy(alpha = 0.6f), RoundedCornerShape(MetroDimens.radius))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            )
 {
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
        val op = activeOpening
        val ed = activeEnding
        val isOp = op != null && curSec in op.startSec..op.endSec
        val isEd = ed != null && curSec in ed.startSec..ed.endSec

        val showSkip = isOp || isEd
        val skipTargetSec = when {
            isOp -> op?.endSec
            isEd -> ed?.endSec
            else -> null
        }
        val skipLabel = when {
            isOp -> "Пропустить опенинг"
            isEd && nextEpisode != null -> "Следующая серия"
            isEd -> "Пропустить эндинг"
            else -> "Пропустить заставку"
        }

        AnimatedVisibility(
            visible = showSkip && (skipTargetSec != null || (isEd && nextEpisode != null)) && !isControlsLocked,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 76.dp, end = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MetroDimens.radiusSmall))
                    .hazeEffect(state = hazeState) {
                        backgroundColor = Color.Transparent
                        blurRadius = 24.dp
                    }
                    .background(hudPillGlass)
                    .border(1.dp, scheme.accent, RoundedCornerShape(MetroDimens.radiusSmall))
                    .metroClickable {
                        if (isEd && nextEpisode != null) {
                            switchToEpisode(nextEpisode)
                        } else if (skipTargetSec != null) {
                            exoPlayer.seekTo(skipTargetSec * 1000L)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skipLabel,
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
        if (isControlsLocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 18.dp, end = 20.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .hazeEffect(state = hazeState) {
                        backgroundColor = Color.Transparent
                        blurRadius = 24.dp
                    }
                    .background(hudPillGlass)
                    .border(1.dp, scheme.accent, RoundedCornerShape(10.dp))
                    .metroClickable { isControlsLocked = false },
                contentAlignment = Alignment.Center,
            )
 {
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
                            .hazeEffect(state = hazeState) {
                                backgroundColor = Color.Transparent
                                blurRadius = 24.dp
                            }
                            .background(hudPillGlass)
                            .border(1.dp, hudBorder, RoundedCornerShape(12.dp))
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
                            .hazeEffect(state = hazeState) {
                                backgroundColor = Color.Transparent
                                blurRadius = 24.dp
                            }
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
                        .hazeEffect(state = hazeState) {
                            backgroundColor = Color.Transparent
                            blurRadius = 24.dp
                        }
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
                            .hazeEffect(state = hazeState) {
                                backgroundColor = Color.Transparent
                                blurRadius = 24.dp
                            }
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
                            .hazeEffect(state = hazeState) {
                                backgroundColor = Color.Transparent
                                blurRadius = 24.dp
                            }
                            .background(hudCardGlass)
                            .border(1.dp, hudBorder, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    )
 {
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
                                        .metroClickable { updateQuality(q) }
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
                                .padding(horizontal = 10.dp, vertical = 8.dp),
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

                        Spacer(modifier = Modifier.height(12.dp))

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
                                fontSize = 12.sp,
                                color = scheme.text,
                            )
                            val transparencyPct = ((1f - playerOpacity) * 100).toInt()
                            Text(
                                text = "$transparencyPct%",
                                fontFamily = MetroFonts.text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
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

                        Spacer(modifier = Modifier.height(10.dp))

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
                        .hazeEffect(state = hazeState) {
                            backgroundColor = Color.Transparent
                            blurRadius = 24.dp
                        }
                        .background(hudPillGlass)
                        .border(1.dp, hudBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Play / Pause Button
                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                    ensureAudioAwake()
                                }
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
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
