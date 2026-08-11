package com.vitz.music.app.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vitz.music.api.MediaLink
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.local.DownloadRow
import com.vitz.music.app.data.local.MirrorDb
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Чем кончилась загрузка. Причина отказа нужна словами: её показывают человеку. */
sealed interface DownloadResult {
    data object Done : DownloadResult

    /**
     * @param staleLink сервер отверг саму ссылку — есть смысл продлить подпись и повторить.
     * @param offline причина в связи, а не в треке: перебирать очередь дальше бессмысленно.
     */
    data class Failed(
        val reason: String,
        val staleLink: Boolean = false,
        val offline: Boolean = false,
    ) : DownloadResult
}

/**
 * Скачивание треков на устройство.
 *
 * Кеш стрима и скачанные файлы — разные сущности, и путать их нельзя: кеш появляется сам
 * и вытесняется при переполнении, скачанное появляется по явному действию и не исчезает,
 * пока его не удалят. Отсюда и отдельное хранилище, и отдельные кнопки очистки.
 *
 * Главное правило здесь — загрузка либо кладёт на диск музыку, либо честно говорит, почему
 * не положила. Ответ сервера, оказавшийся не файлом, файлом не считается: без проверки
 * статуса на диск ложился json ошибки в двадцать один байт, строка загрузок появлялась,
 * кнопка показывала галочку, а играть было нечего.
 */
