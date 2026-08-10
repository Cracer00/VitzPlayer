package com.vitz.music.catalog

import com.vitz.music.api.AlbumDto
import com.vitz.music.api.ArtistDto
import com.vitz.music.api.MediaLink
import com.vitz.music.api.TrackDto
import com.vitz.music.db.count
import com.vitz.music.db.doubleOrNull
import com.vitz.music.db.exec
import com.vitz.music.db.intOrNull
import com.vitz.music.db.select
import com.vitz.music.db.selectOne
import com.vitz.music.db.uuid
import com.vitz.music.db.uuidOrNull
import com.vitz.music.media.UrlSigner
import com.vitz.music.media.coverUrl
import com.vitz.music.util.normalizeName
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class RenditionRow(
    val kind: String,
    val sha256: String,
    val ext: String,
    val sizeBytes: Long,
    val bitrate: Int?,
)

data class TrackRow(
    val id: UUID,
    val title: String,
    val artistId: UUID,
    val artistName: String,
    val albumId: UUID?,
    val albumTitle: String?,
    val durationMs: Long,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val genre: String?,
    val gainDb: Double?,
    val coverSha: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val renditions: List<RenditionRow> = emptyList(),
) {
    fun toDto(publicUrl: String, signer: UrlSigner, userId: UUID, liked: Boolean): TrackDto = TrackDto(
        id = id.toString(),
        title = title,
        artistId = artistId.toString(),
        artist = artistName,
        albumId = albumId?.toString(),
        album = albumTitle,
        durationMs = durationMs,
        trackNo = trackNo,
        discNo = discNo,
        year = year,
        genre = genre,
        gainDb = gainDb,
        coverUrl = coverUrl(publicUrl, coverSha),
        liked = liked,
        addedAtEpochMs = createdAt.toEpochMilli(),
        media = renditions.map {
            MediaLink(
                rendition = it.kind,
                url = signer.url(publicUrl, id, it.kind, userId),
                format = it.ext,
                sizeBytes = it.sizeBytes,
                bitrate = it.bitrate,
            )
        },
    )
}

object Catalog {

    const val TRACK_SELECT = """
        select t.id, t.title, t.duration_ms, t.track_no, t.disc_no, t.year, t.genre, t.gain_db,
               t.created_at, t.updated_at, t.deleted_at,
               a.id as artist_id, a.name as artist_name,
               al.id as album_id, al.title as album_title,
               cv.sha256 as cover_sha
        from tracks t
        join artists a on a.id = t.artist_id
        left join albums al on al.id = t.album_id
        left join covers cv on cv.id = t.cover_id
    """

    fun mapTrack(rs: ResultSet): TrackRow = TrackRow(
        id = rs.uuid("id"),
        title = rs.getString("title"),
        artistId = rs.uuid("artist_id"),
        artistName = rs.getString("artist_name"),
        albumId = rs.uuidOrNull("album_id"),
        albumTitle = rs.getString("album_title"),
        durationMs = rs.getLong("duration_ms"),
        trackNo = rs.intOrNull("track_no"),
        discNo = rs.intOrNull("disc_no"),
        year = rs.intOrNull("year"),
        genre = rs.getString("genre"),
        gainDb = rs.doubleOrNull("gain_db"),
        coverSha = rs.getString("cover_sha"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    /** Догружает рендиции пачкой: иначе на страницу в 50 треков уходит 50 запросов. */
    fun withRenditions(c: Connection, tracks: List<TrackRow>): List<TrackRow> {
        if (tracks.isEmpty()) return tracks
        val ids = c.createArrayOf("uuid", tracks.map { it.id }.toTypedArray())
        val byTrack = HashMap<UUID, MutableList<RenditionRow>>()
        c.prepareStatement(
            "select track_id, kind, sha256, ext, size_bytes, bitrate from renditions where track_id = any(?)",
        ).use { st ->
            st.setArray(1, ids)
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    byTrack.getOrPut(rs.uuid("track_id")) { mutableListOf() }.add(
                        RenditionRow(
                            kind = rs.getString("kind"),
                            sha256 = rs.getString("sha256"),
                            ext = rs.getString("ext"),
                            sizeBytes = rs.getLong("size_bytes"),
                            bitrate = rs.intOrNull("bitrate"),
                        ),
                    )
                }
            }
        }
        return tracks.map { it.copy(renditions = byTrack[it.id].orEmpty().sortedBy { r -> r.kind }) }
    }

