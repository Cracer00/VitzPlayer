package com.vitz.music.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.vitz.music.app.ui.library.LibraryScreen
import com.vitz.music.app.ui.library.LibraryViewModel
import com.vitz.music.app.ui.library.PlaylistDetailScreen
import com.vitz.music.app.ui.library.PlaylistsScreen
import com.vitz.music.app.ui.library.SearchScreen
import com.vitz.music.app.player.PlayerController
import com.vitz.music.app.ui.player.NowPlayingBar
import com.vitz.music.app.ui.player.PlayerScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("library", "Музыка", Icons.Filled.LibraryMusic),
    Destination("playlists", "Плейлисты", Icons.Filled.PlaylistPlay),
    Destination("search", "Поиск", Icons.Filled.Search),
    Destination("profile", "Профиль", Icons.Filled.Person),
)

@UnstableApi
@Composable
fun MainScreen(
    me: MeResponse,
    app: VitzMusicApp,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    val model: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(app.catalog))
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { PlayerController(context.applicationContext, scope) }
    DisposableEffect(Unit) {
        player.connect()
        onDispose { player.release() }
    }

    val onPlay: (TrackDto, List<TrackDto>) -> Unit = { track, queue -> player.play(track, queue) }

    Scaffold(
        modifier = modifier,
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
                LibraryScreen(model = model, onPlay = onPlay)
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
            composable("search") {
                SearchScreen(model = model, onPlay = onPlay)
            }
            composable("player") {
                PlayerScreen(player = player)
            }
            composable("profile") {
                HomeScreen(me = me, api = app.api, onLogout = onLogout)
            }
        }
    }
}
