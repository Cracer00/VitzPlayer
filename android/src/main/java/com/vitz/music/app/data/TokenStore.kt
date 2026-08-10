package com.vitz.music.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Сессия пользователя: где сервер и какими токенами к нему ходить.
 *
 * Refresh-токен живёт месяц и даёт доступ ко всей фонотеке, поэтому лежит в
 * EncryptedSharedPreferences — ключ шифрования хранится в Android Keystore и из приложения
 * не извлекается. Обычных SharedPreferences тут мало: на устройстве с root-доступом
 * их содержимое читается как обычный XML.
 */
data class Session(
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String,
)

class TokenStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "vitz-music-session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): Session? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return Session(server, access, refresh)
    }

    fun save(session: Session) {
        prefs.edit()
            .putString(KEY_SERVER, session.serverUrl)
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .apply()
    }

    /** Адрес сервера переживает выход: вводить его каждый раз незачем. */
    fun clearTokens() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
    }

    fun lastServerUrl(): String? = prefs.getString(KEY_SERVER, null)

    /**
     * Профиль последнего успешного входа. Нужен, чтобы открыть приложение без сети: токены
     * есть, но спросить у сервера, кто мы, нельзя — а показать имя и пустить к музыке надо.
     */
    fun saveProfile(email: String, displayName: String, role: String) {
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_NAME, displayName)
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun loadProfile(): Triple<String, String, String>? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val role = prefs.getString(KEY_ROLE, null) ?: "user"
        return Triple(email, name, role)
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EMAIL = "profile_email"
        const val KEY_NAME = "profile_name"
        const val KEY_ROLE = "profile_role"
    }
}
