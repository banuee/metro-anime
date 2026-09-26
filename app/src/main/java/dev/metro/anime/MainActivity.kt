package dev.metro.anime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.metro.anime.data.model.AnimeEpisode
import dev.metro.anime.data.model.AnimeSource
import dev.metro.anime.data.model.AnimeTitle
import dev.metro.anime.data.repository.AnimeRepository
import dev.metro.anime.ui.screens.DetailsScreen
import dev.metro.anime.ui.screens.HomeScreen
import dev.metro.anime.ui.screens.PlayerScreen
import dev.metro.anime.ui.theme.MetroTheme

sealed interface Screen {
    data object Home : Screen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = AnimeRepository(applicationContext)

        setContent {
            MetroTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                BackHandler(enabled = currentScreen !is Screen.Home) {
                    currentScreen = when (val s = currentScreen) {
                        is Screen.Player -> Screen.Details(s.anime)
                        is Screen.Details -> Screen.Home
                        is Screen.Home -> Screen.Home
                    }
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
