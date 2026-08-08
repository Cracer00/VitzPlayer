package com.vitz.music.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitz.music.app.ui.AppState
import com.vitz.music.app.ui.AppViewModel
import com.vitz.music.app.ui.HomeScreen
import com.vitz.music.app.ui.LoginScreen
import com.vitz.music.app.ui.theme.VitzMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as VitzMusicApp
        setContent {
            VitzMusicTheme {
                val model: AppViewModel = viewModel(factory = AppViewModel.factory(app.api))
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    AppRoot(model, app, Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun AppRoot(model: AppViewModel, app: VitzMusicApp, modifier: Modifier = Modifier) {
    when (val state = model.state) {
        AppState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is AppState.LoggedOut -> LoginScreen(
            state = state,
            onLogin = model::login,
            modifier = modifier,
        )

        is AppState.LoggedIn -> HomeScreen(
            me = state.me,
            api = app.api,
            onLogout = model::logout,
            modifier = modifier,
        )
    }
}
