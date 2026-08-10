package com.vitz.music.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vitz.music.app.data.local.MirrorDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Автоматическое скачивание каталога на устройство.
 *
 * Запускается после каждой синхронизации: зеркало уже знает, что появилось нового, и остаётся
 * докачать недостающее. Идёт по одному треку — параллельная выкачка на мобильном интернете
 * только мешает играть тому, что уже качается.
 *
 * Два предохранителя обязательны. Первый — тип сети: качать гигабайты по мобильному, не
 * спросив, нельзя. Второй — свободное место: заполнить планшет под ноль означает сломать
 * не только плеер, но и всё остальное на устройстве.
 */
class AutoDownloader(
    private val context: Context,
    private val mirror: MirrorDb,
    private val downloads: Downloads,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {

    var running by mutableStateOf(false)
        private set
    var remaining by mutableIntStateOf(0)
        private set
    var lastSkipReason by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /**
     * Трек только что отметили сердечком.
     *
     * При области «только понравившееся» ждать следующей синхронизации незачем: человек
     * поставил отметку именно для того, чтобы трек оказался с ним в дороге. Отдельного
     * пути скачивания здесь нет намеренно — обычный заход подберёт и его, и всё, что
     * не докачалось раньше, вместе с предохранителями по сети и месту.
     */
    fun onTrackLiked() {
        if (settings.autoDownload != AutoDownloadScope.LIKED) return
        start()
    }

    /**
     * Принудительная остановка.
     *
     * Останавливает и текущий файл: копирование проверяет отмену на каждом куске, иначе
     * «стоп» на оригинале в сотню мегабайт ждал бы конца закачки и выглядел бы сломанным.
     * Недокачанный кусок удаляется — готовым файлом он не станет.
     *
     * Возобновится всё это только со следующей синхронизацией. Чтобы не возобновлялось
     * вовсе, область автозагрузки нужно перевести в «Выключено».
     */
    fun cancel() {
        job?.cancel()
        job = null
        running = false
        remaining = 0
        lastSkipReason = "Остановлено вручную"
    }

    private suspend fun run() {
        if (settings.autoDownload == AutoDownloadScope.OFF) return

        if (settings.wifiOnly && !onWifi()) {
            lastSkipReason = "Ждёт Wi-Fi"
            return
        }
        if (freeBytes() < MIN_FREE_BYTES) {
            lastSkipReason = "Мало места на устройстве"
            return
        }

        val already = downloads.downloadedIds()
        val wanted = when (settings.autoDownload) {
            AutoDownloadScope.LIKED -> mirror.tracks(0, MAX_BATCH, null, likedOnly = true, downloadedOnly = false)
            else -> mirror.tracks(0, MAX_BATCH, null, likedOnly = false, downloadedOnly = false)
        }.filter { it.id !in already }

        if (wanted.isEmpty()) {
            lastSkipReason = null
            return
        }

        running = true
        remaining = wanted.size
        lastSkipReason = null

        for (track in wanted) {
            // Именно своя отмена, а не жизнь общей области: appScope живёт сколько процесс,
            // и по нему остановка не читалась бы вовсе.
            if (!currentCoroutineContext().isActive) break
            // Условия могли измениться на середине: вышли из зоны Wi-Fi, кончилось место.
            if (settings.wifiOnly && !onWifi()) {
                lastSkipReason = "Ждёт Wi-Fi"
                break
            }
            if (freeBytes() < MIN_FREE_BYTES) {
                lastSkipReason = "Мало места на устройстве"
                break
            }

            runCatching { downloads.download(track, settings.rendition) }
                .onFailure { error ->
                    // Отмену пропускаем наружу: проглоченная, она превратила бы остановку
                    // в холостой пробег по всей оставшейся очереди.
                    if (error is CancellationException) throw error
                    Log.i(TAG, "Автозагрузка ${track.id} не удалась: ${error.message}")
                }
            remaining--
        }

        running = false
        remaining = 0
    }

    private fun onWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun freeBytes(): Long = runCatching { File(context.filesDir.toURI()).usableSpace }.getOrDefault(0L)

    private companion object {
        const val TAG = "AutoDownloader"

        /** Ниже этого порога не качаем: устройству нужно место и на всё остальное. */
        const val MIN_FREE_BYTES = 1_500L * 1024 * 1024

        /** За один заход берём не больше — остальное догонит следующая синхронизация. */
        const val MAX_BATCH = 500
    }
}
