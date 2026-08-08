package com.vitz.music.app.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitz.music.api.TrackDto

@Composable
fun LibraryScreen(
    model: LibraryViewModel,
    onPlay: (TrackDto, List<TrackDto>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Догружаем страницу заранее, за пять строк до конца: иначе список спотыкается о паузу.
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= model.tracks.size - 5
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }.collect { if (it) model.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = LibraryTab.entries.indexOf(model.tab)) {
            Tab(
                selected = model.tab == LibraryTab.ALL,
                onClick = { model.switchTab(LibraryTab.ALL) },
                text = { Text("Все треки") },
            )
            Tab(
                selected = model.tab == LibraryTab.LIKED,
                onClick = { model.switchTab(LibraryTab.LIKED) },
                text = { Text("Понравившиеся") },
            )
        }

        model.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (model.tracks.isEmpty() && !model.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (model.tab == LibraryTab.LIKED) "Пока ничего не отмечено" else "Каталог пуст",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(model.tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onClick = { onPlay(track, model.tracks.toList()) },
                    onToggleLike = { model.toggleLike(track) },
                )
            }
            if (model.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
