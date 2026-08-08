package com.vitz.music.app.data

import com.vitz.music.api.AddItemsRequest
import com.vitz.music.api.CreatePlaylistRequest
import com.vitz.music.api.Page
import com.vitz.music.api.PlaylistDetail
import com.vitz.music.api.PlaylistDto
import com.vitz.music.api.RemoveItemsRequest
import com.vitz.music.api.SearchResponse
import com.vitz.music.api.TrackDto
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter

/**
 * Чтение каталога. Пока ходит в сеть на каждый запрос — оффлайн-зеркало на Room появится
 * седьмым срезом вместе с загрузками на устройство.
 */
class CatalogRepository(private val api: ApiClient) {

    suspend fun tracks(offset: Int, limit: Int = PAGE_SIZE): Page<TrackDto> =
        api.get("/api/v1/tracks?limit=$limit&offset=$offset")

    suspend fun likedTracks(offset: Int, limit: Int = PAGE_SIZE): Page<TrackDto> =
        api.get("/api/v1/likes?limit=$limit&offset=$offset")

    suspend fun recent(limit: Int = 30): Page<TrackDto> = api.get("/api/v1/recent?limit=$limit")

    suspend fun search(query: String): SearchResponse =
        api.get("/api/v1/search?q=${query.encodeURLParameter()}")

    suspend fun playlists(): List<PlaylistDto> = api.get("/api/v1/playlists")

    suspend fun playlist(id: String): PlaylistDetail = api.get("/api/v1/playlists/$id")

    suspend fun createPlaylist(title: String): PlaylistDto =
        api.post("/api/v1/playlists", CreatePlaylistRequest(title))

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) =
        api.send(HttpMethod.Post, "/api/v1/playlists/$playlistId/items", AddItemsRequest(trackIds))

    suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>) =
        api.send(HttpMethod.Delete, "/api/v1/playlists/$playlistId/items", RemoveItemsRequest(trackIds))

    suspend fun setLiked(trackId: String, liked: Boolean) =
        api.send(if (liked) HttpMethod.Put else HttpMethod.Delete, "/api/v1/likes/$trackId")

    companion object {
        const val PAGE_SIZE = 50
    }
}
