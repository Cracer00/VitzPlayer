package com.vitz.music.api

import kotlinx.serialization.Serializable

/**
 * DTO API. Модуль общий с Android-клиентом — менять поля здесь означает менять контракт,
 * поэтому несовместимые правки идут только вместе с новой версией в пути (`/api/v1`).
 */

const val API_VERSION = "v1"

// --- аутентификация ---

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    val invite: String,
    val device: String? = null,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val device: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresInSeconds: Long,
)

@Serializable
data class MeResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val role: String,
)

// --- каталог ---

@Serializable
data class MediaLink(
    val rendition: String,
    val url: String,
    val format: String,
    val sizeBytes: Long,
    val bitrate: Int?,
)

@Serializable
data class TrackDto(
    val id: String,
    val title: String,
    val artistId: String,
    val artist: String,
    val albumId: String? = null,
    val album: String? = null,
    val durationMs: Long,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val gainDb: Double? = null,
    val coverUrl: String? = null,
    val liked: Boolean = false,
    val addedAtEpochMs: Long,
    val media: List<MediaLink> = emptyList(),
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
)

@Serializable
data class AlbumDto(
    val id: String,
    val title: String,
    val artistId: String,
    val artist: String,
    val year: Int? = null,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
)

@Serializable
data class Page<T>(
    val items: List<T>,
    val total: Long,
    val offset: Int,
    val limit: Int,
)

@Serializable
data class SearchResponse(
    val tracks: List<TrackDto>,
    val artists: List<ArtistDto>,
    val albums: List<AlbumDto>,
    val playlists: List<PlaylistDto>,
)

// --- плейлисты и лайки ---

@Serializable
data class PlaylistDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val trackCount: Int = 0,
    val coverUrl: String? = null,
    val updatedAtEpochMs: Long,
)

@Serializable
data class PlaylistDetail(
    val playlist: PlaylistDto,
    val items: List<TrackDto>,
)

@Serializable
data class CreatePlaylistRequest(val title: String, val description: String? = null)

@Serializable
data class UpdatePlaylistRequest(val title: String? = null, val description: String? = null)

@Serializable
data class AddItemsRequest(val trackIds: List<String>, val position: Int? = null)

@Serializable
data class RemoveItemsRequest(val trackIds: List<String>)

@Serializable
data class ReorderRequest(val trackIds: List<String>)

// --- прослушивания ---

@Serializable
data class PlayEvent(
    val trackId: String,
    val startedAtEpochMs: Long,
    val msPlayed: Long,
    val completed: Boolean,
)

@Serializable
data class PlaysRequest(val events: List<PlayEvent>)

// --- синхронизация оффлайн-зеркала ---

@Serializable
data class SyncResponse(
    val cursor: String,
    val hasMore: Boolean,
    val tracks: List<TrackDto>,
    val deletedTrackIds: List<String>,
    val playlists: List<PlaylistWithItems>,
    val deletedPlaylistIds: List<String>,
    val likedTrackIds: List<String>,
)

@Serializable
data class PlaylistWithItems(
    val playlist: PlaylistDto,
    val trackIds: List<String>,
)

// --- ошибки ---

@Serializable
data class ErrorDto(val error: String, val message: String? = null)
