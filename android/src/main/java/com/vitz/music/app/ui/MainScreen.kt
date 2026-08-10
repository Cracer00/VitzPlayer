package com.vitz.music.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vitz.music.api.MeResponse
import com.vitz.music.api.TrackDto
import com.vitz.music.app.VitzMusicApp
import com.vitz.music.app.ui.downloads.DownloadsScreen
import com.vitz.music.app.ui.library.LibraryScreen
import com.vitz.music.app.ui.library.LibraryViewModel
import com.vitz.music.app.ui.library.PlaylistDetailScreen
import com.vitz.music.app.ui.library.PlaylistsScreen
import com.vitz.music.app.ui.library.SearchScreen
import com.vitz.music.app.player.PlayerController
import com.vitz.music.app.ui.player.NowPlayingBar
import com.vitz.music.app.ui.player.PlayerScreen

/**
 * Полоса «нет связи». Появляется, когда вошли по сохранённому профилю: приложение работает
 * по зеркалу и скачанному, но человек должен понимать, почему не видно нового.
 */
@Composable
private fun OfflineBanner(onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Нет связи с сервером — играет только скачанное",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onRetry) { Text("Ещё раз") }
        }
    }
}

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("library", "Музыка", Icons.Filled.LibraryMusic),
    Destination("playlists", "Плейлисты", Icons.Filled.PlaylistPlay),
    Destination("downloads", "Загрузки", Icons.Filled.Download),
    Destination("search", "Поиск", Icons.Filled.Search),
    Destination("profile", "Профиль", Icons.Filled.Person),
)

@UnstableApi
@Composable
fun MainScreen(
    me: MeResponse,
    app: VitzMusicApp,
    offline: Boolean,
    onRetryConnection: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    val model: LibraryViewModel = viewModel(
        factory = LibraryViewModel.factory(app.catalog, app.downloads),
    )
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        PlayerController(
            context = context.applicationContext,
            scope = scope,
            localPath = { trackId -> app.downloads.localPath(trackId) },
        )
    }
    DisposableEffect(Unit) {
        player.connect()
        onDispose { player.release() }
    }

    val onPlay: (TrackDto, List<TrackDto>) -> Unit = { track, queue -> player.play(track, queue) }

    Scaffold(
        modifier = modifier,
        topBar = { if (offline) OfflineBanner(onRetry = onRetryConnection) },
        bottomBar = {
            Column {
                NowPlayingBar(player = player, onOpen = { nav.navigate("player") })
                NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            nav.navigate(destination.route) {
                                // Вкладки не должны копиться в стеке: возврат уводит на старт.
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "library",
            modifier = Modifier.padding(padding),
        ) {
            composable("library") {
                LibraryScreen(model = model, onPlay = onPlay, playingTrackId = player.currentTrackId)
            }
            composable("playlists") {
                PlaylistsScreen(model = model, onOpen = { id -> nav.navigate("playlist/$id") })
            }
            composable("playlist/{id}") { entry ->
                PlaylistDetailScreen(
                    playlistId = entry.arguments?.getString("id").orEmpty(),
                    catalog = app.catalog,
                    onPlay = onPlay,
                    onToggleLike = model::toggleLike,
                )
            }
            composable("downloads") {
                DownloadsScreen(app = app, onPlay = onPlay)
            }
            composable("search") {
                SearchScreen(model = model, onPlay = onPlay)
            }
            composable("player") {
                PlayerScreen(player = player)
            }
            composable("profile") {
                HomeScreen(
                    me = me,
                    api = app.api,
                    settings = app.settings,
                    updates = app.updates,
                    onLogout = onLogout,
                )
            }
        }
    }
}