class Downloads(
    private val context: Context,
    private val api: ApiClient,
    private val mirror: MirrorDb,
    private val links: MediaLinks,
    private val connectivity: Connectivity,
) {

    /** Почему не скачалось в последний раз. Показывается на экране загрузок. */
    var lastError by mutableStateOf<String?>(null)
        private set

    private val directory: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    fun isDownloaded(trackId: String): Boolean = mirror.downloadPath(trackId)?.let { File(it).isFile } == true

    fun localPath(trackId: String): String? = mirror.downloadPath(trackId)?.takeIf { File(it).isFile }

    // Списки перечитываются на каждый шаг автозагрузки, поэтому уходят с главного потока:
    // запрос растёт вместе с каталогом, а на планшете это кадры прокрутки.
    suspend fun downloadedIds(): Set<String> = withContext(Dispatchers.IO) { mirror.downloadedIds() }

    suspend fun list(): List<DownloadRow> = withContext(Dispatchers.IO) { mirror.downloads() }

    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { mirror.downloads().sumOf { it.sizeBytes } }

    fun clearLastError() {
        lastError = null
    }

    /**
     * По умолчанию качаем `opus-96`: в машине звук всё равно уходит по Bluetooth и там
     * перекодируется, а места такая рендиция занимает втрое меньше оригинала.
     */
    suspend fun download(track: TrackDto, rendition: String = "opus-96"): DownloadResult =
        withContext(Dispatchers.IO) {
            val result = attempt(track, rendition)
            lastError = (result as? DownloadResult.Failed)
                ?.let { "«${track.title}» не скачался: ${it.reason}" }
            result
        }

    private suspend fun attempt(track: TrackDto, rendition: String): DownloadResult {
        // Проверяем сеть до запроса: без неё ждать минуту таймаута, чтобы сказать
        // «не получилось», — худший из способов сообщить, что нет интернета.
        if (!connectivity.online()) return DownloadResult.Failed(NO_NETWORK, offline = true)

        val source = runCatching { links.fresh(track) }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(TAG, "Не продлил ссылки ${track.id}: ${error.message}")
            // Ссылка из зеркала может быть ещё жива — тогда пробуем ею. Если протухла,
            // качать нечем: подписать адрес самостоятельно устройство не может.
            if (track.media.any { it.isFresh() }) track
            else return DownloadResult.Failed(readable(error), offline = error.isConnection())
        }

        val link = source.media.firstOrNull { it.rendition == rendition }
            ?: source.media.firstOrNull()
            ?: return DownloadResult.Failed("на сервере нет файла для этого трека")

        val first = fetch(track, link)
        if (first !is DownloadResult.Failed || !first.staleLink) return first

        // Подпись отвергнута: часы устройства и сервера могли разойтись, и по сроку ссылка
        // выглядела живой. Продлеваем принудительно и пробуем ещё раз — один.
        val renewed = runCatching { links.fresh(track, force = true) }.getOrElse { error ->
            if (error is CancellationException) throw error
            return first
        }
        val fresh = renewed.media.firstOrNull { it.rendition == link.rendition } ?: return first
        return fetch(track, fresh)
    }

    private suspend fun fetch(track: TrackDto, link: MediaLink): DownloadResult {
        val target = File(directory, "${track.id}.${link.format}")
        // Скачиваем во временный файл: оборванная загрузка не должна выглядеть как готовая.
        val temp = File(directory, "${track.id}.part")

        try {
            val response = api.getRaw(link.url)
            if (!response.status.isSuccess()) {
                return DownloadResult.Failed(
                    reason = describe(response.status),
                    staleLink = response.status == HttpStatusCode.Forbidden,
                )
            }
            // Json вместо музыки — это отказ, который сервер не успел назвать отказом
            // (или прокси подменила ответ). На диск такое не кладём.
            val type = response.contentType()
            if (type != null && type.match(ContentType.Application.Json)) {
                return DownloadResult.Failed("сервер вернул ошибку вместо файла", staleLink = true)
            }

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

            // Размер файла сервер называет заранее — сверяем с ним, а если не назвал,
            // отбиваемся хотя бы порогом. Иначе оборванная на середине загрузка стала бы
            // «скачанным треком», который обрывается на середине, а отказ сервера —
            // треком в двадцать один байт.
            val written = temp.length()
            val enough = if (link.sizeBytes > 0) written >= link.sizeBytes else written >= MIN_VALID_BYTES
            if (!enough) {
                val expected = if (link.sizeBytes > 0) " из ${link.sizeBytes / 1024} КБ" else ""
                return DownloadResult.Failed("загрузка оборвалась — ${written / 1024} КБ$expected")
            }

            target.delete()
            if (!temp.renameTo(target)) return DownloadResult.Failed("не удалось сохранить файл на устройство")

            mirror.putDownload(
                DownloadRow(
                    trackId = track.id,
                    rendition = link.rendition,
                    path = target.absolutePath,
                    sizeBytes = target.length(),
                    state = "done",
                ),
            )
            return DownloadResult.Done
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Не скачался ${track.id}: ${error.message}")
            return DownloadResult.Failed(readable(error), offline = error.isConnection())
        } finally {
            temp.delete()
        }
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
     *
     * Заодно уходят огрызки: файлы, которые музыкой быть не могут по размеру. Такие остались
     * от версий, где ответ сервера не проверялся и отказ ложился на диск как трек, — снаружи
     * это выглядело как скачанная библиотека в триста байт.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val known = HashSet<String>()
        mirror.downloads().forEach { row ->
            val file = File(row.path)
            when {
                !file.isFile -> mirror.removeDownload(row.trackId)
                file.length() < MIN_VALID_BYTES -> {
                    Log.i(TAG, "Выбросил огрызок загрузки ${row.trackId}: ${file.length()} Б")
                    file.delete()
                    mirror.removeDownload(row.trackId)
                }

                else -> known += row.path
            }
        }
        // Свежие файлы не трогаем: рядом может идти загрузка, у которой строки ещё нет.
        val staleBefore = System.currentTimeMillis() - ORPHAN_GRACE_MS
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in known && file.lastModified() < staleBefore) {
                if (file.delete()) Log.i(TAG, "Убрал файл без записи: ${file.name}")
            }
        }
    }

    private fun describe(status: HttpStatusCode): String = when (status) {
        HttpStatusCode.Forbidden -> "ссылка на файл просрочена"
        HttpStatusCode.Unauthorized -> "сервер не принял вход — нужно войти заново"
        HttpStatusCode.NotFound -> "файла нет на сервере"
        else -> "сервер ответил ${status.value}"
    }

    private fun readable(error: Throwable): String = when {
        error is ApiException && error.status == 401 -> "сервер не принял вход — нужно войти заново"
        error is ApiException && error.status == 404 -> "трека больше нет на сервере"
        error is ApiException -> error.message ?: "сервер ответил ошибкой ${error.status}"
        error is UnknownHostException -> "сервер не отвечает — похоже, нет интернета"
        error is SocketTimeoutException || error is HttpRequestTimeoutException -> "сервер не ответил вовремя"
        error is IOException -> "связь оборвалась"
        else -> error.message ?: "неизвестная ошибка"
    }

    /** Отказ по связи, а не по треку: следующий трек упрётся в то же самое. */
    private fun Throwable.isConnection(): Boolean =
        this is IOException || this is HttpRequestTimeoutException

    companion object {
        const val NO_NETWORK = "нет интернета"

        private const val TAG = "Downloads"
        private const val BUFFER_SIZE = 64 * 1024

        /** Меньше этого музыкой не бывает: столько занимает отказ сервера, а не трек. */
        private const val MIN_VALID_BYTES = 16 * 1024L

        /** Сколько файл без строки считается идущей загрузкой, а не мусором. */
        private const val ORPHAN_GRACE_MS = 10 * 60 * 1000L
    }
}
