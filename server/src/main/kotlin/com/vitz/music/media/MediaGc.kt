package com.vitz.music.media

import com.vitz.music.Config
import com.vitz.music.db.Db
import com.vitz.music.db.exec
import com.vitz.music.db.select
import com.vitz.music.db.selectOne
import com.vitz.music.db.uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Разделы каталога: имя файла начинается с sha содержимого, по нему и решается судьба файла. */
private val CATALOG_KINDS = listOf("audio", "rendition", "cover")

/** Разделы с полуфабрикатами: сюда попадает загруженное и промежуточное. */
private val TEMP_KINDS = listOf("tmp", "incoming")

/** Задания, чей входной файл ещё может понадобиться. `done` свой файл уже разобрал. */
private const val BUSY_JOB_STATUSES = "'queued', 'running', 'failed', 'needs_attention'"

/** За один плановый проход стираем не больше этого — чтобы не держать транзакцию минутами. */
private const val PURGE_BATCH = 500

/** Первый проход не на старте: пусть сервер поднимется, а отложенные задания доедут. */
private const val START_DELAY_MS = 2 * 60 * 1000L

data class Swept(val files: Int, val bytes: Long)

/**
 * Уборка хранилища.
 *
 * Удаление трека из админки — мягкое: строка с `deleted_at` служит надгробием, по которому
 * плеер на синхронизации понимает, что трек надо убрать и у себя. Поэтому файлы уходят не
 * сразу, а когда надгробие отстоит положенный срок: до этого момента трек ещё можно вернуть.
 *
 * Сами файлы адресуются хешем содержимого и переиспользуются, поэтому «файлы этого трека»
 * удалить нельзя — можно удалить только те, на которые после стирания строк никто не ссылается.
 */
class MediaGc(private val store: MediaStore, private val cfg: Config) {

    private val log = LoggerFactory.getLogger(MediaGc::class.java)

    data class Report(
        val at: Instant = Instant.now(),
        val tracksPurged: Int = 0,
        val filesDeleted: Int = 0,
        val bytesFreed: Long = 0,
        val tempDeleted: Int = 0,
        val rowsDeleted: Int = 0,
    ) {
        val isEmpty: Boolean
            get() = tracksPurged == 0 && filesDeleted == 0 && tempDeleted == 0 && rowsDeleted == 0
    }

    /** Итог последнего прохода — показывается в админке. Переживать перезапуск ему незачем. */
    @Volatile
    var last: Report? = null
        private set

    fun start(scope: CoroutineScope) {
        val minutes = cfg.gcIntervalMinutes
        if (minutes <= 0) {
            log.warn("Фоновая уборка медиа выключена: VM_GC_INTERVAL_MINUTES=0")
            return
        }
        log.info(
            "Уборка медиа: раз в {} мин, корзина {} дн., бесхозный файл сносится через {} ч",
            minutes, cfg.trashRetentionDays, cfg.gcOrphanGraceHours,
        )
        scope.launch(Dispatchers.IO) {
            delay(START_DELAY_MS)
            while (true) {
                runCatching { runOnce() }
                    .onSuccess { report -> if (!report.isEmpty) log.info("Уборка: {}", report) }
                    .onFailure { log.error("Уборка медиа сорвалась", it) }
                delay(minutes * 60_000L)
            }
        }
    }

    /** Плановый проход: отстоявшая корзина, осиротевшие строки, бесхозные файлы, старый tmp. */
    fun runOnce(): Report {
        val purged = Db.tx { c -> purgeRows(c, expiredTrackIds(c)) }
        val rowsDeleted = tidyRows()
        val fromTracks = deleteUnreferenced(purged.files)

        val cutoff = Instant.now().minusSeconds(cfg.gcOrphanGraceHours * 3600)
        val referenced = Db.read { c -> referencedShas(c) }
        val swept = sweepUnreferenced(store, CATALOG_KINDS, referenced, cutoff)

        return Report(
            tracksPurged = purged.tracks,
            filesDeleted = fromTracks.files + swept.files,
            bytesFreed = fromTracks.bytes + swept.bytes,
            tempDeleted = sweepTemp(cutoff),
            rowsDeleted = rowsDeleted,
        ).also { last = it }
    }

