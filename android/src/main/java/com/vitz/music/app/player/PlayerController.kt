package com.vitz.music.app.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
class PlayerController(private val context: Context, private val scope: CoroutineScope) {

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
    var durationMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var hasNext by mutableStateOf(false)
        private set
    var hasPrevious by mutableStateOf(false)
        private set

    val isConnected: Boolean get() = controller != null

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also { attach(it) }
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
        val controller = controller ?: return
        val items = queue.mapNotNull { it.toMediaItem() }
        val startIndex = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        if (items.isEmpty()) return
        controller.setMediaItems(items, startIndex, 0L)
        controller.prepare()
        controller.play()
    }

    fun togglePlayPause() {
        val controller = controller ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
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
fun TrackDto.toMediaItem(): MediaItem? {
    val link = media.firstOrNull { it.rendition == "opus-96" } ?: media.firstOrNull() ?: return null
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(link.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverUrl?.let { android.net.Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}
