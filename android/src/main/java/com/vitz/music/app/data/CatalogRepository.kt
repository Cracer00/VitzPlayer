package com.vitz.music.app.data

import com.vitz.music.api.AddItemsRequest
import com.vitz.music.api.CreatePlaylistRequest
import com.vitz.music.api.Page
import com.vitz.music.api.PlaylistDetail
import com.vitz.music.api.PlaylistDto
import com.vitz.music.api.RemoveItemsRequest
import com.vitz.music.api.SearchResponse
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.local.MirrorDb
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Чтение каталога.
 *
 * Источник для показа — локальное зеркало, а не сеть: список должен открываться мгновенно
 * и одинаково работать в подземном паркинге. Сеть нужна для того, чтобы зеркало обновить,
 * и для действий, которые обязаны дойти до сервера, — лайков и правки плейлистов.
 */
class CatalogRepository(
    private val api: ApiClient,
    private val mirror: MirrorDb,
    private val downloads: Downloads,
    /** Что делать после появления новой отметки «нравится». Решает не каталог. */
    private val onLiked: () -> Unit = {},
) {

    /**
     * Сигналы о том, что зеркало изменилось. Экрану их достаточно, чтобы перечитать себя:
     * знать, кто именно писал в базу — синхронизация, автозагрузка или соседний экран, —
     * ему незачем.
     */
    val catalogRevision: StateFlow<Long> get() = mirror.catalogRevision
    val downloadsRevision: StateFlow<Long> get() = mirror.downloadsRevision

    suspend fun tracks(
        offset: Int,
        limit: Int = PAGE_SIZE,
        query: String? = null,
        downloadedOnly: Boolean = false,
    ): Page<TrackDto> = fromMirror(offset, limit, query, likedOnly = false, downloadedOnly = downloadedOnly)

    suspend fun likedTracks(
        offset: Int,
        limit: Int = PAGE_SIZE,
        query: String? = null,
        downloadedOnly: Boolean = false,
    ): Page<TrackDto> = fromMirror(offset, limit, query, likedOnly = true, downloadedOnly = downloadedOnly)

    private suspend fun fromMirror(
        offset: Int,
        limit: Int,
        query: String?,
        likedOnly: Boolean,
        downloadedOnly: Boolean,
    ): Page<TrackDto> = withContext(Dispatchers.IO) {
        val liked = mirror.likedIds()
        val items = mirror.tracks(offset, limit, query, likedOnly, downloadedOnly)
            .map { it.copy(liked = it.id in liked) }
        Page(
            items = items,
            total = mirror.trackCount(query, likedOnly, downloadedOnly).toLong(),
            offset = offset,
            limit = limit,
        )
    }

    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        // Поиск тоже по зеркалу: иначе в оффлайне он молча ничего не находил бы.
        val liked = mirror.likedIds()
        SearchResponse(
            tracks = mirror.tracks(0, SEARCH_LIMIT, query, likedOnly = false, downloadedOnly = false)
                .map { it.copy(liked = it.id in liked) },
            artists = emptyList(),
            albums = emptyList(),
            playlists = mirror.playlists().filter { it.title.contains(query, ignoreCase = true) },
        )
    }

    suspend fun playlists(): List<PlaylistDto> = withContext(Dispatchers.IO) { mirror.playlists() }

    suspend fun playlist(id: String): PlaylistDetail = withContext(Dispatchers.IO) {
        val playlist = mirror.playlists().firstOrNull { it.id == id }
            ?: PlaylistDto(id = id, title = "Плейлист", updatedAtEpochMs = 0)
        val liked = mirror.likedIds()
        PlaylistDetail(
            playlist = playlist,
            items = mirror.playlistTracks(id).map { it.copy(liked = it.id in liked) },
        )
    }

    suspend fun recent(limit: Int = 30): Page<TrackDto> = tracks(offset = 0, limit = limit)

    // --- действия, которым нужна сеть ---

    suspend fun createPlaylist(title: String): PlaylistDto =
        api.post("/api/v1/playlists", CreatePlaylistRequest(title))

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) =
        api.send(HttpMethod.Post, "/api/v1/playlists/$playlistId/items", AddItemsRequest(trackIds))

    suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>) =
        api.send(HttpMethod.Delete, "/api/v1/playlists/$playlistId/items", RemoveItemsRequest(trackIds))

    /**
     * Лайк сразу отражается в зеркале, а до сервера доходит по возможности: сердечко обязано
     * загораться мгновенно, в том числе без сети. Расхождение выправит ближайшая синхронизация.
     */
    suspend fun setLiked(trackId: String, liked: Boolean) {
        withContext(Dispatchers.IO) { mirror.setLiked(trackId, liked) }
        // Отметка уже в зеркале, поэтому автозагрузка увидит трек как «понравившийся,
        // но не скачанный» — до ответа сервера и независимо от того, дойдёт ли он вообще.
        if (liked) onLiked()
        runCatching {
            api.send(if (liked) HttpMethod.Put else HttpMethod.Delete, "/api/v1/likes/$trackId")
        }
    }

    /** Ссылка на файл, если трек скачан: в оффлайне играет только он. */
    fun localPath(trackId: String): String? = downloads.localPath(trackId)

    companion object {
        const val PAGE_SIZE = 50
        const val SEARCH_LIMIT = 50
    }
}
