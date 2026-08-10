package com.vitz.music.app.data

import android.content.Context
import android.util.Log
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.local.DownloadRow
import com.vitz.music.app.data.local.MirrorDb
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Скачивание треков на устройство.
 *
 * Кеш стрима и скачанные файлы — разные сущности, и путать их нельзя: кеш появляется сам
 * и вытесняется при переполнении, скачанное появляется по явному действию и не исчезает,
 * пока его не удалят. Отсюда и отдельное хранилище, и отдельные кнопки очистки.
 */
class Downloads(
    private val context: Context,
    private val api: ApiClient,
    private val mirror: MirrorDb,
) {

    private val directory: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    fun isDownloaded(trackId: String): Boolean = mirror.downloadPath(trackId)?.let { File(it).isFile } == true

    fun localPath(trackId: String): String? = mirror.downloadPath(trackId)?.takeIf { File(it).isFile }

    // Списки перечитываются на каждый шаг автозагрузки, поэтому уходят с главного потока:
    // запрос растёт вместе с каталогом, а на планшете это кадры прокрутки.
    suspend fun downloadedIds(): Set<String> = withContext(Dispatchers.IO) { mirror.downloadedIds() }

    suspend fun list(): List<DownloadRow> = withContext(Dispatchers.IO) { mirror.downloads() }

    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { mirror.downloads().sumOf { it.sizeBytes } }

    /**
     * По умолчанию качаем `opus-96`: в машине звук всё равно уходит по Bluetooth и там
     * перекодируется, а места такая рендиция занимает втрое меньше оригинала.
     */
    suspend fun download(track: TrackDto, rendition: String = "opus-96"): Boolean = withContext(Dispatchers.IO) {
        val link = track.media.firstOrNull { it.rendition == rendition }
            ?: track.media.firstOrNull()
            ?: return@withContext false

        val target = File(directory, "${track.id}.${link.format}")
        // Скачиваем во временный файл: оборванная загрузка не должна выглядеть как готовая.
        val temp = File(directory, "${track.id}.part")

        val ok = runCatching {
            val response = api.getRaw(link.url)
            temp.outputStream().use { output ->
                response.bodyAsChannel().toInputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        // Копирование одним вызовом не прерывается: остановка автозагрузки
                        // ждала бы конца файла, а на оригинале это десятки секунд.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (temp.length() <= 0) error("пустой файл")
            temp.renameTo(target)
        }.getOrElse { error ->
            temp.delete()
            if (error is CancellationException) throw error
            Log.w(TAG, "Не скачался ${track.id}: ${error.message}")
            false
        }

        if (!ok) return@withContext false

        mirror.putDownload(
            DownloadRow(
                trackId = track.id,
                rendition = link.rendition,
                path = target.absolutePath,
                sizeBytes = target.length(),
                state = "done",
            ),
        )
        true
    }

    suspend fun remove(trackId: String) = withContext(Dispatchers.IO) {
        mirror.downloadPath(trackId)?.let { File(it).delete() }
        mirror.removeDownload(trackId)
    }

    /** Удаление всего скачанного. Кеш стрима это не трогает — у него своя кнопка. */
    suspend fun removeAll() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
        mirror.clearDownloads()
    }

    /**
     * Файлы треков, которых больше нет в каталоге. Строку загрузки зеркало убирает само,
     * когда разбирает удаления с синхронизации, а снять файл с диска может только эта сторона.
     */
    suspend fun forget(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path ->
            if (File(path).delete()) Log.i(TAG, "Убрал загрузку удалённого трека: $path")
        }
    }

    /**
     * Сверка списка с диском в обе стороны.
     *
     * Файл может пропасть мимо приложения — например, при очистке данных системой, — и тогда
     * список обещал бы музыку, которой нет. Бывает и обратное: оборванная загрузка оставляет
     * `.part`, а трек, удалённый на сервере, пока телефон стоял без сети, — целый файл, на
     * который уже никто не сошлётся.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val known = HashSet<String>()
        mirror.downloads().forEach { row ->
            if (File(row.path).isFile) known += row.path else mirror.removeDownload(row.trackId)
        }
        // Свежие файлы не трогаем: рядом может идти загрузка, у которой строки ещё нет.
        val staleBefore = System.currentTimeMillis() - ORPHAN_GRACE_MS
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in known && file.lastModified() < staleBefore) {
                if (file.delete()) Log.i(TAG, "Убрал файл без записи: ${file.name}")
            }
        }
    }

    private companion object {
        const val TAG = "Downloads"
        const val BUFFER_SIZE = 64 * 1024

        /** Сколько файл без строки считается идущей загрузкой, а не мусором. */
        const val ORPHAN_GRACE_MS = 10 * 60 * 1000L
    }
}