    /**
     * Стереть один трек сейчас, не дожидаясь срока корзины: админ нажал кнопку осознанно.
     * Отсрочку для бесхозных файлов здесь не выдерживаем — стираются ровно те файлы этого
     * трека, на которые после удаления строк уже никто не ссылается.
     */
    fun purgeTrack(id: UUID): Report {
        val purged = Db.tx { c ->
            // Только из корзины. У живого трека надгробия нет, и плеер, который сейчас вне
            // сети, об исчезновении строки не узнал бы уже никогда.
            val trashed = c.selectOne(
                "select id from tracks where id = ? and deleted_at is not null", id,
            ) { it.uuid("id") }
            purgeRows(c, listOfNotNull(trashed))
        }
        val rowsDeleted = tidyRows()
        val deleted = deleteUnreferenced(purged.files)
        return Report(
            tracksPurged = purged.tracks,
            filesDeleted = deleted.files,
            bytesFreed = deleted.bytes,
            rowsDeleted = rowsDeleted,
        ).also { last = it }
    }

    // ---------- строки ----------

    private fun expiredTrackIds(c: Connection): List<UUID> = c.select(
        """
        select id from tracks
        where deleted_at is not null and deleted_at < now() - make_interval(days => ?)
        order by deleted_at
        limit ?
        """.trimIndent(),
        cfg.trashRetentionDays.toInt(), PURGE_BATCH,
    ) { it.uuid("id") }

    private data class Purged(val tracks: Int, val files: List<StoredFile>)

    private data class StoredFile(val kind: String, val sha: String)

    /**
     * Стирает строки треков насовсем. Рендиции, лайки, позиции в плейлистах и прослушивания
     * уезжают каскадом; sha файлов забираем до удаления, потом их уже не найти.
     */
    private fun purgeRows(c: Connection, ids: List<UUID>): Purged {
        if (ids.isEmpty()) return Purged(0, emptyList())
        val files = mutableListOf<StoredFile>()
        forEachRow(c, "select kind, sha256 from renditions where track_id = any(?)", ids) { rs ->
            // Оригинал лежит в audio, всё остальное — перекодировки.
            val kind = if (rs.getString("kind") == "original") "audio" else "rendition"
            files += StoredFile(kind, rs.getString("sha256"))
        }
        forEachRow(c, "select audio_sha256 from tracks where id = any(?)", ids) { rs ->
            files += StoredFile("audio", rs.getString("audio_sha256"))
        }
        forEachRow(
            c,
            "select cv.sha256 from covers cv join tracks t on t.cover_id = cv.id where t.id = any(?)",
            ids,
        ) { rs ->
            files += StoredFile("cover", rs.getString("sha256"))
        }
        val deleted = c.prepareStatement("delete from tracks where id = any(?)").use { st ->
            st.setArray(1, c.createArrayOf("uuid", ids.toTypedArray()))
            st.executeUpdate()
        }
        return Purged(deleted, files.distinct())
    }

    /**
     * Отдельной транзакцией и мягко. Ингест умеет подцепить пустого артиста ровно в тот момент,
     * когда мы решили, что он никому не нужен; ссылка `on delete restrict` такую гонку ловит и
     * валит транзакцию. Ронять из-за этого весь проход незачем — уберём в следующий раз.
     */
    private fun tidyRows(): Int = runCatching { Db.tx { c -> deleteOrphanRows(c) } }
        .onFailure { log.warn("Пустые записи убрать не вышло, попробую в следующий проход: {}", it.message) }
        .getOrDefault(0)

    /**
     * Записи, которые ничего уже не описывают: пустые альбомы и артисты, обложки без владельца.
     * Порядок важен — обложку держит альбом, так что альбом должен уйти первым, иначе на
     * освобождение обложки уходил бы лишний проход.
     *
     * Берём только то, что старше отсрочки: свежая пустая запись — это, скорее всего, ингест,
     * который завёл артиста и вот-вот привяжет к нему трек.
     */
    private fun deleteOrphanRows(c: Connection): Int {
        val graceHours = cfg.gcOrphanGraceHours.toInt()
        var deleted = c.exec(
            """
            delete from albums al
            where not exists (select 1 from tracks where album_id = al.id)
              and al.created_at < now() - make_interval(hours => ?)
            """.trimIndent(),
            graceHours,
        )
        deleted += c.exec(
            """
            delete from artists ar
            where not exists (select 1 from tracks where artist_id = ar.id)
              and not exists (select 1 from albums where artist_id = ar.id)
              and ar.created_at < now() - make_interval(hours => ?)
            """.trimIndent(),
            graceHours,
        )
        deleted += c.exec(
            """
            delete from covers cv
            where not exists (select 1 from tracks where cover_id = cv.id)
              and not exists (select 1 from albums where cover_id = cv.id)
              and not exists (select 1 from playlists where cover_id = cv.id)
              and cv.created_at < now() - make_interval(hours => ?)
            """.trimIndent(),
            graceHours,
        )
        return deleted
    }

