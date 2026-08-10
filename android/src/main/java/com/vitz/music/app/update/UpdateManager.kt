package com.vitz.music.app.update

import android.content.Context
import android.util.Log
import com.vitz.music.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Состояние самообновления: проверка, скачивание, установка.
 *
 * Скачанный файл живёт в кэше приложения — если установку прервали, мусор уберёт система.
 * Скачанная музыка лежит в filesDir и под эту уборку не попадает.
 */
class UpdateManager(
    private val context: Context,
    private val repository: UpdateRepository = UpdateRepository(),
    private val installer: ApkInstaller = ApkInstaller(context),
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val releasesPageUrl: String get() = repository.releasesPageUrl

    /** Разрешена ли установка приложений из этого источника. */
    fun canInstall(): Boolean = installer.canRequestInstall()

    fun openInstallPermissionSettings() = installer.openPermissionSettings()

    suspend fun check() {
        _state.value = UpdateState.Checking
        val release = repository.fetchLatest()
        _state.value = when {
            release == null -> UpdateState.Failed("Не удалось связаться с сервером обновлений")
            release.isNewerThan(currentVersionCode) -> UpdateState.Available(release)
            else -> UpdateState.UpToDate
        }
    }

    suspend fun download(release: ReleaseInfo) {
        val target = File(updatesDirectory(), "${release.versionName}.apk")
        _state.value = UpdateState.Downloading(release, 0f)

        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                }
                connection.use {
                    if (it.responseCode != HttpURLConnection.HTTP_OK) return@runCatching false

                    val total = it.contentLengthLong.takeIf { size -> size > 0 } ?: release.sizeBytes
                    var downloaded = 0L

                    it.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    _state.value = UpdateState.Downloading(
                                        release,
                                        (downloaded.toFloat() / total).coerceIn(0f, 1f),
                                    )
                                }
                            }
                        }
                    }
                    true
                }
            }.onFailure { Log.w(TAG, "Скачивание обновления не удалось", it) }.getOrDefault(false)
        }

        _state.value = if (ok && target.length() > 0) {
            UpdateState.Downloaded(release, target)
        } else {
            runCatching { target.delete() }
            UpdateState.Failed("Не удалось скачать обновление")
        }
    }

    fun install(file: File) {
        val started = installer.install(file)
        if (!started) _state.value = UpdateState.Failed("Установщик не запустился")
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    private fun updatesDirectory(): File =
        File(context.cacheDir, "updates").apply { mkdirs() }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private companion object {
        const val TAG = "UpdateManager"
        const val TIMEOUT_MS = 20_000
        const val BUFFER_SIZE = 64 * 1024
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class Downloaded(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val reason: String) : UpdateState
}
