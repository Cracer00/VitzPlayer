package com.vitz.music.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedPlayback(
    val trackIds: List<String>,
    val index: Int,
    val positionMs: Long,
    /** Играло ли в момент сохранения. Пауза, сделанная руками, не должна оживать сама. */
    val wasPlaying: Boolean,
    val savedAtEpochMs: Long,
)

/**
 * Что играло и на какой секунде.
 *
 * Пишется периодически по ходу воспроизведения, а не при выходе из приложения: зажигание
 * выключается без предупреждения, процесс убивают, и никакого «сохранить напоследок»
 * не случается. Отсюда и цена ошибки: потеряется не состояние, а ровно последние секунды.
 */
class PlaybackStateStore(context: Context) {

    private val prefs = context.getSharedPreferences("vitz-music-playback", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(state: SavedPlayback) {
        runCatching {
            prefs.edit().putString(KEY_STATE, json.encodeToString(state)).apply()
        }
    }

    fun load(): SavedPlayback? = runCatching {
        prefs.getString(KEY_STATE, null)?.let { json.decodeFromString<SavedPlayback>(it) }
    }.getOrNull()?.takeIf { it.trackIds.isNotEmpty() }

    fun clear() {
        prefs.edit().remove(KEY_STATE).apply()
    }

    private companion object {
        const val KEY_STATE = "last_playback"
    }
}
