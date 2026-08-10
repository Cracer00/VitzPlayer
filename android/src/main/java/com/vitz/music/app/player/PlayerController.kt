package com.vitz.music.app.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vitz.music.api.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Прослойка между экранами и службой воспроизведения.
 *
 * Держит подключение к сессии и отражает её состояние в состояние Compose. Позиция
 * обновляется раз в секунду опросом, а не событиями: событий о движении позиции плеер
 * не шлёт, а перерисовывать чаще незачем — цифры секунд всё равно меняются раз в секунду.
 */
@UnstableApi
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Путь к скачанному файлу, если он есть. Без сети играет только он. */
    private val localPath: (String) -> String? = { null },
) {

    private var controller: MediaController? = null

    var currentTrackId by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var artist by mutableStateOf<String?>(null)
        private set
    var artworkUri by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var liked by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var hasNext by mutableStateOf(false)
        private set
    var hasPrevious by mutableStateOf(false)
        private set

    val isConnected: Boolean get() = controller != null

    /** Запрос, пришедший до того, как подключение к сессии состоялось. */
    private var pending: Pair<TrackDto, List<TrackDto>>? = null

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also { ready ->
                attach(ready)
                // Подключение асинхронное, а по треку могли тапнуть сразу после запуска
                // приложения. Без этого первое нажатие молча пропадало бы.
                pending?.let { (track, queue) ->
                    pending = null
                    play(track, queue)
                }
            }
        }, MoreExecutors.directExecutor())

        scope.launch {
            while (isActive) {
                controller?.let { positionMs = it.currentPosition.coerceAtLeast(0L) }
                delay(1000)
            }
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }

    private fun attach(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = readState(player)
        })
        readState(controller)
    }

    private fun readState(player: Player) {
        val item = player.currentMediaItem

        // Сердечко перечитываем только при смене трека. Внутри одного трека верна отметка,
        // поставленная руками: в метаданных очереди она осталась той, что была при запуске,
        // и перечитывание на каждое событие гасило бы её обратно.
        if (item?.mediaId != currentTrackId) {
            liked = (item?.mediaMetadata?.userRating as? HeartRating)?.isHeart == true
        }

        currentTrackId = item?.mediaId
        title = item?.mediaMetadata?.title?.toString()
        artist = item?.mediaMetadata?.artist?.toString()
        artworkUri = item?.mediaMetadata?.artworkUri?.toString()
        isPlaying = player.isPlaying
        durationMs = player.duration.takeIf { it > 0 } ?: 0L
        positionMs = player.currentPosition.coerceAtLeast(0L)
        hasNext = player.hasNextMediaItem()
        hasPrevious = player.hasPreviousMediaItem()
    }

    // --- команды ---

    fun play(track: TrackDto, queue: List<TrackDto>) {
        val controller = controller
        if (controller == null) {
            pending = track to queue
            connect()
            return
        }

        // Индекс считаем по тому же списку, из которого собраны элементы: если у части треков
        // не оказалось ссылок, индексы исходной очереди уже не совпадают с итоговыми.
        val playable = queue.filter { it.media.isNotEmpty() || localPath(it.id) != null }
        val items = playable.mapNotNull { it.toMediaItem(localPath(it.id)) }
        if (items.isEmpty()) return
        val startIndex = playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        controller.setMediaItems(items, startIndex, 0L)
        // playWhenReady до prepare(): вызванный сразу после подготовки play() успевает
        // отработать раньше, чем сессия готова его принять, и воспроизведение не стартует.
        controller.playWhenReady = true
        controller.prepare()
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    /**
     * Отметка «нравится» уходит штатным `setRating` — тем же путём, которым её ставит
     * приборка. Сохраняет её служба: у неё есть авторизованный клиент, а экрану знать
     * ни адреса сервера, ни токенов незачем.
     *
     * Сердечко перекрашивается сразу, не дожидаясь ответа: ждать сети ради галочки нечего,
     * а расхождение выправит ближайшая синхронизация.
     */
    fun toggleLike() {
        val controller = controller ?: return
        val desired = !liked
        liked = desired
        controller.setRating(HeartRating(desired))
    }

    fun next() = controller?.seekToNextMediaItem()

    fun previous() = controller?.seekToPreviousMediaItem()

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceIn(0L, durationMs.takeIf { it > 0 } ?: ms))
        positionMs = ms
    }
}

/**
 * Играем рендицию `opus-96`: в машине звук всё равно уходит по Bluetooth и там
 * перекодируется, а трафика она ест втрое меньше оригинала. Оригинал — запасной вариант,
 * если рендиция почему-то не сделалась.
 */
@UnstableApi
fun TrackDto.toMediaItem(localPath: String? = null): MediaItem? {
    // Скачанный файл в приоритете: он играет без сети и не зависит от срока жизни подписи.
    val uri = localPath?.let { android.net.Uri.fromFile(java.io.File(it)).toString() }
        ?: (media.firstOrNull { it.rendition == "opus-96" } ?: media.firstOrNull())?.url
        ?: return null
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverUrl?.let { android.net.Uri.parse(it) })
                // Отметка «нравится» едет в метаданных: по ней приборка рисует сердечко,
                // не обращаясь к серверу самостоятельно.
                .setUserRating(androidx.media3.common.HeartRating(liked))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}