    fun likedIds(c: Connection, userId: UUID, tracks: List<TrackRow>): Set<UUID> {
        if (tracks.isEmpty()) return emptySet()
        val ids = c.createArrayOf("uuid", tracks.map { it.id }.toTypedArray())
        val out = HashSet<UUID>()
        c.prepareStatement("select track_id from track_likes where user_id = ? and track_id = any(?)").use { st ->
            st.setObject(1, userId)
            st.setArray(2, ids)
            st.executeQuery().use { rs -> while (rs.next()) out.add(rs.uuid("track_id")) }
        }
        return out
    }

    // --- артисты и альбомы ---

    fun upsertArtist(c: Connection, name: String): UUID {
        val clean = name.trim().ifBlank { "Неизвестный исполнитель" }
        val norm = normalizeName(clean)
        c.selectOne("select id from artists where name_norm = ?", norm) { it.uuid("id") }?.let { return it }
        val id = UUID.randomUUID()
        c.exec("insert into artists (id, name, name_norm) values (?, ?, ?) on conflict (name_norm) do nothing", id, clean, norm)
        return c.selectOne("select id from artists where name_norm = ?", norm) { it.uuid("id") } ?: id
    }

    fun upsertAlbum(c: Connection, artistId: UUID, title: String, year: Int?, coverId: UUID?): UUID {
        val clean = title.trim()
        val norm = normalizeName(clean)
        val existing = c.selectOne(
            "select id from albums where artist_id = ? and title_norm = ?", artistId, norm,
        ) { it.uuid("id") }
        if (existing != null) {
            if (coverId != null) c.exec("update albums set cover_id = coalesce(cover_id, ?), updated_at = now() where id = ?", coverId, existing)
            if (year != null) c.exec("update albums set year = coalesce(year, ?), updated_at = now() where id = ?", year, existing)
            return existing
        }
        val id = UUID.randomUUID()
        c.exec(
            """
            insert into albums (id, artist_id, title, title_norm, year, cover_id)
            values (?, ?, ?, ?, ?, ?)
            on conflict (artist_id, title_norm) do nothing
            """.trimIndent(),
            id, artistId, clean, norm, year, coverId,
        )
        return c.selectOne("select id from albums where artist_id = ? and title_norm = ?", artistId, norm) { it.uuid("id") } ?: id
    }

    fun upsertCover(c: Connection, sha: String, width: Int, height: Int): UUID {
        c.selectOne("select id from covers where sha256 = ?", sha) { it.uuid("id") }?.let { return it }
        val id = UUID.randomUUID()
        c.exec(
            "insert into covers (id, sha256, width, height) values (?, ?, ?, ?) on conflict (sha256) do nothing",
            id, sha, width, height,
        )
        return c.selectOne("select id from covers where sha256 = ?", sha) { it.uuid("id") } ?: id
    }

    fun listArtists(c: Connection, limit: Int, offset: Int): List<ArtistDto> = c.select(
        """
        select a.id, a.name, count(t.id) filter (where t.deleted_at is null) as track_count
        from artists a
        left join tracks t on t.artist_id = a.id
        group by a.id, a.name
        having count(t.id) filter (where t.deleted_at is null) > 0
        order by a.name
        limit ? offset ?
        """.trimIndent(),
        limit, offset,
    ) { rs -> ArtistDto(rs.uuid("id").toString(), rs.getString("name"), rs.getInt("track_count")) }

