package com.vitz.music.app.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений через публичный репозиторий релизов.
 *
 * Исходники остаются приватными, публикуются только сборки — поэтому никакого токена
 * в приложении нет и быть не должно: извлечь его из APK элементарно.
 *
 * Используются постоянные ссылки вида `/releases/latest/download/<файл>`: они всегда ведут
 * на последний релиз, не требуют обращения к API GitHub и не упираются в его лимиты
 * для анонимных запросов.
 *
 * Соединение здесь голое, не через [com.vitz.music.app.data.ApiClient]: тот носит с собой
 * токены пользователя, и отправлять их на github.com незачем.
 */
class UpdateRepository(
    private val owner: String = OWNER,
    private val repository: String = REPOSITORY,
) {

    val releasesPageUrl: String get() = "https://github.com/$owner/$repository/releases/latest"

    private val manifestUrl: String
        get() = "https://github.com/$owner/$repository/releases/latest/download/$MANIFEST_ASSET"

    val apkUrl: String
        get() = "https://github.com/$owner/$repository/releases/latest/download/$APK_ASSET"

    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val text = openConnection(manifestUrl).use { connection ->
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "Манифест обновления недоступен: ${connection.responseCode}")
                    return@withContext null
                }
                connection.inputStream.bufferedReader().readText()
            }
            parseManifest(text)
        }.onFailure { Log.w(TAG, "Не удалось получить сведения об обновлении", it) }.getOrNull()
    }

    private fun parseManifest(text: String): ReleaseInfo {
        val json = JSONObject(text)
        return ReleaseInfo(
            versionName = json.getString("versionName"),
            versionCode = json.getInt("versionCode"),
            notes = json.optString("notes").takeIf { it.isNotBlank() },
            sizeBytes = json.optLong("sizeBytes", 0L),
            apkUrl = json.optString("apkUrl").takeIf { it.isNotBlank() } ?: apkUrl,
        )
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub уводит файлы релизов на отдельный хост; редирект здесь безопасен,
            // потому что никаких заголовков авторизации мы не отправляем.
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    companion object {
        private const val TAG = "UpdateRepository"
        private const val TIMEOUT_MS = 15_000

        /** Публичный репозиторий только со сборками; исходники лежат в приватном. */
        const val OWNER = "Cracer00"
        const val REPOSITORY = "VitzMusic-releases"

        /** Имена файлов постоянные — на них указывают ссылки «последнего релиза». */
        const val MANIFEST_ASSET = "version.json"
        const val APK_ASSET = "VitzMusic-release.apk"
    }
}

data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String?,
    val sizeBytes: Long,
    val apkUrl: String,
) {
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode

    val sizeMb: String get() = "%.1f МБ".format(sizeBytes / 1024.0 / 1024.0)
}
