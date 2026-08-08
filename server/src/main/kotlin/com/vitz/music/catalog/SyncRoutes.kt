package com.vitz.music.catalog

import com.vitz.music.AppServices
import com.vitz.music.api.PlaylistWithItems
import com.vitz.music.api.SyncResponse
import com.vitz.music.api.TrackDto
import com.vitz.music.db.dbRead
import com.vitz.music.db.select
import com.vitz.music.db.uuid
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Instant
import java.util.UUID

/**
 * Дельта для оффлайн-зеркала клиента.
 *
 * Курсор — пара `(updated_at, id)` последней отданной строки в текстовом виде. Именно пара,
 * а не одно время: у нескольких треков `updated_at` совпадает до микросекунды (одна транзакция
 * ингеста), и без второго ключа часть из них терялась бы между страницами. Время печатается
 * с полной точностью Postgres и возвращается обратно как есть — округление до миллисекунд
 * тоже приводило бы к пропускам.
 *
 * Плейлисты и лайки отдаются целиком: их у пользователя десятки, дельта тут не окупается.
 */
private val EPOCH_CURSOR = Instant.EPOCH to UUID(0, 0)

private fun parseCursor(raw: String?): Pair<Instant, UUID> {
    if (raw.isNullOrBlank()) return EPOCH_CURSOR
    val at = raw.lastIndexOf('_')
    if (at <= 0) return EPOCH_CURSOR
    val instant = runCatching { Instant.parse(raw.substring(0, at)) }.getOrNull() ?: return EPOCH_CURSOR
    val id = runCatching { UUID.fromString(raw.substring(at + 1)) }.getOrNull() ?: return EPOCH_CURSOR
    return instant to id
}

fun Route.syncRoutes(services: AppServices) = route("/api/v1") {
    val cfg = services.cfg
    val signer = services.signer

    authenticate("api") {
        get("/sync") {
            val user = call.user()
            val (sinceAt, sinceId) = parseCursor(call.request.queryParameters["since"])
            val limit = call.limit(default = 500, max = 1000)

            val response = dbRead { c ->
                val changed = c.select(
                    """
                    ${Catalog.TRACK_SELECT}
                    where (t.updated_at, t.id) > (?, ?)
                    order by t.updated_at, t.id
                    limit ?
                    """.trimIndent(),
                    sinceAt, sinceId, limit,
                ) { rs ->
                    Changed(
                        row = Catalog.mapTrack(rs),
                        id = rs.uuid("id"),
                        deleted = rs.getTimestamp("deleted_at") != null,
                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                    )
                }

                val alive = changed.filter { !it.deleted }.map { it.row }
                val withMedia = Catalog.withRenditions(c, alive)
                val liked = Catalog.likedIds(c, user.userId, withMedia)

                val last = changed.lastOrNull()
                val cursor = if (last != null) "${last.updatedAt}_${last.id}" else "${sinceAt}_$sinceId"

                val playlists = Playlists.listFor(c, cfg.publicUrl, user.userId).map { playlist ->
                    PlaylistWithItems(
                        playlist = playlist,
                        trackIds = Playlists.trackIds(c, UUID.fromString(playlist.id)).map { it.toString() },
                    )
                }

                val deletedPlaylists = c.select(
                    "select id from playlists where user_id = ? and deleted_at is not null", user.userId,
                ) { it.uuid("id").toString() }

                val likedIds = c.select(
                    """
                    select l.track_id from track_likes l
                    join tracks t on t.id = l.track_id
                    where l.user_id = ? and t.deleted_at is null
                    order by l.liked_at desc
                    """.trimIndent(),
                    user.userId,
                ) { it.uuid("track_id").toString() }

                SyncResponse(
                    cursor = cursor,
                    hasMore = changed.size == limit,
                    tracks = withMedia.map<TrackRow, TrackDto> {
                        it.toDto(cfg.publicUrl, signer, user.userId, it.id in liked)
                    },
                    deletedTrackIds = changed.filter { it.deleted }.map { it.id.toString() },
                    playlists = playlists,
                    deletedPlaylistIds = deletedPlaylists,
                    likedTrackIds = likedIds,
                )
            }
            call.respond(response)
        }
    }
}

private data class Changed(val row: TrackRow, val id: UUID, val deleted: Boolean, val updatedAt: Instant)
