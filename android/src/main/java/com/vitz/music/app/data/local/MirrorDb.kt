package com.vitz.music.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.vitz.music.api.MediaLink
import com.vitz.music.api.PlaylistDto
import com.vitz.music.api.TrackDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Локальное зеркало каталога.
 *
 * Без Room намеренно: он тянет KSP, версия которого жёстко привязана к версии Kotlin, —
 * лишний риск сборки ради пяти таблиц, которые всё равно пишутся руками. Здесь нет ни
 * миграций сложнее «пересоздать», ни связей, которых не видно глазом.
 *
 * Зеркало наполняется из `/sync` и позволяет показывать библиотеку без сети. Играть без сети
 * можно только скачанное: ссылки на медиа подписаны и живут считанные часы.
 */
class MirrorDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _catalogRevision = MutableStateFlow(0L)
    private val _downloadsRevision = MutableStateFlow(0L)

    /**
     * Счётчики записей в зеркало.
     *
     * Экраны читают базу разово, при открытии, и без такого сигнала синхронизация наполняет
     * её молча: новые треки лежат в SQLite, а список показывает то, что успело приехать
     * к моменту входа. Счётчик, а не флаг, — чтобы подписчик не пропустил изменение,
     * случившееся между двумя его чтениями.
     *
     * Каталог и загрузки разведены намеренно: скачанный файл меняет одну иконку в строке
     * и не должен заставлять список треков перечитываться целиком.
     */
    val catalogRevision: StateFlow<Long> = _catalogRevision.asStateFlow()
    val downloadsRevision: StateFlow<Long> = _downloadsRevision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            create table tracks (
                id text primary key,
                title text not null,
                artist_id text not null,
                artist text not null,
                album_id text,
                album text,
                duration_ms integer not null,
                track_no integer,
                disc_no integer,
                year integer,
                genre text,
                gain_db real,
                cover_url text,
                added_at integer not null,
                media_json text not null,
                title_norm text not null,
                artist_norm text not null
            )
            """.trimIndent(),
        )
        db.execSQL("create index tracks_added_idx on tracks (added_at desc)")
        db.execSQL("create index tracks_search_idx on tracks (title_norm, artist_norm)")
        db.execSQL("create table likes (track_id text primary key, liked_at integer not null)")
        db.execSQL(
            """
            create table playlists (
                id text primary key,
                title text not null,
                description text,
                cover_url text,
                updated_at integer not null
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            create table playlist_items (
                playlist_id text not null,
                track_id text not null,
                position integer not null,
                primary key (playlist_id, position)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            create table downloads (
                track_id text primary key,
                rendition text not null,
                path text not null,
                size_bytes integer not null,
                state text not null,
                created_at integer not null
            )
            """.trimIndent(),
        )
        db.execSQL("create table meta (key text primary key, value text not null)")
    }

    /**
     * Зеркало — это кеш, а не источник правды: при смене схемы его дешевле пересобрать
     * из `/sync`, чем писать миграции. Скачанные файлы при этом не трогаем — они на диске,
     * и их список восстановится сканированием каталога загрузок.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("tracks", "likes", "playlists", "playlist_items", "meta").forEach {
            db.execSQL("drop table if exists $it")
        }
        onCreate(db)
    }

    // --- курсор синхронизации ---

    fun syncCursor(): String? = readableDatabase
        .rawQuery("select value from meta where key = ?", arrayOf(KEY_CURSOR))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun saveSyncCursor(cursor: String) {
        writableDatabase.insertWithOnConflict(
            "meta",
            null,
            ContentValues().apply {
                put("key", KEY_CURSOR)
                put("value", cursor)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clearAll() {
        writableDatabase.transaction { db ->
            listOf("tracks", "likes", "playlists", "playlist_items", "meta").forEach {
                db.delete(it, null, null)
            }
        }
        _catalogRevision.update { it + 1 }
    }

    // --- запись из синхронизации ---

    fun upsertTracks(tracks: List<TrackDto>) {
        if (tracks.isEmpty()) return
        writableDatabase.transaction { db ->
            tracks.forEach { track ->
                db.insertWithOnConflict("tracks", null, track.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        _catalogRevision.update { it + 1 }
    }

    /**
     * Трек исчез с сервера. Вместе со строкой каталога уходит и запись о загрузке: иначе
     * скачанный файл остался бы лежать в памяти магнитолы, на который уже ничто не ссылается.
     *
     * @return пути скачанных файлов, которых больше некому держать. Снять их с диска — дело
     * [com.vitz.music.app.data.Downloads]: зеркало работает только с базой.
     */
    fun deleteTracks(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val orphaned = mutableListOf<String>()
        writableDatabase.transaction { db ->
            ids.forEach { id ->
                db.rawQuery("select path from downloads where track_id = ?", arrayOf(id))
                    .use { if (it.moveToFirst()) orphaned += it.getString(0) }
                db.delete("tracks", "id = ?", arrayOf(id))
                db.delete("likes", "track_id = ?", arrayOf(id))
                db.delete("playlist_items", "track_id = ?", arrayOf(id))
                db.delete("downloads", "track_id = ?", arrayOf(id))
            }
        }
        _catalogRevision.update { it + 1 }
        if (orphaned.isNotEmpty()) _downloadsRevision.update { it + 1 }
        return orphaned
    }

    /**
     * Лайки сервер отдаёт целиком на каждой странице синхронизации, поэтому счётчик двигаем
     * только при настоящем расхождении: иначе каждая проверка связи выглядела бы как
     * изменение каталога и дёргала бы открытый список.
     */
    fun replaceLikes(trackIds: List<String>) {
        val changed = likedIds() != trackIds.toSet()
        writableDatabase.transaction { db ->
            db.delete("likes", null, null)
            trackIds.forEachIndexed { index, id ->
                db.insertWithOnConflict(
                    "likes",
                    null,
                    ContentValues().apply {
                        put("track_id", id)
                        // Порядок прихода с сервера — уже по времени лайка, сохраняем его.
                        put("liked_at", (trackIds.size - index).toLong())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
        if (changed) _catalogRevision.update { it + 1 }
    }

    /** Плейлисты приходят так же целиком — и так же сравниваются перед сигналом. */
    fun replacePlaylists(playlists: List<Pair<PlaylistDto, List<String>>>, deletedIds: List<String>) {
        val known = this.playlists().associate { it.id to it.updatedAtEpochMs }
        val changed = playlists.any { (playlist, _) -> known[playlist.id] != playlist.updatedAtEpochMs } ||
            deletedIds.any { it in known }

        writableDatabase.transaction { db ->
            deletedIds.forEach { id ->
                db.delete("playlists", "id = ?", arrayOf(id))
                db.delete("playlist_items", "playlist_id = ?", arrayOf(id))
            }
            playlists.forEach { (playlist, trackIds) ->
                db.insertWithOnConflict(
                    "playlists",
                    null,
                    ContentValues().apply {
                        put("id", playlist.id)
                        put("title", playlist.title)
                        put("description", playlist.description)
                        put("cover_url", playlist.coverUrl)
                        put("updated_at", playlist.updatedAtEpochMs)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
                db.delete("playlist_items", "playlist_id = ?", arrayOf(playlist.id))
                trackIds.forEachIndexed { index, trackId ->
                    db.insertWithOnConflict(
                        "playlist_items",
                        null,
                        ContentValues().apply {
                            put("playlist_id", playlist.id)
                            put("track_id", trackId)
                            put("position", index)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
        }
        if (changed) _catalogRevision.update { it + 1 }
    }

    // --- чтение ---

    /**
     * Сколько всего строк подходит под те же условия, что и [tracks]. Именно под те же:
     * по этому числу список решает, что страницы кончились, и общий счёт вместо
     * отфильтрованного заставлял бы его просить несуществующую страницу.
     */
    fun trackCount(query: String? = null, likedOnly: Boolean = false, downloadedOnly: Boolean = false): Int {
        val criteria = criteria(query, likedOnly, downloadedOnly)
        return readableDatabase
            .rawQuery("select count(*) from tracks t where ${criteria.where}", criteria.args.toTypedArray())
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun tracks(offset: Int, limit: Int, query: String?, likedOnly: Boolean, downloadedOnly: Boolean): List<TrackDto> {
        val criteria = criteria(query, likedOnly, downloadedOnly)
        val order = if (likedOnly) "order by (select liked_at from likes where track_id = t.id) desc"
        else "order by t.added_at desc"

        val args = criteria.args + listOf(limit.toString(), offset.toString())
        return readableDatabase.rawQuery(
            "select t.* from tracks t where ${criteria.where} $order limit ? offset ?",
            args.toTypedArray(),
        ).use { cursor -> cursor.mapAll { it.toTrack() } }
    }

    private class Criteria(val where: String, val args: List<String>)

    private fun criteria(query: String?, likedOnly: Boolean, downloadedOnly: Boolean): Criteria {
        val where = StringBuilder("1 = 1")
        val args = mutableListOf<String>()
        if (likedOnly) where.append(" and t.id in (select track_id from likes)")
        if (downloadedOnly) where.append(" and t.id in (select track_id from downloads where state = 'done')")
        query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" and (t.title_norm like ? or t.artist_norm like ?)")
            args += "%$it%"
            args += "%$it%"
        }
        return Criteria(where.toString(), args)
    }

    /** Треки по списку идентификаторов, в том же порядке: так восстанавливается очередь. */
    fun tracksByIds(ids: List<String>): List<TrackDto> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val byId = readableDatabase.rawQuery(
            "select * from tracks where id in ($placeholders)",
            ids.toTypedArray(),
        ).use { cursor -> cursor.mapAll { it.toTrack() } }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun likedIds(): Set<String> = readableDatabase
        .rawQuery("select track_id from likes", null)
        .use { cursor -> cursor.mapAll { it.getString(0) }.toSet() }

    fun setLiked(trackId: String, liked: Boolean) {
        if (liked) {
            writableDatabase.insertWithOnConflict(
                "likes",
                null,
                ContentValues().apply {
                    put("track_id", trackId)
                    put("liked_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } else {
            writableDatabase.delete("likes", "track_id = ?", arrayOf(trackId))
        }
    }

    fun playlists(): List<PlaylistDto> = readableDatabase.rawQuery(
        """
        select p.*, (select count(*) from playlist_items i where i.playlist_id = p.id) as track_count
        from playlists p order by p.updated_at desc
        """.trimIndent(),
        null,
    ).use { cursor ->
        cursor.mapAll {
            PlaylistDto(
                id = it.text("id"),
                title = it.text("title"),
                description = it.textOrNull("description"),
                trackCount = it.getInt(it.getColumnIndexOrThrow("track_count")),
                coverUrl = it.textOrNull("cover_url"),
                updatedAtEpochMs = it.getLong(it.getColumnIndexOrThrow("updated_at")),
            )
        }
    }

    fun playlistTracks(playlistId: String): List<TrackDto> = readableDatabase.rawQuery(
        """
        select t.* from playlist_items i
        join tracks t on t.id = i.track_id
        where i.playlist_id = ?
        order by i.position
        """.trimIndent(),
        arrayOf(playlistId),
    ).use { cursor -> cursor.mapAll { it.toTrack() } }

    // --- загрузки ---

    fun downloads(): List<DownloadRow> = readableDatabase.rawQuery(
        """
        select d.*, t.title, t.artist from downloads d
        left join tracks t on t.id = d.track_id
        order by d.created_at desc
        """.trimIndent(),
        null,
    ).use { cursor ->
        cursor.mapAll {
            DownloadRow(
                trackId = it.text("track_id"),
                rendition = it.text("rendition"),
                path = it.text("path"),
                sizeBytes = it.getLong(it.getColumnIndexOrThrow("size_bytes")),
                state = it.text("state"),
                title = it.textOrNull("title") ?: "Трек",
                artist = it.textOrNull("artist").orEmpty(),
            )
        }
    }

    fun downloadedIds(): Set<String> = readableDatabase
        .rawQuery("select track_id from downloads where state = 'done'", null)
        .use { cursor -> cursor.mapAll { it.getString(0) }.toSet() }

    fun downloadPath(trackId: String): String? = readableDatabase
        .rawQuery("select path from downloads where track_id = ? and state = 'done'", arrayOf(trackId))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun putDownload(row: DownloadRow) {
        writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            ContentValues().apply {
                put("track_id", row.trackId)
                put("rendition", row.rendition)
                put("path", row.path)
                put("size_bytes", row.sizeBytes)
                put("state", row.state)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _downloadsRevision.update { it + 1 }
    }

    fun removeDownload(trackId: String) {
        val removed = writableDatabase.delete("downloads", "track_id = ?", arrayOf(trackId))
        if (removed > 0) _downloadsRevision.update { it + 1 }
    }

    fun clearDownloads() {
        val removed = writableDatabase.delete("downloads", null, null)
        if (removed > 0) _downloadsRevision.update { it + 1 }
    }

    // --- преобразования ---

    private fun TrackDto.toValues() = ContentValues().apply {
        put("id", id)
        put("title", title)
        put("artist_id", artistId)
        put("artist", artist)
        put("album_id", albumId)
        put("album", album)
        put("duration_ms", durationMs)
        put("track_no", trackNo)
        put("disc_no", discNo)
        put("year", year)
        put("genre", genre)
        put("gain_db", gainDb)
        put("cover_url", coverUrl)
        put("added_at", addedAtEpochMs)
        put("media_json", json.encodeToString(media))
        put("title_norm", title.lowercase())
        put("artist_norm", artist.lowercase())
    }

    private fun Cursor.toTrack(): TrackDto = TrackDto(
        id = text("id"),
        title = text("title"),
        artistId = text("artist_id"),
        artist = text("artist"),
        albumId = textOrNull("album_id"),
        album = textOrNull("album"),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        trackNo = intOrNull("track_no"),
        discNo = intOrNull("disc_no"),
        year = intOrNull("year"),
        genre = textOrNull("genre"),
        gainDb = doubleOrNull("gain_db"),
        coverUrl = textOrNull("cover_url"),
        addedAtEpochMs = getLong(getColumnIndexOrThrow("added_at")),
        media = runCatching {
            json.decodeFromString<List<MediaLink>>(text("media_json"))
        }.getOrDefault(emptyList()),
    )

    private companion object {
        const val NAME = "vitz-music-mirror.db"
        const val VERSION = 1
        const val KEY_CURSOR = "sync_cursor"
    }
}

data class DownloadRow(
    val trackId: String,
    val rendition: String,
    val path: String,
    val sizeBytes: Long,
    val state: String,
    val title: String = "",
    val artist: String = "",
)

// --- мелкие помощники, чтобы запросы читались ---

private inline fun <T> SQLiteDatabase.transaction(block: (SQLiteDatabase) -> T): T {
    beginTransaction()
    return try {
        val result = block(this)
        setTransactionSuccessful()
        result
    } finally {
        endTransaction()
    }
}

private inline fun <T> Cursor.mapAll(map: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out.add(map(this))
    return out
}

private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column))

private fun Cursor.textOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun Cursor.intOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}

private fun Cursor.doubleOrNull(column: String): Double? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getDouble(index)
}
