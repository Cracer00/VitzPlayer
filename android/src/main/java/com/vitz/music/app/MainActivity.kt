package com.vitz.music.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitz.music.app.player.PlayerController
import com.vitz.music.app.ui.AppState
import com.vitz.music.app.ui.AppViewModel
import com.vitz.music.app.ui.LoginScreen
import com.vitz.music.app.ui.MainScreen
import com.vitz.music.app.ui.theme.VitzMusicTheme

class MainActivity : ComponentActivity() {

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationsIfNeeded()
        val app = application as VitzMusicApp
        setContent {
            VitzMusicTheme(mode = app.settings.theme) {
                val model: AppViewModel = viewModel(factory = AppViewModel.factory(app))
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    AppRoot(model, app, Modifier.padding(padding))
                }
            }
        }
    }

    /**
     * С Android 13 уведомления требуют разрешения. Без него плеер играет, но управлять им
     * из шторки нельзя, а в машине это основной способ — до экрана ещё дотянуться надо.
     * Отказ не блокирует ничего: просто не будет уведомления.
     */
    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@androidx.media3.common.util.UnstableApi
@Composable
private fun AppRoot(model: AppViewModel, app: VitzMusicApp, modifier: Modifier = Modifier) {
    // Подключение к службе — до проверки входа, а не внутри главного экрана.
    //
    // Проверка ходит на сервер, и в машине без связи она упирается в таймаут: пока он идёт,
    // на экране крутится колесо, служба даже не создана, и восстанавливать прошлую поездку
    // некому. Музыке сервер не нужен — очередь лежит в зеркале, файлы на диске, — поэтому
    // плеер поднимается сразу, параллельно с проверкой.
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

    when (val state = model.state) {
        AppState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is AppState.LoggedOut -> LoginScreen(
            state = state,
            onLogin = model::login,
            modifier = modifier,
        )

        is AppState.LoggedIn -> MainScreen(
            me = state.me,
            app = app,
            player = player,
            offline = state.offline,
            onRetryConnection = model::retryConnection,
            onLogout = model::logout,
            modifier = modifier,
        )
    }
}
