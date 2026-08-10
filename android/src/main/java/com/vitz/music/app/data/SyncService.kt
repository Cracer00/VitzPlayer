package com.vitz.music.app.data

import android.util.Log
import com.vitz.music.api.SyncResponse
import com.vitz.music.app.data.local.MirrorDb

/**
 * Наполнение локального зеркала.
 *
 * Сервер отдаёт изменения порциями по курсору, поэтому первый запуск выкачивает каталог
 * целиком, а последующие — только то, что поменялось. Курсор хранится вместе с зеркалом:
 * иначе после переустановки приложения пришлось бы качать всё заново.
 */
class SyncService(
    private val api: ApiClient,
    private val mirror: MirrorDb,
    private val downloads: Downloads,
) {

    /** @return сколько треков приехало. -1 — сервер недоступен. */
    suspend fun sync(): Int {
        if (!api.isLoggedIn) return -1

        var cursor = mirror.syncCursor()
        var received = 0

        repeat(MAX_PAGES) {
            val page: SyncResponse = runCatching {
                val since = cursor?.let { "?since=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                api.get<SyncResponse>("/api/v1/sync$since")
            }.getOrElse { error ->
                Log.i(TAG, "Синхронизация не прошла: ${error.message}")
                return if (received > 0) received else -1
            }

            mirror.upsertTracks(page.tracks)
            // Удалённый на сервере трек уносит с собой и скачанный файл: держать его в памяти
            // магнитолы больше незачем, из библиотеки он всё равно пропал.
            downloads.forget(mirror.deleteTracks(page.deletedTrackIds))
            mirror.replaceLikes(page.likedTrackIds)
            mirror.replacePlaylists(
                playlists = page.playlists.map { it.playlist to it.trackIds },
                deletedIds = page.deletedPlaylistIds,
            )
            mirror.saveSyncCursor(page.cursor)

            received += page.tracks.size + page.deletedTrackIds.size
            cursor = page.cursor
            if (!page.hasMore) return received
        }
        return received
    }

    private companion object {
        const val TAG = "SyncService"

        /** Предохранитель от бесконечного цикла, если курсор вдруг перестанет двигаться. */
        const val MAX_PAGES = 200
    }
}
