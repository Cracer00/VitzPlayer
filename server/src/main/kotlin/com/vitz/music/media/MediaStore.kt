package com.vitz.music.media

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Файлы на диске, путь адресуется хешем содержимого:
 * `<root>/<kind>/ab/cd/abcd….<ext>`. Даёт дедупликацию побайтовых копий и неизменяемость —
 * такой файл можно отдавать с `Cache-Control: immutable`.
 */
class MediaStore(val root: Path) {

    init {
        listOf("audio", "rendition", "cover", "incoming", "tmp").forEach {
            Files.createDirectories(root.resolve(it))
        }
    }

    fun path(kind: String, sha: String, ext: String): Path =
        root.resolve(kind).resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve("$sha.$ext")

    fun exists(kind: String, sha: String, ext: String): Boolean = Files.isRegularFile(path(kind, sha, ext))

    /** Перемещает временный файл в хранилище. Если такой файл уже есть — временный удаляется. */
    fun put(temp: Path, kind: String, sha: String, ext: String): Path {
        val target = path(kind, sha, ext)
        if (Files.exists(target)) {
            Files.deleteIfExists(temp)
            return target
        }
        Files.createDirectories(target.parent)
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            // Разные файловые системы (том /media против /tmp) — атомарно не выйдет.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    fun delete(kind: String, sha: String, ext: String): Boolean = Files.deleteIfExists(path(kind, sha, ext))

    /** Файл для приёма загрузки: живёт до тех пор, пока задание ингеста его не разберёт. */
    fun incoming(ext: String): Path {
        val dir = root.resolve("incoming")
        Files.createDirectories(dir)
        return dir.resolve("${UUID.randomUUID()}.${ext.ifBlank { "bin" }}")
    }

    fun temp(suffix: String): Path {
        val dir = root.resolve("tmp")
        Files.createDirectories(dir)
        return dir.resolve("${UUID.randomUUID()}$suffix")
    }

    fun sizeOf(path: Path): Long = Files.size(path)

    companion object {
        fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input -> digest.consume(input) }
            return digest.digest().toHex()
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun MessageDigest.consume(input: InputStream) {
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                update(buffer, 0, read)
            }
        }

        private fun ByteArray.toHex(): String {
            val out = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                out.append("0123456789abcdef"[v ushr 4])
                out.append("0123456789abcdef"[v and 0x0F])
            }
            return out.toString()
        }
    }
}
