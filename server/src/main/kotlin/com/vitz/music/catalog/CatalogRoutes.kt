package com.vitz.music.catalog

import com.vitz.music.AppServices
import com.vitz.music.api.ArtistDto
import com.vitz.music.api.Page
import com.vitz.music.api.PlaysRequest
import com.vitz.music.api.SearchResponse
import com.vitz.music.api.TrackDto
import com.vitz.music.auth.UserPrincipal
import com.vitz.music.badRequest
import com.vitz.music.db.count
import com.vitz.music.db.dbRead
import com.vitz.music.db.dbTx
import com.vitz.music.db.exec
import com.vitz.music.db.select
import com.vitz.music.notFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Instant
import java.util.UUID

fun ApplicationCall.user(): UserPrincipal = principal<UserPrincipal>()!!

fun ApplicationCall.limit(default: Int = 50, max: Int = 200): Int =
    (request.queryParameters["limit"]?.toIntOrNull() ?: default).coerceIn(1, max)

fun ApplicationCall.offset(): Int = (request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)

fun ApplicationCall.uuidParam(name: String): UUID =
    parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: badRequest("Некорректный $name")

fun ApplicationCall.uuidQuery(name: String): UUID? =
    request.queryParameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

fun Route.catalogRoutes(services: AppServices) = route("/api/v1") {
    val cfg = services.cfg
    val signer = services.signer

    authenticate("api") {
        get("/tracks") {
            val user = call.user()
            val artistId = call.uuidQuery("artistId")
            val albumId = call.uuidQuery("albumId")
            val limit = call.limit()
            val offset = call.offset()

            val page = dbRead { c ->
                val rows = Catalog.listTracks(c, artistId, albumId, limit, offset)
                val liked = Catalog.likedIds(c, user.userId, rows)
                val total = Catalog.countTracks(c, artistId, albumId)
                Page(
                    items = rows.map { it.toDto(cfg.publicUrl, signer, user.userId, it.id in liked) },
                    total = total,
                    offset = offset,
                    limit = limit,
                )
            }
            call.respond(page)
        }

        get("/tracks/{id}") {
            val user = call.user()
            val id = call.uuidParam("id")
            val dto = dbRead { c ->
                val row = Catalog.trackById(c, id) ?: return@dbRead null
                val liked = Catalog.likedIds(c, user.userId, listOf(row))
                row.toDto(cfg.publicUrl, signer, user.userId, row.id in liked)
            } ?: notFound("track_not_found")
            call.respond(dto)
        }

        get("/search") {
            val user = call.user()
            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            if (query.length < 2) badRequest("Запрос короче двух символов")
            val limit = call.limit(default = 20, max = 50)

            val response = dbRead { c ->
                val tracks = Catalog.searchTracks(c, query, limit)
                val liked = Catalog.likedIds(c, user.userId, tracks)
                SearchResponse(
                    tracks = tracks.map { it.toDto(cfg.publicUrl, signer, user.userId, it.id in liked) },
                    artists = Catalog.searchArtists(c, query, limit),
                    albums = Catalog.searchAlbums(c, cfg.publicUrl, query, limit),
                    playlists = Playlists.search(c, cfg.publicUrl, user.userId, query, limit),
                )
            }
            call.respond(response)
        }

        get("/artists") {
            val limit = call.limit()
            val offset = call.offset()
            val page = dbRead { c ->
                Page<ArtistDto>(
                    items = Catalog.listArtists(c, limit, offset),
                    total = c.count("select count(distinct artist_id) from tracks where deleted_at is null"),
                    offset = offset,
                    limit = limit,
                )
            }
            call.respond(page)
        }

        get("/albums") {
            val limit = call.limit()
            val offset = call.offset()
            val artistId = call.uuidQuery("artistId")
            val items = dbRead { c -> Catalog.listAlbums(c, cfg.publicUrl, artistId, limit, offset) }
            call.respond(Page(items = items, total = items.size.toLong(), offset = offset, limit = limit))
        }

        // --- понравившееся ---

        get("/likes") {
            val user = call.user()
            val limit = call.limit()
            val offset = call.offset()
            val page = dbRead { c ->
                val rows = Catalog.withRenditions(
                    c,
                    c.select(
                        """
                        ${Catalog.TRACK_SELECT}
                        join track_likes l on l.track_id = t.id and l.user_id = ?
                        where t.deleted_at is null
                        order by l.liked_at desc
                        limit ? offset ?
                        """.trimIndent(),
                        user.userId, limit, offset,
                    ) { Catalog.mapTrack(it) },
                )
                Page(
                    items = rows.map { it.toDto(cfg.publicUrl, signer, user.userId, true) },
                    total = c.count(
                        """
                        select count(*) from track_likes l join tracks t on t.id = l.track_id
                        where l.user_id = ? and t.deleted_at is null
                        """.trimIndent(),
                        user.userId,
                    ),
                    offset = offset,
                    limit = limit,
                )
            }
            call.respond(page)
        }

        put("/likes/{trackId}") {
            val user = call.user()
            val trackId = call.uuidParam("trackId")
            dbTx { c ->
                c.exec(
                    "insert into track_likes (user_id, track_id) values (?, ?) on conflict (user_id, track_id) do nothing",
                    user.userId, trackId,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/likes/{trackId}") {
            val user = call.user()
            val trackId = call.uuidParam("trackId")
            dbTx { c -> c.exec("delete from track_likes where user_id = ? and track_id = ?", user.userId, trackId) }
            call.respond(HttpStatusCode.NoContent)
        }

        // --- прослушивания ---

        post("/plays") {
            val user = call.user()
            val body = call.receive<PlaysRequest>()
            if (body.events.size > 500) badRequest("Слишком много событий за раз")
            dbTx { c ->
                for (event in body.events) {
                    val trackId = runCatching { UUID.fromString(event.trackId) }.getOrNull() ?: continue
                    c.exec(
                        """
                        insert into plays (user_id, track_id, started_at, ms_played, completed)
                        values (?, ?, ?, ?, ?)
                        """.trimIndent(),
                        user.userId, trackId, Instant.ofEpochMilli(event.startedAtEpochMs),
                        event.msPlayed, event.completed,
                    )
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/recent") {
            val user = call.user()
            val limit = call.limit(default = 30, max = 100)
            val items = dbRead { c ->
                val rows = Catalog.withRenditions(
                    c,
                    c.select(
                        """
                        ${Catalog.TRACK_SELECT}
                        join (
                            select track_id, max(started_at) as last_played
                            from plays where user_id = ? group by track_id
                        ) p on p.track_id = t.id
                        where t.deleted_at is null
                        order by p.last_played desc
                        limit ?
                        """.trimIndent(),
                        user.userId, limit,
                    ) { Catalog.mapTrack(it) },
                )
                val liked = Catalog.likedIds(c, user.userId, rows)
                rows.map { it.toDto(cfg.publicUrl, signer, user.userId, it.id in liked) }
            }
            call.respond(Page<TrackDto>(items = items, total = items.size.toLong(), offset = 0, limit = limit))
        }
    }
}
