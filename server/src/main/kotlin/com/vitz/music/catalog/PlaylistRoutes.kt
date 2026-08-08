package com.vitz.music.catalog

import com.vitz.music.AppServices
import com.vitz.music.api.AddItemsRequest
import com.vitz.music.api.CreatePlaylistRequest
import com.vitz.music.api.PlaylistDetail
import com.vitz.music.api.RemoveItemsRequest
import com.vitz.music.api.ReorderRequest
import com.vitz.music.api.UpdatePlaylistRequest
import com.vitz.music.badRequest
import com.vitz.music.db.dbRead
import com.vitz.music.db.dbTx
import com.vitz.music.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

fun Route.playlistRoutes(services: AppServices) = route("/api/v1/playlists") {
    val cfg = services.cfg
    val signer = services.signer

    authenticate("api") {
        get {
            val user = call.user()
            call.respond(dbRead { c -> Playlists.listFor(c, cfg.publicUrl, user.userId) })
        }

        post {
            val user = call.user()
            val body = call.receive<CreatePlaylistRequest>()
            if (body.title.isBlank()) badRequest("Пустое название")
            val dto = dbTx { c ->
                val id = Playlists.create(c, user.userId, body.title, body.description)
                Playlists.byId(c, cfg.publicUrl, user.userId, id)
            } ?: notFound("playlist_not_found")
            call.respond(HttpStatusCode.Created, dto)
        }

        get("/{id}") {
            val user = call.user()
            val id = call.uuidParam("id")
            val detail = dbRead { c ->
                val playlist = Playlists.byId(c, cfg.publicUrl, user.userId, id) ?: return@dbRead null
                val rows = Playlists.items(c, id)
                val liked = Catalog.likedIds(c, user.userId, rows)
                PlaylistDetail(
                    playlist = playlist,
                    items = rows.map { it.toDto(cfg.publicUrl, signer, user.userId, it.id in liked) },
                )
            } ?: notFound("playlist_not_found")
            call.respond(detail)
        }

        patch("/{id}") {
            val user = call.user()
            val id = call.uuidParam("id")
            val body = call.receive<UpdatePlaylistRequest>()
            val dto = dbTx { c ->
                if (!Playlists.update(c, user.userId, id, body.title, body.description)) return@dbTx null
                Playlists.byId(c, cfg.publicUrl, user.userId, id)
            } ?: notFound("playlist_not_found")
            call.respond(dto)
        }

        delete("/{id}") {
            val user = call.user()
            val id = call.uuidParam("id")
            val ok = dbTx { c -> Playlists.softDelete(c, user.userId, id) }
            if (!ok) notFound("playlist_not_found")
            call.respond(HttpStatusCode.NoContent)
        }

        post("/{id}/items") {
            val user = call.user()
            val id = call.uuidParam("id")
            val body = call.receive<AddItemsRequest>()
            val ids = body.trackIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            if (ids.isEmpty()) badRequest("Пустой список треков")
            dbTx { c ->
                if (!Playlists.owns(c, user.userId, id)) notFound("playlist_not_found")
                Playlists.addItems(c, id, ids, body.position)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/{id}/items") {
            val user = call.user()
            val id = call.uuidParam("id")
            val body = call.receive<RemoveItemsRequest>()
            val ids = body.trackIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            dbTx { c ->
                if (!Playlists.owns(c, user.userId, id)) notFound("playlist_not_found")
                Playlists.removeItems(c, id, ids)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        put("/{id}/order") {
            val user = call.user()
            val id = call.uuidParam("id")
            val body = call.receive<ReorderRequest>()
            val ids = body.trackIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            dbTx { c ->
                if (!Playlists.owns(c, user.userId, id)) notFound("playlist_not_found")
                Playlists.reorder(c, id, ids)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
