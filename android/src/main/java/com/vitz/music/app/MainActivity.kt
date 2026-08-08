package com.vitz.music.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitz.music.api.API_VERSION
import com.vitz.music.app.ui.theme.VitzMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VitzMusicTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    Placeholder(Modifier.padding(padding))
                }
            }
        }
    }
}

/**
 * Заглушка первого среза. Обращение к [API_VERSION] здесь не украшение: это проверка того,
 * что модуль shared с DTO сервера действительно подключается к Android-сборке —
 * ради этого приложение и живёт в одном репозитории с бэкендом.
 */
@Composable
private fun Placeholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Vitz Music", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Контракт API: $API_VERSION",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