    private fun referencedShas(c: Connection): Set<String> = c.select(
        """
        select audio_sha256 as sha from tracks
        union select sha256 from renditions
        union select sha256 from covers
        """.trimIndent(),
    ) { it.getString("sha") }.toSet()

    /**
     * Ссылку держит и удалённый в корзину трек: его строки на месте, значит файлы нужны для
     * возврата. Если не смогли выяснить — считаем, что ссылка есть: лишний файл дешевле потери.
     */
    private fun isReferenced(c: Connection, sha: String): Boolean = c.selectOne(
        """
        select exists (select 1 from renditions where sha256 = ?)
            or exists (select 1 from tracks where audio_sha256 = ?)
            or exists (select 1 from covers where sha256 = ?)
        """.trimIndent(),
        sha, sha, sha,
    ) { it.getBoolean(1) } ?: true

    // ---------- файлы ----------

    private fun deleteUnreferenced(candidates: List<StoredFile>): Swept {
        if (candidates.isEmpty()) return Swept(0, 0)
        val orphans = Db.read { c -> candidates.filterNot { isReferenced(c, it.sha) } }
        var files = 0
        var bytes = 0L
        orphans.forEach { file ->
            val swept = store.deleteBySha(file.kind, file.sha)
            files += swept.files
            bytes += swept.bytes
        }
        return Swept(files, bytes)
    }

    /**
     * Полуфабрикаты. Загруженный файл держит своё задание, пока то не разобрано или ещё может
     * быть перезапущено; всё остальное в tmp — следы упавшего ингеста.
     */
    private fun sweepTemp(olderThan: Instant): Int {
        val busy = Db.read { c ->
            c.select("select payload->>'temp' as temp from jobs where status in ($BUSY_JOB_STATUSES)") {
                it.getString("temp")
            }
        }.mapNotNull { raw -> raw?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() } }
            .toSet()

        var deleted = 0
        TEMP_KINDS.forEach { kind ->
            store.forEachFile(kind) { path ->
                if (path.toAbsolutePath().normalize() !in busy && isOlderThan(path, olderThan)) {
                    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) deleted++
                }
            }
        }
        return deleted
    }

    private fun forEachRow(c: Connection, sql: String, ids: List<UUID>, row: (ResultSet) -> Unit) {
        c.prepareStatement(sql).use { st ->
            st.setArray(1, c.createArrayOf("uuid", ids.toTypedArray()))
            st.executeQuery().use { rs -> while (rs.next()) row(rs) }
        }
    }
}

/**
 * Метёт разделы каталога: файл живёт, пока его sha есть в базе.
 *
 * Отсрочка по времени изменения обязательна. Ингест кладёт файл в хранилище раньше, чем
 * пишет строку в базу, и в этом промежутке файл выглядит ровно как бесхозный — без отсрочки
 * уборка снесла бы его прямо из-под работающей загрузки.
 */
internal fun sweepUnreferenced(
    store: MediaStore,
    kinds: List<String>,
    referenced: Set<String>,
    olderThan: Instant,
): Swept {
    var files = 0
    var bytes = 0L
    kinds.forEach { kind ->
        store.forEachFile(kind) { path ->
            val sha = path.fileName.toString().substringBefore('.')
            if (sha !in referenced && isOlderThan(path, olderThan)) {
                val size = runCatching { Files.size(path) }.getOrDefault(0L)
                if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) {
                    files++
                    bytes += size
                }
            }
        }
    }
    return Swept(files, bytes)
}

/** Не смогли прочитать время — считаем файл свежим и не трогаем. */
private fun isOlderThan(path: Path, cutoff: Instant): Boolean =
    runCatching { Files.getLastModifiedTime(path).toInstant().isBefore(cutoff) }.getOrDefault(false)
