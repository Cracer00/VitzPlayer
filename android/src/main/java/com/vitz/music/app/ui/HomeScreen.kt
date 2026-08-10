package com.vitz.music.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitz.music.api.MeResponse
import com.vitz.music.api.Page
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.ApiClient
import androidx.compose.material3.FilterChip
import com.vitz.music.app.data.AppSettings
import com.vitz.music.app.data.ThemeMode
import com.vitz.music.app.update.UpdateManager
import com.vitz.music.app.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Временный экран второго среза: показывает, что вход состоялся и каталог доступен.
 * На третьем срезе его место займёт библиотека.
 */
@Composable
fun HomeScreen(
    me: MeResponse,
    api: ApiClient,
    settings: AppSettings,
    updates: UpdateManager,
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
        // Прокрутка обязательна: на экране магнитолы карточки не помещаются целиком,
        // а кнопка выхода и обновление не должны уезжать за край без возможности достать.
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Тема", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.theme == mode,
                            onClick = { settings.updateTheme(mode) },
                            label = { Text(mode.title) },
                        )
                    }
                }
            }
        }

        Card {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Продолжать с того же места", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "После запуска музыка сама продолжится с той секунды, где оборвалась",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.resumeOnStart,
                    onCheckedChange = settings::updateResumeOnStart,
                )
            }
        }

        UpdateCard(updates)

        TextButton(onClick = onLogout) { Text("Выйти") }
    }
}

/**
 * Обновление приложения из публичного репозитория релизов.
 *
 * Проверка запускается только вручную: плеер живёт в машине и обязан молча играть,
 * а не предлагать обновиться посреди дороги.
 */
@Composable
private fun UpdateCard(updates: UpdateManager, modifier: Modifier = Modifier) {
    val state by updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Обновление", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))

            when (val current = state) {
                UpdateState.Idle -> {
                    Text(
                        "Установлена версия ${updates.currentVersionName}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch { updates.check() } }) {
                        Text("Проверить обновление")
                    }
                }

                UpdateState.Checking -> Text("Проверяю…", style = MaterialTheme.typography.bodyMedium)

                UpdateState.UpToDate -> {
                    Text(
                        "Установлена последняя версия — ${updates.currentVersionName}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { scope.launch { updates.check() } }) {
                        Text("Проверить снова")
                    }
                }

                is UpdateState.Available -> {
                    Text(
                        "Доступна версия ${current.release.versionName} · ${current.release.sizeMb}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    current.release.notes?.let { notes ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch { updates.download(current.release) } }) {
                        Text("Скачать")
                    }
                }

                is UpdateState.Downloading -> {
                    Text(
                        "Скачиваю ${current.release.versionName}…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is UpdateState.Downloaded -> {
                    Text(
                        "Версия ${current.release.versionName} готова к установке.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (updates.canInstall()) {
                        Button(onClick = { updates.install(current.file) }) { Text("Установить") }
                    } else {
                        Text(
                            "Android требует разрешить установку приложений из этого источника.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { updates.openInstallPermissionSettings() }) {
                            Text("Разрешить установку")
                        }
                    }
                }

                is UpdateState.Failed -> {
                    Text(
                        current.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { updates.check() } }) { Text("Повторить") }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(updates.releasesPageUrl))
                                    )
                                }
                            }
                        ) { Text("Открыть в браузере") }
                    }
                }
            }
        }
    }
}