    fun listAlbums(c: Connection, publicUrl: String, artistId: UUID?, limit: Int, offset: Int): List<AlbumDto> {
        val filter = if (artistId != null) "where al.artist_id = ?" else ""
        val args = buildList<Any?> {
            if (artistId != null) add(artistId)
            add(limit); add(offset)
        }
        return c.select(
            """
            select al.id, al.title, al.year, a.id as artist_id, a.name as artist_name, cv.sha256 as cover_sha,
                   count(t.id) filter (where t.deleted_at is null) as track_count
            from albums al
            join artists a on a.id = al.artist_id
            left join covers cv on cv.id = al.cover_id
            left join tracks t on t.album_id = al.id
            $filter
            group by al.id, al.title, al.year, a.id, a.name, cv.sha256
            order by a.name, al.year nulls last, al.title
            limit ? offset ?
            """.trimIndent(),
            *args.toTypedArray(),
        ) { rs ->
            AlbumDto(
                id = rs.uuid("id").toString(),
                title = rs.getString("title"),
                artistId = rs.uuid("artist_id").toString(),
                artist = rs.getString("artist_name"),
                year = rs.intOrNull("year"),
                coverUrl = coverUrl(publicUrl, rs.getString("cover_sha")),
                trackCount = rs.getInt("track_count"),
            )
        }
    }

    // --- треки ---

    fun findBySha(c: Connection, sha: String): UUID? =
        c.selectOne("select id from tracks where audio_sha256 = ? and deleted_at is null", sha) { it.uuid("id") }

    data class DuplicateCandidate(val id: UUID, val artist: String, val title: String, val durationMs: Long)

    /**
     * Почти-дубли: тот же артист и близкое название при разнице длительности меньше двух секунд.
     * Побайтово такие файлы различаются (другой битрейт, другие теги), поэтому sha их не ловит.
     */
    fun findNearDuplicates(c: Connection, artistNorm: String, titleNorm: String, durationMs: Long): List<DuplicateCandidate> = c.select(
        """
        select t.id, a.name as artist_name, t.title, t.duration_ms
        from tracks t
        join artists a on a.id = t.artist_id
        where t.deleted_at is null
          and abs(t.duration_ms - ?) < 2000
          and similarity(a.name_norm, ?) > 0.85
          and similarity(t.title_norm, ?) > 0.85
        limit 10
        """.trimIndent(),
        durationMs, artistNorm, titleNorm,
    ) { rs ->
        DuplicateCandidate(rs.uuid("id"), rs.getString("artist_name"), rs.getString("title"), rs.getLong("duration_ms"))
    }

