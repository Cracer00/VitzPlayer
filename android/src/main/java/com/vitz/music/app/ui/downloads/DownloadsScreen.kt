package com.vitz.music.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.vitz.music.api.TrackDto
import com.vitz.music.app.VitzMusicApp
import com.vitz.music.app.data.AutoDownloadScope
import com.vitz.music.app.data.local.DownloadRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Скачанное на устройстве.
 *
 * Отдельный экран, а не строчка в настройках: место на планшете кончается, и человек должен
 * видеть, что именно его занимает и как это убрать — по одному треку или всё разом.
 */
@UnstableApi
@Composable
fun DownloadsScreen(
    app: VitzMusicApp,
    onPlay: (TrackDto, List<TrackDto>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<TrackDto>>(emptyList()) }
    var confirmClearAll by remember { mutableStateOf(false) }

    suspend fun refresh() {
        // Файлы могли пропасть мимо приложения — сверяем список с диском, иначе он обещал бы
        // музыку, которой нет.
        app.downloads.reconcile()
        rows = app.downloads.list()
        tracks = app.catalog.tracks(offset = 0, limit = 500, downloadedOnly = true).items
    }

    // Экран открыт, пока идёт автозагрузка: список должен пополняться сам, а не по возврату
    // на вкладку. Первый сигнал — текущее значение счётчика, он же и есть первое чтение.
    LaunchedEffect(Unit) {
        app.catalog.downloadsRevision.collectLatest {
            delay(300)
            refresh()
        }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Удалить все скачанные файлы?") },
            text = { Text("Треки останутся в библиотеке, но без сети играть будет нечего.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    scope.launch {
                        app.downloads.removeAll()
                        refresh()
                    }
                }) { Text("Удалить всё") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Отмена") }
            },
        )
    }

    /*
        Весь экран — один список, а не карточки над списком.

        На экране магнитолы высоты мало: две карточки сверху съедали её почти целиком,
        а список под ними получал нулевой остаток. Прокручивать было нечего — сам список
        пустой высоты, а колонка над ним прокрутки не имеет вовсе.
     */
    LazyColumn(modifier.fillMaxSize()) {
        item {
            SummaryCard(
                app = app,
                rows = rows,
                onClearAll = { confirmClearAll = true },
            )
        }

        item {
            AutoDownloadSettings(app = app, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    "Пока ничего не скачано.\nКнопка загрузки — в списке треков.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        } else {
            items(rows, key = { it.trackId }) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${row.artist} · ${formatBytes(row.sizeBytes)} · ${row.rendition}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            app.downloads.remove(row.trackId)
                            refresh()
                        }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить с устройства")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Сколько занято на устройстве, что делает автозагрузка и кнопка «удалить всё». */
@Composable
private fun SummaryCard(
    app: VitzMusicApp,
    rows: List<DownloadRow>,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("На устройстве", style = MaterialTheme.typography.titleMedium)
            Text(
                "${rows.size} ${plural(rows.size)} · ${formatBytes(rows.sumOf { it.sizeBytes })}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Эти треки играют без интернета. Всё остальное требует связи с сервером.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            app.autoDownloader.let { downloader ->
                when {
                    downloader.running -> {
                        Text(
                            "Скачивается, осталось ${downloader.remaining}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        // Отдельная кнопка, а не только переключатель области: остановить
                        // качалку прямо сейчас нужно чаще, чем менять саму настройку.
                        OutlinedButton(
                            onClick = { downloader.cancel() },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Остановить скачивание") }
                    }

                    downloader.lastSkipReason != null -> Text(
                        downloader.lastSkipReason.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (rows.isNotEmpty()) {
                OutlinedButton(
                    onClick = onClearAll,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Удалить все скачанные") }
            }
        }
    }
}

/**
 * Настройки автозагрузки.
 *
 * Область вынесена в явный выбор, а не спрятана за одним переключателем: «весь каталог» и
 * «только понравившееся» отличаются на порядок по занимаемому месту, и человек должен видеть,
 * на что подписывается.
 */
@Composable
private fun AutoDownloadSettings(app: VitzMusicApp, modifier: Modifier = Modifier) {
    val settings = app.settings

    // «Выключено» обязано останавливать и то, что качается прямо сейчас: настройка, которая
    // начинает действовать только со следующего запуска, читается как неработающая.
    fun select(scope: AutoDownloadScope) {
        settings.updateAutoDownload(scope)
        if (scope == AutoDownloadScope.OFF) app.autoDownloader.cancel()
    }

    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("Скачивать автоматически", style = MaterialTheme.typography.titleMedium)

            AutoDownloadScope.entries.forEach { scope ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.autoDownload == scope,
                            onClick = { select(scope) },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.autoDownload == scope,
                        onClick = { select(scope) },
                    )
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(scope.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            scope.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingSwitch(
                title = "Только по Wi-Fi",
                hint = "Каталог выкачивается дома, в дороге играет уже скачанное",
                checked = settings.wifiOnly,
                onChange = settings::updateWifiOnly,
            )
            SettingSwitch(
                title = "Скачивать оригиналы",
                hint = "Втрое больше места; по Bluetooth разницы не слышно",
                checked = settings.downloadOriginal,
                onChange = settings::updateDownloadOriginal,
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun plural(count: Int): String {
    val tens = count % 100
    if (tens in 11..14) return "треков"
    return when (count % 10) {
        1 -> "трек"
        2, 3, 4 -> "трека"
        else -> "треков"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f МБ".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.0f КБ".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes Б"
}
