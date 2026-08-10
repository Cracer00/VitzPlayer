package com.vitz.music

import com.vitz.music.media.MediaStore
import com.vitz.music.media.sweepUnreferenced
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Уборка проверяется на настоящих файлах во временном каталоге: вся её суть — в том, какой
 * файл она трогает, а какой нет, и подменённое хранилище это как раз и скрыло бы.
 */
class MediaGcSweepTest {

    private val root: Path = createTempDirectory("vitz-gc-test")
    private val store = MediaStore(root)
    private val long = "a".repeat(64)
    private val old = Instant.now().minusSeconds(48 * 3600)
    private val cutoff = Instant.now().minusSeconds(24 * 3600)

    @AfterTest
    fun cleanup() {
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun put(kind: String, sha: String, ext: String, at: Instant = old): Path {
        val temp = store.temp(".bin")
        Files.write(temp, ByteArray(1024))
        val path = store.put(temp, kind, sha, ext)
        Files.setLastModifiedTime(path, FileTime.from(at))
        return path
    }

    private fun sha(prefix: String) = prefix.padEnd(64, '0')

    @Test
    fun `файл без ссылки в базе удаляется`() {
        val orphan = put("audio", sha("dead"), "mp3")
        val swept = sweepUnreferenced(store, listOf("audio"), referenced = emptySet(), olderThan = cutoff)

        assertFalse(Files.exists(orphan), "бесхозный файл должен был уйти")
        assertEquals(1, swept.files)
        assertEquals(1024L, swept.bytes)
    }

    @Test
    fun `файл со ссылкой остаётся`() {
        val kept = put("audio", sha("beef"), "flac")
        val swept = sweepUnreferenced(store, listOf("audio"), referenced = setOf(sha("beef")), olderThan = cutoff)

        assertTrue(Files.exists(kept), "на файл ссылается трек, трогать нельзя")
        assertEquals(0, swept.files)
    }

    @Test
    fun `свежий файл не трогаем даже без ссылки`() {
        // Так выглядит ингест в промежутке между записью файла и записью строки в базу.
        val inFlight = put("audio", sha("f00d"), "mp3", at = Instant.now())
        val swept = sweepUnreferenced(store, listOf("audio"), referenced = emptySet(), olderThan = cutoff)

        assertTrue(Files.exists(inFlight), "файл на ингесте сносить нельзя")
        assertEquals(0, swept.files)
    }

    @Test
    fun `обложка уходит всеми вариантами размера`() {
        val cover = sha("c0")
        val variants = listOf(64, 256, 1024).map { put("cover", cover, "$it.webp") }
        val swept = sweepUnreferenced(store, listOf("cover"), referenced = emptySet(), olderThan = cutoff)

        variants.forEach { assertFalse(Files.exists(it), "остался вариант $it") }
        assertEquals(3, swept.files)
    }

    @Test
    fun `удаление по sha сносит все расширения этого содержимого`() {
        val target = sha("11")
        val neighbour = sha("12")
        put("audio", target, "mp3")
        put("audio", target, "flac")
        val other = put("audio", neighbour, "mp3")

        val swept = store.deleteBySha("audio", target)

        assertEquals(2, swept.files)
        assertEquals(2048L, swept.bytes)
        assertTrue(Files.exists(other), "чужой файл в том же каталоге не трогаем")
    }

    @Test
    fun `удаление по несуществующему sha ничего не ломает`() {
        assertEquals(0, store.deleteBySha("audio", long).files)
        assertEquals(0, store.deleteBySha("audio", "abc").files, "слишком короткий sha — не путь к файлу")
    }
}
