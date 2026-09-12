package com.vitz.music.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Снимок трека, достаточный, чтобы включить его без зеркала и без сети.
 *
 * Нужен потому, что очередь хранится идентификаторами, а зеркало может их не отдать:
 * база пересоздалась, трек уехал из каталога, синхронизация ещё не дошла. Тогда из
 * снимка собирается хотя бы то, что играло, — а это и есть главное обещание.
 *
 * Адрес храним последний известный. Подпись в нём протухает за часы, но продлевает её
 * тот же слой, что и в обычном воспроизведении, — по идентификатору трека внутри ссылки.
 */
@Serializable
data class SavedTrack(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val coverUrl: String? = null,
    val url: String? = null,
    val liked: Boolean = false,
)

@Serializable
data class SavedPlayback(
    val trackIds: List<String>,
    val index: Int,
    /**
     * Что именно играло.
     *
     * Индекса мало: при восстановлении из очереди выпадают треки, которых не оказалось в
     * зеркале, и позиция в списке уезжает — плеер продолжал «с той же секунды», но другую
     * песню. Идентификатор не уезжает.
     */
    val currentTrackId: String? = null,
    val positionMs: Long,
    /** Играло ли в момент сохранения. Пауза, сделанная руками, не должна оживать сама. */
    val wasPlaying: Boolean,
    val savedAtEpochMs: Long,
    /** Снимок текущего трека — на случай, если зеркало его не отдаст. */
    val current: SavedTrack? = null,
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

    /** Последнее, что реально легло на диск. По нему отсеиваются повторные записи того же. */
    @Volatile
    private var lastWritten: SavedPlayback? = null

    /**
     * Сохранить. Синхронно и на диск — `apply()` откладывает запись, а между «отложил» и
     * «записал» помещается всё выключение зажигания целиком.
     *
     * Из-за этого вызывать нужно не с главного потока. Исключение — прощание службы:
     * там записать важнее, чем не задеть кадр отрисовки, которого всё равно уже не будет.
     */
    fun save(state: SavedPlayback) {
        // Пишут двое — тикер и слушатель событий, — и оба с фонового потока. Без замка
        // они могут разъехаться так, что последним на диск ляжет снимок постарше.
        synchronized(this) {
            // Одно и то же не переписываем: на паузе тикер приносит один и тот же снимок
            // каждые несколько секунд, а флеш-память магнитолы не бесконечная.
            if (lastWritten?.sameContent(state) == true) return
            runCatching { prefs.edit().putString(KEY_STATE, json.encodeToString(state)).commit() }
                .onSuccess { lastWritten = state }
        }
    }

    fun load(): SavedPlayback? = runCatching {
        prefs.getString(KEY_STATE, null)?.let { json.decodeFromString<SavedPlayback>(it) }
    }.getOrNull()?.takeIf { it.trackIds.isNotEmpty() }

    fun clear() {
        lastWritten = null
        prefs.edit().remove(KEY_STATE).apply()
    }

    /** Различие только во времени сохранения — это не различие. */
    private fun SavedPlayback.sameContent(other: SavedPlayback): Boolean =
        copy(savedAtEpochMs = 0) == other.copy(savedAtEpochMs = 0)

    private companion object {
        const val KEY_STATE = "last_playback"
    }
}
