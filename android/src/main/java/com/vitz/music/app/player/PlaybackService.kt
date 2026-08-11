package com.vitz.music.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.vitz.music.app.VitzMusicApp
import com.vitz.music.app.data.AppSettings
import com.vitz.music.app.data.Downloads
import com.vitz.music.app.data.PlaybackStateStore
import com.vitz.music.app.data.SavedPlayback
import com.vitz.music.app.data.local.MirrorDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Служба воспроизведения.
 *
 * Взят `MediaSessionService`, а не `MediaLibraryService`: сессии достаточно, чтобы музыкой
 * управляли уведомление, кнопки на руле и приборка. Обзор каталога снаружи (`MediaLibraryService`)
 * понадобится только для Android Auto — это отдельный этап, и переезд туда локален.
 *
 * `handleAudioFocus = true` не украшение: без него звонок, навигация или предупреждение
 * приборки будут звучать поверх музыки, а не приглушать её.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    private val app: VitzMusicApp get() = application as VitzMusicApp
    private val playbackState: PlaybackStateStore get() = app.playbackState
    private val settings: AppSettings get() = app.settings
    private val mirror: MirrorDb get() = app.mirror
    private val downloads: Downloads get() = app.downloads

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Играла ли музыка в момент, когда магнитола погасила экран. */
    private var sleptWhilePlaying = false
    private var screen: BroadcastReceiver? = null

    private val catalog by lazy { (application as VitzMusicApp).catalog }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Пауза при отключении наушников или Bluetooth: иначе музыка продолжит играть
            // из динамика планшета, когда вышел из машины.
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player)
            .setCallback(RestrictedCallback())
            .build()

        player.addListener(PersistingListener(player))
        startPositionTicker(player)
        watchScreen(player)
        restoreLastPlayback(player)
    }

    /**
     * Сон и пробуждение магнитолы.
     *
     * Выключенное зажигание не всегда убивает процесс: часто голова просто гасит экран,
     * и тогда «запуска» при следующей поездке не происходит вовсе — плеер уже запущен и
     * стоит на паузе. Поэтому запоминаем, играла ли музыка, когда экран погас, и на
     * пробуждении возвращаем её.
     *
     * Именно «играла, когда погас», а не просто пауза: паузу, поставленную руками при
     * живом экране, гасание экрана оживлять не должно.
     *
     * Приёмник только динамический — `ACTION_SCREEN_ON` через манифест не приходит вовсе.
     */
    private fun watchScreen(player: ExoPlayer) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver(player), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun screenReceiver(player: ExoPlayer) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // playWhenReady, а не isPlaying: голова успевает отобрать аудиофокус
                // раньше, чем гасит экран, и «играет» к этому моменту уже false.
                Intent.ACTION_SCREEN_OFF -> sleptWhilePlaying = player.playWhenReady || player.isPlaying

                Intent.ACTION_SCREEN_ON -> {
                    if (!sleptWhilePlaying || player.isPlaying) return
                    sleptWhilePlaying = false
                    if (!settings.resumeOnStart || !settings.autoplayOnStart) return
                    if (player.mediaItemCount == 0) return
                    Log.i(TAG, "Магнитола проснулась — продолжаю воспроизведение")
                    applyStartVolume()
                    player.play()
                }
            }
        }
    }.also { screen = it }

    /**
     * Откуда плеер берёт байты.
     *
     * Поверх обычного источника — [ResolvingDataSource]: ссылки на медиа подписаны и живут
     * часы, а зеркало помнит те, что приехали с последней синхронизацией. Без продления
     * перед самым открытием потока каталог, пролежавший ночь, отвечал бы `403` на каждый
     * трек, кроме скачанных, — и плеер молча спотыкался бы на первой же песне.
     */
    private fun dataSourceFactory(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        // DefaultDataSource, а не только http: скачанное играет с диска, по file://.
        val base = DefaultDataSource.Factory(this, http)
        return ResolvingDataSource.Factory(base) { spec ->
            app.mediaLinks.freshUrlBlocking(spec.uri.toString())
                ?.let { fresh -> spec.withUri(fresh.toUri()) }
                ?: spec
        }
    }

    /**
     * Периодическое сохранение позиции.
     *
     * Сохранять «на выходе» бессмысленно: зажигание выключается без предупреждения, процесс
     * убивают, и никакого прощального колбэка не будет. Раз в пять секунд — компромисс между
     * износом хранилища и тем, сколько секунд трека потеряется.
     */
    private fun startPositionTicker(player: ExoPlayer) {
        serviceScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                withContext(Dispatchers.Main) {
                    if (player.isPlaying) persist(player, playing = true)
                }
            }
        }
    }

    private fun persist(player: Player, playing: Boolean) {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return
        playbackState.save(
            SavedPlayback(
                trackIds = ids,
                index = player.currentMediaItemIndex,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                wasPlaying = playing,
                savedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Восстановление очереди и позиции.
     *
     * При включённом автостарте музыка продолжается сама, даже если в прошлый раз её
     * поставили на паузу: магнитола просыпается вместе с зажиганием, и ждать от водителя
     * ещё одного нажатия незачем — он уже повернул ключ. Кому такое не нужно, выключает
     * автостарт на вкладке «Профиль», и тогда сама заиграет только музыка, которую
     * оборвали на ходу.
     */
    private fun restoreLastPlayback(player: ExoPlayer) {
        val saved = playbackState.load() ?: return
        serviceScope.launch {
            val tracks = mirror.tracksByIds(saved.trackIds)
            if (tracks.isEmpty()) return@launch
            val items = tracks.mapNotNull { it.toMediaItem(downloads.localPath(it.id)) }
            if (items.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                // Пока восстанавливались, пользователь мог уже включить что-то сам.
                if (player.mediaItemCount > 0) return@withContext
                player.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
                player.prepare()

                if (!settings.resumeOnStart) return@withContext
                if (!saved.wasPlaying && !settings.autoplayOnStart) return@withContext
                applyStartVolume()
                player.play()
            }
        }
    }

    /**
     * Громкость мультимедиа для автостарта.
     *
     * Магнитола просыпается с той громкостью, что осталась от радио или прошлой поездки,
     * поэтому музыка, включившаяся сама, звучит то шёпотом, то на весь двор. Ставим свою
     * ступень — и только в момент автостарта: крутить громкость под руками у водителя,
     * пока он уже слушает, нельзя.
     *
     * Система может и отказать — например, при включённом «не беспокоить». Тогда играем
     * на той громкости, что есть: молчать из-за этого было бы хуже.
     */
    private fun applyStartVolume() {
        val wanted = settings.startVolume
        if (wanted <= 0) return
        val audio = getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, wanted.coerceIn(1, max), 0) }
            .onFailure { Log.w(TAG, "Не выставил громкость автостарта: ${it.message}") }
    }

    private inner class PersistingListener(private val player: ExoPlayer) : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = persist(player, isPlaying)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            persist(player, player.isPlaying)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Пользователь смахнул приложение из недавних. Музыка на паузе — останавливаем службу,
     * иначе в шторке навсегда повиснет уведомление, из которого нечего играть.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        screen?.let { runCatching { unregisterReceiver(it) } }
        screen = null
        session?.run {
            player.release()
            release()
        }
        session = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * К сессии пускаем свои приложения и системных посредников. Открытая сессия означает, что любое
     * приложение на планшете сможет и управлять воспроизведением, и читать, что играет.
     */
    private inner class RestrictedCallback : MediaSession.Callback {

        /**
         * Кого пускать.
         *
         * Одного списка пакетов мало: физические кнопки магнитолы, руль и AVRCP приходят
         * не от приложения, а через системного посредника, и его имя пакета зависит от
         * прошивки. Именно поэтому «стоп» и «пауза» не делали ничего — подключение
         * отклонялось раньше, чем команда доходила до плеера.
         *
         * `isTrusted` — это система и обладатели MEDIA_CONTENT_CONTROL, то есть ровно те,
         * кому платформа и так доверяет управление чужой музыкой. Постороннее приложение
         * им не станет, так что сессия остаётся закрытой.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val allowed = controller.isTrusted || controller.packageName in ALLOWED_PACKAGES
            if (!allowed) {
                Log.w(TAG, "Отказано в подключении к сессии: ${controller.packageName}")
                return MediaSession.ConnectionResult.reject()
            }
            Log.i(TAG, "Подключён контроллер: ${controller.packageName} (доверенный: ${controller.isTrusted})")
            return super.onConnect(session, controller)
        }

        /**
         * Физические кнопки.
         *
         * Перехватываем только «стоп». По умолчанию он останавливает плеер целиком, и
         * следующее нажатие «играть» ничего не делает — очередь уже разобрана. В машине
         * «стоп» означает «замолчи», а не «забудь, что играло», поэтому переводим в паузу.
         * Остальные кнопки — играть, пауза, следующий, предыдущий — обрабатывает Media3.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            Log.i(TAG, "Кнопка: ${event?.keyCode} от ${controllerInfo.packageName}")

            if (event?.keyCode != KeyEvent.KEYCODE_MEDIA_STOP) {
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }
            if (event.action == KeyEvent.ACTION_DOWN) session.player.pause()
            return true
        }

        /**
         * Кнопка нажата, когда приложение не запущено.
         *
         * Система поднимает службу и спрашивает, что играть. Без ответа она осталась бы
         * висеть без уведомления, и Android убил бы её с ошибкой. Отвечаем последней
         * сохранённой очередью — той же, что восстанавливается при обычном запуске.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val saved = playbackState.load()
                val items = saved?.let { state ->
                    mirror.tracksByIds(state.trackIds).mapNotNull { it.toMediaItem(downloads.localPath(it.id)) }
                }.orEmpty()

                if (items.isEmpty()) {
                    future.setException(UnsupportedOperationException("Нечего возобновлять"))
                    return@launch
                }
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        saved!!.index.coerceIn(0, items.lastIndex),
                        saved.positionMs,
                    ),
                )
            }
            return future
        }

        /**
         * Отметка «нравится» с приборки. Приборка не знает ни адреса сервера, ни токенов —
         * и знать не должна: сохраняет тот, у кого уже есть авторизованный клиент.
         */
        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating,
        ): ListenableFuture<SessionResult> {
            val heart = rating as? HeartRating ?: return super.onSetRating(session, controller, rating)
            val trackId = session.player.currentMediaItem?.mediaId
                ?: return super.onSetRating(session, controller, rating)

            serviceScope.launch {
                runCatching { catalog.setLiked(trackId, heart.isHeart) }
                    .onFailure { Log.w(TAG, "Отметка не сохранилась", it) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

        /** Компромисс между износом хранилища и тем, сколько секунд теряется при обрыве питания. */
        const val SAVE_INTERVAL_MS = 5_000L

        val ALLOWED_PACKAGES = setOf(
            "com.vitz.music.app",
            "com.vitz.music.app.debug",
            "com.vitz.dashboard",
            "com.vitz.dashboard.debug",
            // Системный медиа-контроллер: без него не работают шторка и кнопки на руле.
            "com.android.systemui",
        )
    }
}
