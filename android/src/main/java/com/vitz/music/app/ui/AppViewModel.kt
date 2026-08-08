package com.vitz.music.app.ui

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitz.music.api.MeResponse
import com.vitz.music.app.data.ApiClient
import com.vitz.music.app.data.ApiException
import kotlinx.coroutines.launch

sealed interface AppState {
    data object Loading : AppState
    data class LoggedOut(val serverUrl: String, val error: String? = null, val busy: Boolean = false) : AppState
    data class LoggedIn(val me: MeResponse) : AppState
}

class AppViewModel(private val api: ApiClient) : ViewModel() {

    var state: AppState by mutableStateOf(AppState.Loading)
        private set

    init {
        restore()
    }

    /**
     * На старте пробуем восстановить сессию. Токен доступа живёт 15 минут, так что после
     * ночи в кармане он почти всегда протух — клиент сам сходит за новым по refresh,
     * и пользователь ничего не заметит.
     */
    private fun restore() {
        viewModelScope.launch {
            if (!api.isLoggedIn) {
                state = AppState.LoggedOut(api.serverUrl() ?: DEFAULT_SERVER)
                return@launch
            }
            state = runCatching { AppState.LoggedIn(api.me()) }
                .getOrElse { error ->
                    when {
                        error is ApiException && error.status == 401 ->
                            AppState.LoggedOut(api.serverUrl() ?: DEFAULT_SERVER)
                        // Нет сети — это не повод выкидывать сессию: попросим повторить.
                        else -> AppState.LoggedOut(
                            serverUrl = api.serverUrl() ?: DEFAULT_SERVER,
                            error = error.readableMessage(),
                        )
                    }
                }
        }
    }

    fun login(serverUrl: String, email: String, password: String) {
        val current = state as? AppState.LoggedOut ?: return
        if (current.busy) return
        state = current.copy(busy = true, error = null)
        viewModelScope.launch {
            state = runCatching {
                AppState.LoggedIn(api.login(serverUrl, email, password, device = deviceName()))
            }.getOrElse { error ->
                current.copy(busy = false, error = error.readableMessage())
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            api.logout()
            state = AppState.LoggedOut(api.serverUrl() ?: DEFAULT_SERVER)
        }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun Throwable.readableMessage(): String = when {
        this is ApiException && status == 401 -> "Неверная почта или пароль"
        this is ApiException && code == "too_many_attempts" -> "Слишком много попыток, подождите"
        this is ApiException -> message ?: "Сервер ответил ошибкой $status"
        else -> "Сервер недоступен: ${this::class.simpleName}"
    }

    companion object {
        const val DEFAULT_SERVER = "https://music.nethound.ru"

        fun factory(api: ApiClient) = viewModelFactory {
            initializer { AppViewModel(api) }
        }
    }
}