    @Suppress("LongParameterList")
    fun insertTrack(
        c: Connection,
        artistId: UUID,
        albumId: UUID?,
        title: String,
        trackNo: Int?,
        discNo: Int?,
        durationMs: Long,
        year: Int?,
        genre: String?,
        coverId: UUID?,
        audioSha: String,
        sizeBytes: Long,
        format: String,
        bitrate: Int?,
        sampleRate: Int?,
        channels: Int?,
        gainDb: Double?,
        source: String,
        sourceUrl: String?,
        addedBy: UUID?,
    ): UUID {
        val id = UUID.randomUUID()
        c.exec(
            """
            insert into tracks (id, artist_id, album_id, title, title_norm, track_no, disc_no, duration_ms,
                                year, genre, cover_id, audio_sha256, size_bytes, format, bitrate, sample_rate,
                                channels, gain_db, source, source_url, added_by)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id, artistId, albumId, title.trim(), normalizeName(title), trackNo, discNo, durationMs,
            year, genre, coverId, audioSha, sizeBytes, format, bitrate, sampleRate,
            channels, gainDb, source, sourceUrl, addedBy,
        )
        return id
    }

    fun insertRendition(c: Connection, trackId: UUID, kind: String, sha: String, ext: String, size: Long, bitrate: Int?) {
        c.exec(
            """
            insert into renditions (track_id, kind, sha256, ext, size_bytes, bitrate)
            values (?, ?, ?, ?, ?, ?)
            on conflict (track_id, kind) do update
            set sha256 = excluded.sha256, ext = excluded.ext, size_bytes = excluded.size_bytes, bitrate = excluded.bitrate
            """.trimIndent(),
            trackId, kind, sha, ext, size, bitrate,
        )
    }

    fun trackById(c: Connection, id: UUID): TrackRow? =
        c.selectOne("$TRACK_SELECT where t.id = ? and t.deleted_at is null", id) { mapTrack(it) }
            ?.let { withRenditions(c, listOf(it)).first() }

    /** Подстрока для поиска по нормализованным полям; null — фильтра нет. */
    fun likePattern(query: String?): String? =
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { "%${normalizeName(it)}%" }

    fun listTracks(
        c: Connection,
        artistId: UUID?,
        albumId: UUID?,
        limit: Int,
        offset: Int,
        query: String? = null,
    ): List<TrackRow> {
        val pattern = likePattern(query)
        val conditions = buildList {
            add("t.deleted_at is null")
            if (artistId != null) add("t.artist_id = ?")
            if (albumId != null) add("t.album_id = ?")
            if (pattern != null) add("(t.title_norm like ? or a.name_norm like ?)")
        }
        val args = buildList<Any?> {
            if (artistId != null) add(artistId)
            if (albumId != null) add(albumId)
            if (pattern != null) { add(pattern); add(pattern) }
            add(limit); add(offset)
        }
        val order = if (albumId != null) "order by t.disc_no nulls first, t.track_no nulls last, t.title"
        else "order by t.created_at desc"
        val rows = c.select(
            "$TRACK_SELECT where ${conditions.joinToString(" and ")} $order limit ? offset ?",
            *args.toTypedArray(),
        ) { mapTrack(it) }
        return withRenditions(c, rows)
    }

    fun countTracks(c: Connection, artistId: UUID?, albumId: UUID?, query: String? = null): Long {
        val pattern = likePattern(query)
        val conditions = buildList {
            add("t.deleted_at is null")
            if (artistId != null) add("t.artist_id = ?")
            if (albumId != null) add("t.album_id = ?")
            if (pattern != null) add("(t.title_norm like ? or a.name_norm like ?)")
        }
        val args = buildList<Any?> {
            if (artistId != null) add(artistId)
            if (albumId != null) add(albumId)
            if (pattern != null) { add(pattern); add(pattern) }
        }
        return c.count(
            """
            select count(*) from tracks t join artists a on a.id = t.artist_id
            where ${conditions.joinToString(" and ")}
            """.trimIndent(),
            *args.toTypedArray(),
        )
    }

    fun searchTracks(c: Connection, query: String, limit: Int): List<TrackRow> {
        val norm = normalizeName(query)
        if (norm.isBlank()) return emptyList()
        val rows = c.select(
            """
            $TRACK_SELECT
            where t.deleted_at is null
              and (t.title_norm like ? or a.name_norm like ?
                   or similarity(t.title_norm, ?) > 0.3 or similarity(a.name_norm, ?) > 0.3)
            order by greatest(similarity(t.title_norm, ?), similarity(a.name_norm, ?)) desc, t.title
            limit ?
            """.trimIndent(),
            "%$norm%", "%$norm%", norm, norm, norm, norm, limit,
        ) { mapTrack(it) }
        return withRenditions(c, rows)
    }

    fun searchArtists(c: Connection, query: String, limit: Int): List<ArtistDto> {
        val norm = normalizeName(query)
        if (norm.isBlank()) return emptyList()
        return c.select(
            """
            select a.id, a.name, count(t.id) filter (where t.deleted_at is null) as track_count
            from artists a
            left join tracks t on t.artist_id = a.id
            where a.name_norm like ? or similarity(a.name_norm, ?) > 0.3
            group by a.id, a.name
            having count(t.id) filter (where t.deleted_at is null) > 0
            order by similarity(a.name_norm, ?) desc
            limit ?
            """.trimIndent(),
            "%$norm%", norm, norm, limit,
        ) { rs -> ArtistDto(rs.uuid("id").toString(), rs.getString("name"), rs.getInt("track_count")) }
    }

    fun searchAlbums(c: Connection, publicUrl: String, query: String, limit: Int): List<AlbumDto> {
        val norm = normalizeName(query)
        if (norm.isBlank()) return emptyList()
        return c.select(
            """
            select al.id, al.title, al.year, a.id as artist_id, a.name as artist_name, cv.sha256 as cover_sha,
                   count(t.id) filter (where t.deleted_at is null) as track_count
            from albums al
            join artists a on a.id = al.artist_id
            left join covers cv on cv.id = al.cover_id
            left join tracks t on t.album_id = al.id
            where al.title_norm like ? or similarity(al.title_norm, ?) > 0.3
            group by al.id, al.title, al.year, a.id, a.name, cv.sha256
            order by similarity(al.title_norm, ?) desc
            limit ?
            """.trimIndent(),
            "%$norm%", norm, norm, limit,
        ) { rs ->
            AlbumDto(
                id = rs.uuid("id").toString(),
                title = rs.getString("title"),
                artistId = rs.uuid("artist_id").toString(),
                artist = rs.getString("artist_name"),
                year = rs.intOrNull("year"),
                coverUrl = coverUrl(publicUrl, rs.getString("cover_sha")),
                trackCount = rs.getInt("track_count"),
            )
        }
    }

    /**
     * Удаление в корзину. Строка остаётся надгробием: по нему плеер на синхронизации узнаёт,
     * что трек надо убрать у себя. Файлы стирает [com.vitz.music.media.MediaGc], когда срок выйдет.
     */
    fun softDeleteTrack(c: Connection, id: UUID) {
        c.exec("update tracks set deleted_at = now(), updated_at = now() where id = ? and deleted_at is null", id)
    }

    /**
     * Возврат из корзины. Может не выйти: пока трек лежал удалённым, тот же файл могли залить
     * заново, а частичный уникальный индекс по `audio_sha256` двух живых строк не допускает.
     */
    fun restoreTrack(c: Connection, id: UUID): Boolean =
        c.exec("update tracks set deleted_at = null, updated_at = now() where id = ? and deleted_at is not null", id) > 0

    data class DeletedTrack(
        val id: UUID,
        val title: String,
        val artistName: String,
        val deletedAt: Instant,
        val sizeBytes: Long,
    )

    fun listDeletedTracks(c: Connection, limit: Int): List<DeletedTrack> = c.select(
        """
        select t.id, t.title, a.name as artist_name, t.deleted_at,
               coalesce((select sum(r.size_bytes) from renditions r where r.track_id = t.id), 0) as size_bytes
        from tracks t
        join artists a on a.id = t.artist_id
        where t.deleted_at is not null
        order by t.deleted_at desc
        limit ?
        """.trimIndent(),
        limit,
    ) { rs ->
        DeletedTrack(
            id = rs.uuid("id"),
            title = rs.getString("title"),
            artistName = rs.getString("artist_name"),
            deletedAt = rs.getTimestamp("deleted_at").toInstant(),
            sizeBytes = rs.getLong("size_bytes"),
        )
    }

    @Suppress("LongParameterList")
    fun updateTrack(
        c: Connection,
        id: UUID,
        title: String,
        artistName: String,
        albumTitle: String?,
        year: Int?,
        genre: String?,
        trackNo: Int?,
    ) {
        val artistId = upsertArtist(c, artistName)
        val albumId = albumTitle?.takeIf { it.isNotBlank() }?.let { upsertAlbum(c, artistId, it, year, null) }
        c.exec(
            """
            update tracks
            set title = ?, title_norm = ?, artist_id = ?, album_id = ?, year = ?, genre = ?, track_no = ?,
                updated_at = now()
            where id = ?
            """.trimIndent(),
            title.trim(), normalizeName(title), artistId, albumId, year, genre?.takeIf { it.isNotBlank() }, trackNo, id,
        )
    }

    /** Склейка дублей артиста: треки и альбомы переезжают, пустой артист удаляется. */
    fun mergeArtists(c: Connection, fromId: UUID, intoId: UUID) {
        if (fromId == intoId) return
        c.exec("update tracks set artist_id = ?, updated_at = now() where artist_id = ?", intoId, fromId)
        // Альбом с тем же названием у целевого артиста уже может быть — тогда переносим треки в него.
        val moved = c.select(
            "select id, title_norm from albums where artist_id = ?", fromId,
        ) { rs -> rs.uuid("id") to rs.getString("title_norm") }
        for ((albumId, titleNorm) in moved) {
            val target = c.selectOne(
                "select id from albums where artist_id = ? and title_norm = ?", intoId, titleNorm,
            ) { it.uuid("id") }
            if (target != null) {
                c.exec("update tracks set album_id = ?, updated_at = now() where album_id = ?", target, albumId)
                c.exec("delete from albums where id = ?", albumId)
            } else {
                c.exec("update albums set artist_id = ? where id = ?", intoId, albumId)
            }
        }
        c.exec("delete from artists where id = ?", fromId)
    }
}
