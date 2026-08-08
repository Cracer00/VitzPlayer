package com.vitz.music.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitz.music.api.PlaylistDetail
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.CatalogRepository

@Composable
fun PlaylistsScreen(
    model: LibraryViewModel,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Новый плейлист")
            }
        },
    ) { padding ->
        if (model.playlists.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Плейлистов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(model.playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(playlist.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(playlist.coverUrl)
                        Column(Modifier.weight(1f)) {
                            Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${playlist.trackCount} трек(ов)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false; title = "" },
            title = { Text("Новый плейлист") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { model.createPlaylist(title); creating = false; title = "" },
                    enabled = title.isNotBlank(),
                ) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false; title = "" }) { Text("Отмена") }
            },
        )
    }
}

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    catalog: CatalogRepository,
    onPlay: (TrackDto, List<TrackDto>) -> Unit,
    onToggleLike: (TrackDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    var detail by remember(playlistId) { mutableStateOf<PlaylistDetail?>(null) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) {
        runCatching { catalog.playlist(playlistId) }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "Плейлист не открылся" }
    }

    val current = detail
    when {
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        current == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        else -> Column(modifier.fillMaxSize()) {
            Text(
                current.playlist.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(current.items, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { onPlay(track, current.items) },
                        onToggleLike = { onToggleLike(track) },
                    )
                }
            }
        }
    }
}
