package dev.metro.anime

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import dev.metro.anime.data.AutoUpdateManager
import dev.metro.anime.data.UpdateRepository
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.data.settings.MetroSettingsRepository
import dev.metro.anime.ui.screens.DetailsScreen
import dev.metro.anime.ui.screens.HomeScreen
import dev.metro.anime.ui.screens.PlayerScreen
import dev.metro.anime.ui.screens.SettingsScreen
import dev.metro.anime.ui.theme.MetroScheme
import dev.metro.anime.ui.theme.MetroTheme

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Details(val anime: AnimeTitle) : Screen
    data class Player(
        val anime: AnimeTitle,
        val episode: AnimeEpisode,
        val source: AnimeSource,
        val dubbingTitle: String = "",
        val initialPositionMs: Long = 0L,
        val allEpisodes: List<AnimeEpisode> = emptyList(),
    ) : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var repository: AnimeRepository
    private lateinit var settingsRepository: MetroSettingsRepository
    private lateinit var updateRepository: UpdateRepository
    private var openUpdatesRequest = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_updates", false)) {
            openUpdatesRequest.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = AnimeRepository(applicationContext)
        settingsRepository = MetroSettingsRepository(applicationContext)
        updateRepository = UpdateRepository(applicationContext)
        dev.metro.anime.data.AutoUpdateNotificationHelper.createNotificationChannel(applicationContext)

        if (intent?.getBooleanExtra("open_updates", false) == true) {
            openUpdatesRequest.value = true
        }

        setContent {
            val settings by settingsRepository.settings.collectAsState()
            val wallpaper by settingsRepository.wallpaper.collectAsState()

            val customScheme = remember(settings.accentColor, wallpaper) {
                MetroScheme(
                    accent = Color(settings.accentColor),
                    background = if (wallpaper != null) Color.Transparent else Color(0xFF0A0A0D),
                )
            }

            val openUpdates by openUpdatesRequest
            var currentScreen by remember {
                mutableStateOf<Screen>(if (openUpdates) Screen.Settings else Screen.Home)
            }

            LaunchedEffect(openUpdates) {
                if (openUpdates) {
                    currentScreen = Screen.Settings
                    openUpdatesRequest.value = false
                }
            }

            LaunchedEffect(settings.autoUpdateIntervalMinutes) {
                if (settings.autoUpdateIntervalMinutes > 0) {
                    AutoUpdateManager.schedule(applicationContext, settings.autoUpdateIntervalMinutes)
                }
            }

            MetroTheme(scheme = customScheme) {
                CompositionLocalProvider(
                    dev.metro.anime.ui.theme.LocalBlurredWallpaper provides (if (settings.blurEnabled) wallpaper?.blurred else null),
                ) {
                BackHandler(enabled = currentScreen !is Screen.Home) {
                    currentScreen = when (val s = currentScreen) {
                        is Screen.Player -> Screen.Details(s.anime)
                        is Screen.Details -> Screen.Home
                        is Screen.Settings -> Screen.Home
                        is Screen.Home -> Screen.Home
                    }
                }

                LaunchedEffect(currentScreen) {
                    if (currentScreen !is Screen.Player) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0D)),
                ) {
                    // Wallpaper rendering (Sharp or Blurred with Dimming Overlay)
                    val wp = wallpaper
                    if (wp != null && currentScreen !is Screen.Player) {
                        val bmp = if (settings.blurEnabled) wp.blurred else wp.sharp
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = settings.backgroundDim)),
                        )
                    }

                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen_transition",
                        modifier = Modifier.fillMaxSize(),
                    ) { screen ->
                        when (screen) {
                            is Screen.Home -> {
                                HomeScreen(
                                    repository = repository,
                                    onAnimeClick = { anime ->
                                        currentScreen = Screen.Details(anime)
                                    },
                                    onOpenSettings = {
                                        currentScreen = Screen.Settings
                                    },
                                )
                            }

                            is Screen.Settings -> {
                                SettingsScreen(
                                    settingsRepo = settingsRepository,
                                    updateRepo = updateRepository,
                                    animeRepo = repository,
                                    onBackClick = {
                                        currentScreen = Screen.Home
                                    },
                                )
                            }

                            is Screen.Details -> {
                                DetailsScreen(
                                    anime = screen.anime,
                                    repository = repository,
                                    onBackClick = {
                                        currentScreen = Screen.Home
                                    },
                                    onPlayEpisode = { episode, source, title, dubbingTitle, initialPos, allEps ->
                                        currentScreen = Screen.Player(
                                            anime = title,
                                            episode = episode,
                                            source = source,
                                            dubbingTitle = dubbingTitle,
                                            initialPositionMs = initialPos,
                                            allEpisodes = allEps,
                                        )
                                    },
                                )
                            }

                            is Screen.Player -> {
                                PlayerScreen(
                                    anime = screen.anime,
                                    episode = screen.episode,
                                    source = screen.source,
                                    repository = repository,
                                    dubbingTitle = screen.dubbingTitle,
                                    initialPositionMs = screen.initialPositionMs,
                                    allEpisodes = screen.allEpisodes,
                                    onBackClick = {
                                        currentScreen = Screen.Details(screen.anime)
                                    },
                                    onNextEpisodeClick = { nextEp ->
                                        currentScreen = screen.copy(
                                            episode = nextEp,
                                            initialPositionMs = 0L,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}
