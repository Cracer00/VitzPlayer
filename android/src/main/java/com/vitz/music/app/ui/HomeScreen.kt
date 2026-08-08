package com.vitz.music.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitz.music.api.MeResponse
import com.vitz.music.api.Page
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.ApiClient

/**
 * Временный экран второго среза: показывает, что вход состоялся и каталог доступен.
 * На третьем срезе его место займёт библиотека.
 */
@Composable
fun HomeScreen(
    me: MeResponse,
    api: ApiClient,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var catalog by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(me.id) {
        catalog = runCatching {
            val page: Page<TrackDto> = api.get("/api/v1/tracks?limit=1")
            val first = page.items.firstOrNull()
            when {
                page.total == 0L -> "каталог пуст — залейте треки в админке"
                first != null -> "треков: ${page.total}, первый — ${first.artist} — ${first.title}"
                else -> "треков: ${page.total}"
            }
        }.getOrElse { "каталог недоступен: ${it.message}" }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Здравствуйте, ${me.displayName}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${me.email} · ${me.role}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card {
            Text(
                catalog ?: "проверяю каталог…",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onLogout) { Text("Выйти") }
    }
}
