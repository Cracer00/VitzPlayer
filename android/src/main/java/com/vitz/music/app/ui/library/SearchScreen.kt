package com.vitz.music.app.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitz.music.api.TrackDto

@Composable
fun SearchScreen(
    model: LibraryViewModel,
    onPlay: (TrackDto, List<TrackDto>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = model.query,
            onValueChange = model::onQueryChange,
            label = { Text("Исполнитель или название") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        val result = model.searchResult
        when {
            model.query.trim().length < 2 -> Hint("Введите хотя бы два символа")
            result == null -> Hint("Ищу…")
            result.tracks.isEmpty() && result.artists.isEmpty() && result.albums.isEmpty() ->
                Hint("Ничего не нашлось")

            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (result.artists.isNotEmpty()) {
                    item { SectionTitle("Исполнители") }
                    items(result.artists, key = { it.id }) { artist ->
                        Text(
                            "${artist.name} · ${artist.trackCount}",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    item { HorizontalDivider() }
                }
                if (result.tracks.isNotEmpty()) {
                    item { SectionTitle("Треки") }
                    items(result.tracks, key = { it.id }) { track ->
                        TrackRow(
                            track = track,
                            onClick = { onPlay(track, result.tracks) },
                            onToggleLike = { model.toggleLike(track) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
