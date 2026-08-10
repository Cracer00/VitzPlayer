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
import com.vitz.music.app.VitzMusicApp
import com.vitz.music.app.data.ApiClient
import com.vitz.music.app.data.ApiException
import com.vitz.music.app.data.AutoDownloader
import com.vitz.music.app.data.Downloads
import com.vitz.music.app.data.SyncService
import com.vitz.music.app.data.TokenStore
import kotlinx.coroutines.launch

sealed interface AppState {
    data object Loading : AppState
    data class LoggedOut(val serverUrl: String, val error: String? = null, val busy: Boolean = false) : AppState

    /** [offline] — вошли по сохранённому профилю, сервер недоступен. */
    data class LoggedIn(val me: MeResponse, val offline: Boolean = false) : AppState
}

class AppViewModel(
    private val api: ApiClient,
    private val store: TokenStore,
    private val sync: SyncService,
    private val downloads: Downloads,
    private val autoDownloader: AutoDownloader,
) : ViewModel() {

    var state: AppState by mutableStateOf(AppState.Loading)
        private set

    init {
        restore()
    }

    /**
     * На старте пробуем восстановить сессию. Токен доступа живёт 15 минут, так что после
     * ночи в кармане он почти всегда протух — клиент сам сходит за новым по refresh,
     * и пользователь ничего не заметит.
     *
     * Главное здесь — отсутствие сети не должно упирать в экран входа. Токены на месте,
     * каталог лежит в зеркале, скачанное лежит на диске: музыке нечего ждать от сервера.
     * На экран входа возвращаемся только когда сервер прямо сказал, что сессия недействительна.
     */
    private fun restore() {
        viewModelScope.launch {
            if (!api.isLoggedIn) {
                state = AppState.LoggedOut(api.serverUrl() ?: DEFAULT_SERVER)
                return@launch
            }

            state = runCatching {
                val me = api.me()
                store.saveProfile(me.email, me.displayName, me.role)
                AppState.LoggedIn(me)
            }.getOrElse { error ->
                when {
                    error is ApiException && error.status == 401 ->
                        AppState.LoggedOut(api.serverUrl() ?: DEFAULT_SERVER)

                    else -> offlineState() ?: AppState.LoggedOut(
                        serverUrl = api.serverUrl() ?: DEFAULT_SERVER,
                        error = error.readableMessage(),
                    )
                }
            }

            if (state is AppState.LoggedIn) refreshCatalog()
        }
    }

    /** Вход по сохранённому профилю. Без него в оффлайне не с чем открывать библиотеку. */
    private fun offlineState(): AppState.LoggedIn? {
        val (email, name, role) = store.loadProfile() ?: return null
        return AppState.LoggedIn(
            me = MeResponse(id = "", email = email, displayName = name, role = role),
            offline = true,
        )
    }

    /** Обновление зеркала в фоне: экран уже показан, ждать синхронизацию незачем. */
    private fun refreshCatalog() {
        viewModelScope.launch {
            val received = sync.sync()
            val current = state
            if (current is AppState.LoggedIn) {
                // Синхронизация прошла — значит сервер на связи, отметку «оффлайн» снимаем.
                state = current.copy(offline = received < 0)
            }
            // Зеркало обновилось — самое время сверить загрузки с диском и догрузить
            // недостающее. Свои предохранители (тип сети, свободное место) автозагрузка
            // проверяет сама.
            if (received >= 0) {
                downloads.reconcile()
                autoDownloader.start()
            }
        }
    }

    /** Повторная попытка достучаться до сервера — по кнопке в полосе «нет связи». */
    fun retryConnection() {
        if (state is AppState.LoggedIn) refreshCatalog() else restore()
    }

    fun login(serverUrl: String, email: String, password: String) {
        val current = state as? AppState.LoggedOut ?: return
        if (current.busy) return
        state = current.copy(busy = true, error = null)
        viewModelScope.launch {
            state = runCatching {
                val me = api.login(serverUrl, email, password, device = deviceName())
                store.saveProfile(me.email, me.displayName, me.role)
                AppState.LoggedIn(me)
            }.getOrElse { error ->
                current.copy(busy = false, error = error.readableMessage())
            }
            if (state is AppState.LoggedIn) refreshCatalog()
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

        fun factory(app: VitzMusicApp) = viewModelFactory {
            initializer { AppViewModel(app.api, app.tokenStore, app.sync, app.downloads, app.autoDownloader) }
        }
    }
}
