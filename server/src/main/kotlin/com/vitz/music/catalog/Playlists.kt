package com.vitz.music.catalog

import com.vitz.music.api.PlaylistDto
import com.vitz.music.db.exec
import com.vitz.music.db.instant
import com.vitz.music.db.select
import com.vitz.music.db.selectOne
import com.vitz.music.db.uuid
import com.vitz.music.media.coverUrl
import com.vitz.music.util.normalizeName
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

object Playlists {

    private const val SELECT = """
        select p.id, p.title, p.description, p.updated_at,
               (select count(*) from playlist_items i where i.playlist_id = p.id) as track_count,
               (select cv.sha256
                  from playlist_items i
                  join tracks t on t.id = i.track_id
                  join covers cv on cv.id = t.cover_id
                 where i.playlist_id = p.id
                 order by i.position
                 limit 1) as cover_sha
        from playlists p
    """

    private fun map(rs: ResultSet, publicUrl: String) = PlaylistDto(
        id = rs.uuid("id").toString(),
        title = rs.getString("title"),
        description = rs.getString("description"),
        trackCount = rs.getInt("track_count"),
        coverUrl = coverUrl(publicUrl, rs.getString("cover_sha")),
        updatedAtEpochMs = rs.instant("updated_at").toEpochMilli(),
    )

    fun listFor(c: Connection, publicUrl: String, userId: UUID): List<PlaylistDto> = c.select(
        "$SELECT where p.user_id = ? and p.deleted_at is null order by p.updated_at desc",
        userId,
    ) { map(it, publicUrl) }

    fun byId(c: Connection, publicUrl: String, userId: UUID, id: UUID): PlaylistDto? = c.selectOne(
        "$SELECT where p.id = ? and p.user_id = ? and p.deleted_at is null",
        id, userId,
    ) { map(it, publicUrl) }

    fun search(c: Connection, publicUrl: String, userId: UUID, query: String, limit: Int): List<PlaylistDto> {
        val norm = normalizeName(query)
        if (norm.isBlank()) return emptyList()
        return c.select(
            "$SELECT where p.user_id = ? and p.deleted_at is null and lower(p.title) like ? order by p.updated_at desc limit ?",
            userId, "%${query.lowercase()}%", limit,
        ) { map(it, publicUrl) }
    }

    fun create(c: Connection, userId: UUID, title: String, description: String?): UUID {
        val id = UUID.randomUUID()
        c.exec(
            "insert into playlists (id, user_id, title, description) values (?, ?, ?, ?)",
            id, userId, title.trim(), description?.takeIf { it.isNotBlank() },
        )
        return id
    }

    fun update(c: Connection, userId: UUID, id: UUID, title: String?, description: String?): Boolean {
        val updated = c.exec(
            """
            update playlists
            set title = coalesce(?, title), description = coalesce(?, description), updated_at = now()
            where id = ? and user_id = ? and deleted_at is null
            """.trimIndent(),
            title?.trim()?.takeIf { it.isNotBlank() }, description, id, userId,
        )
        return updated == 1
    }

    fun softDelete(c: Connection, userId: UUID, id: UUID): Boolean = c.exec(
        "update playlists set deleted_at = now(), updated_at = now() where id = ? and user_id = ? and deleted_at is null",
        id, userId,
    ) == 1

    fun owns(c: Connection, userId: UUID, id: UUID): Boolean = c.selectOne(
        "select 1 from playlists where id = ? and user_id = ? and deleted_at is null", id, userId,
    ) { it.getInt(1) } != null

    fun trackIds(c: Connection, playlistId: UUID): List<UUID> = c.select(
        """
        select i.track_id from playlist_items i
        join tracks t on t.id = i.track_id
        where i.playlist_id = ? and t.deleted_at is null
        order by i.position
        """.trimIndent(),
        playlistId,
    ) { it.uuid("track_id") }

    fun items(c: Connection, playlistId: UUID): List<TrackRow> {
        val rows = c.select(
            """
            ${Catalog.TRACK_SELECT}
            join playlist_items i on i.track_id = t.id and i.playlist_id = ?
            where t.deleted_at is null
            order by i.position
            """.trimIndent(),
            playlistId,
        ) { Catalog.mapTrack(it) }
        return Catalog.withRenditions(c, rows)
    }

    /** Добавление в конец (или с указанной позиции) с перенумерацией хвоста. */
    fun addItems(c: Connection, playlistId: UUID, trackIds: List<UUID>, position: Int?) {
        if (trackIds.isEmpty()) return
        val existing = c.select(
            "select track_id from playlist_items where playlist_id = ?", playlistId,
        ) { it.uuid("track_id") }.toSet()
        val toAdd = trackIds.filter { it !in existing }.distinct()
        if (toAdd.isEmpty()) return

        val maxPosition = c.selectOne(
            "select coalesce(max(position), -1) from playlist_items where playlist_id = ?", playlistId,
        ) { it.getInt(1) } ?: -1

        val insertAt = position?.coerceIn(0, maxPosition + 1) ?: (maxPosition + 1)
        if (insertAt <= maxPosition) {
            c.exec(
                "update playlist_items set position = position + ? where playlist_id = ? and position >= ?",
                toAdd.size, playlistId, insertAt,
            )
        }
        toAdd.forEachIndexed { index, trackId ->
            // Арбитр указан явно: при `on conflict do nothing` без цели Postgres пытается взять
            // в том числе отложенное ограничение уникальности позиции и отказывается работать.
            c.exec(
                """
                insert into playlist_items (playlist_id, track_id, position) values (?, ?, ?)
                on conflict (playlist_id, track_id) do nothing
                """.trimIndent(),
                playlistId, trackId, insertAt + index,
            )
        }
        touch(c, playlistId)
    }

    fun removeItems(c: Connection, playlistId: UUID, trackIds: List<UUID>) {
        if (trackIds.isEmpty()) return
        val array = c.createArrayOf("uuid", trackIds.toTypedArray())
        c.prepareStatement("delete from playlist_items where playlist_id = ? and track_id = any(?)").use { st ->
            st.setObject(1, playlistId)
            st.setArray(2, array)
            st.executeUpdate()
        }
        renumber(c, playlistId)
        touch(c, playlistId)
    }

    /**
     * Полная перестановка. Уникальность позиций объявлена deferrable — иначе промежуточное
     * состояние внутри одного апдейта нарушало бы ограничение.
     */
    fun reorder(c: Connection, playlistId: UUID, trackIds: List<UUID>) {
        val known = c.select(
            "select track_id from playlist_items where playlist_id = ?", playlistId,
        ) { it.uuid("track_id") }
        val ordered = trackIds.filter { it in known.toSet() }.distinct()
        val rest = known.filter { it !in ordered.toSet() }
        (ordered + rest).forEachIndexed { index, trackId ->
            c.exec(
                "update playlist_items set position = ? where playlist_id = ? and track_id = ?",
                index, playlistId, trackId,
            )
        }
        touch(c, playlistId)
    }

    private fun renumber(c: Connection, playlistId: UUID) {
        c.exec(
            """
            update playlist_items i
            set position = numbered.rn - 1
            from (
                select track_id, row_number() over (order by position) as rn
                from playlist_items where playlist_id = ?
            ) numbered
            where i.playlist_id = ? and i.track_id = numbered.track_id
            """.trimIndent(),
            playlistId, playlistId,
        )
    }

    private fun touch(c: Connection, playlistId: UUID) {
        c.exec("update playlists set updated_at = now() where id = ?", playlistId)
    }
}
