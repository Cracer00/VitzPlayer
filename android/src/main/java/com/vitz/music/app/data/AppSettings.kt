package com.vitz.music.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val title: String) {
    DARK("Тёмная"),
    LIGHT("Светлая"),
    AUTO("Как в системе"),
}

enum class AutoDownloadScope(val title: String, val hint: String) {
    ALL("Весь каталог", "Всё, что есть на сервере, будет лежать на устройстве"),
    LIKED("Только понравившееся", "Скачивается то, что отмечено сердечком"),
    OFF("Выключено", "Скачивать только вручную, кнопкой в списке"),
}

/**
 * Настройки приложения.
 *
 * Значения хранятся в SharedPreferences, а в память отданы как состояние Compose: экран
 * настроек читает их напрямую и перерисовывается сам, без потоков и подписок ради трёх флагов.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("vitz-music-settings", Context.MODE_PRIVATE)

    /**
     * По умолчанию автозагрузка выключена. Каталог — это гигабайты, и решение занять ими
     * планшет человек должен принять сам, а не обнаружить постфактум по забитой памяти.
     */
    var autoDownload by mutableStateOf(
        runCatching { AutoDownloadScope.valueOf(prefs.getString(KEY_SCOPE, null) ?: "") }
            .getOrDefault(AutoDownloadScope.OFF),
    )
        private set

    /**
     * По умолчанию только по Wi-Fi. Сценарий такой: планшет стоит дома на зарядке и
     * выкачивает каталог, а в машине уже играет своё. Качать гигабайты по мобильному
     * интернету, не спросив, — плохая услуга.
     */
    var wifiOnly by mutableStateOf(prefs.getBoolean(KEY_WIFI_ONLY, true))
        private set

    /** Оригинал занимает втрое больше, а по Bluetooth разницы всё равно не слышно. */
    var downloadOriginal by mutableStateOf(prefs.getBoolean(KEY_ORIGINAL, false))
        private set

    /**
     * Продолжать с того же места при запуске. Включено по умолчанию: в машине это основной
     * сценарий — завёл двигатель, музыка играет дальше сама.
     */
    var resumeOnStart by mutableStateOf(prefs.getBoolean(KEY_RESUME, true))
        private set

    /**
     * Тема. По умолчанию тёмная, а не системная: приложением пользуются в машине, чаще
     * в темноте, и белый экран там бьёт по глазам независимо от того, что стоит в системе.
     */
    var theme by mutableStateOf(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.DARK),
    )
        private set

    val rendition: String get() = if (downloadOriginal) "original" else "opus-96"

    fun updateTheme(value: ThemeMode) {
        theme = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun updateResumeOnStart(value: Boolean) {
        resumeOnStart = value
        prefs.edit().putBoolean(KEY_RESUME, value).apply()
    }

    fun updateAutoDownload(scope: AutoDownloadScope) {
        autoDownload = scope
        prefs.edit().putString(KEY_SCOPE, scope.name).apply()
    }

    fun updateWifiOnly(value: Boolean) {
        wifiOnly = value
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    fun updateDownloadOriginal(value: Boolean) {
        downloadOriginal = value
        prefs.edit().putBoolean(KEY_ORIGINAL, value).apply()
    }

    private companion object {
        const val KEY_SCOPE = "auto_download_scope"
        const val KEY_WIFI_ONLY = "auto_download_wifi_only"
        const val KEY_ORIGINAL = "download_original"
        const val KEY_RESUME = "resume_on_start"
        const val KEY_THEME = "theme_mode"
    }
}
