package com.vitz.music.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import com.vitz.music.api.TrackDto
import com.vitz.music.app.MainActivity
import com.vitz.music.app.R
import com.vitz.music.app.VitzMusicApp
import com.vitz.music.app.data.AppSettings
import com.vitz.music.app.data.Downloads
import com.vitz.music.app.data.PlaybackStateStore
import com.vitz.music.app.data.SavedPlayback
import com.vitz.music.app.data.SavedTrack
import com.vitz.music.app.data.local.MirrorDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** Когда магнитола погасила экран. `0` — при нас не гасила. */
    private var screenOffAtMs = 0L

    /**
     * Музыку остановил человек, а не голова и не конец очереди.
     *
     * Отличать обязательно: паузу, поставленную руками, пробуждение оживлять не должно,
     * а тишину от отобранного аудиофокуса — обязано. По состоянию плеера их не различить,
     * обе выглядят как `playWhenReady = false`.
     */
    private var pausedByHand = false

    private var screen: BroadcastReceiver? = null

    /**
     * Идёт восстановление очереди. Пока идёт, сохранять нечего: свои же `setMediaItems`
     * приезжают в слушатель раньше, чем музыка успевает заиграть, и переписали бы
     * «играло» на «стояло» — ровно то состояние, которое мы восстанавливаем.
     */
    private var restoring = false

    /** За плеер взялись руками. После этого автостарт себя не навязывает. */
    private var handsOn = false

    /** Синхронизацию ради пустой очереди просили. Повторять её каждую попытку незачем. */
    private var resyncTried = false

    /** Догон автостарта: попытки дождаться, когда голова отдаст звук. */
    private var startRetry: Job? = null

    /** Удержание переднего плана после включения магнитолы, пока музыка не пошла. */
    private var bootHold: Job? = null

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

        player.addListener(PersistingListener())
        startPositionTicker(player)
        watchScreen(player)
        restoreLastPlayback(player)
    }

    /**
     * Службу подняли снаружи — приёмником включения магнитолы или после обновления.
     *
     * Такой запуск идёт через `startForegroundService`, и система ждёт, что служба
     * объявится передним планом за считаные секунды. Media3 сделает это сама, но только
     * когда музыка заиграет, а до того успевает пройти и чтение зеркала, и торг за
     * аудиофокус с головой. Поэтому передний план держим своим уведомлением.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESUME_AFTER_BOOT) holdForegroundUntilPlaying()
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Сон и пробуждение магнитолы.
     *
     * Выключенное зажигание не всегда убивает процесс: часто голова просто гасит экран,
     * и тогда «запуска» при следующей поездке не происходит вовсе — плеер уже запущен и
     * стоит на паузе. Поэтому пробуждение — такой же повод вернуть музыку, как и запуск.
     *
     * Сигналов слушаем несколько: `ACTION_SCREEN_ON` приходит не на всякой прошивке, а
     * зажигание отзывается то разблокировкой, то появлением питания. Лишний сигнал ничего
     * не стоит — обработчик всё равно проверяет, что музыка не играет.
     *
     * Приёмник только динамический: через манифест эти события не приходят вовсе.
     */
    private fun watchScreen(player: ExoPlayer) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(this, screenReceiver(player), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun screenReceiver(player: ExoPlayer) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (val action = intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAtMs = System.currentTimeMillis()

                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_POWER_CONNECTED,
                -> onWakeUp(player, action)
            }
        }
    }.also { screen = it }

    /**
     * Магнитола проснулась.
     *
     * Возвращаем музыку, если её не останавливали руками. Ручную паузу переживает только
     * долгий сон: между «поставил на паузу и отвернулся» и «поставил на паузу, выключил
     * зажигание и вернулся назавтра» разница ровно во времени, и во втором случае от плеера
     * ждут того же, что и от радио, — что он заиграет сам.
     *
     * Пустая очередь здесь означает, что при запуске восстановить не удалось: зеркало было
     * не готово или треков в нём не нашлось. Пробуем ещё раз — другого повода не будет.
     */
    private fun onWakeUp(player: ExoPlayer, from: String) {
        if (player.isPlaying || player.playWhenReady) return
        if (!settings.resumeOnStart || !settings.autoplayOnStart) return

        val slept = if (screenOffAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - screenOffAtMs
        if (pausedByHand && slept < LONG_SLEEP_MS) {
            Log.i(TAG, "Пробуждение ($from): музыку только что остановили руками — не лезу")
            return
        }
        screenOffAtMs = 0L

        if (player.mediaItemCount == 0) {
            Log.i(TAG, "Пробуждение ($from): очереди нет, собираю прошлую поездку заново")
            restoreLastPlayback(player)
            return
        }
        // Очередь доиграна до конца: «продолжить» тут означало бы начать её заново,
        // а этого никто не просил.
        if (player.playbackState == Player.STATE_ENDED) return

        Log.i(TAG, "Пробуждение ($from) — продолжаю воспроизведение")
        pausedByHand = false
        applyStartVolume()
        startPlayback(player)
    }

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
     *
     * Тикает и на паузе: очередь, набранную и не запущенную, помнить надо не меньше.
     * Повторные записи одного и того же отсекает само хранилище.
     */
    private fun startPositionTicker(player: ExoPlayer) {
        serviceScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                val state = withContext(Dispatchers.Main) { snapshot(player) }
                state?.let { playbackState.save(it) }
            }
        }
    }

    /** Что сейчас в плеере — в том виде, в каком это переживёт обрыв питания. */
    private fun snapshot(player: Player): SavedPlayback? {
        if (restoring) return null
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return null
        val item = player.currentMediaItem
        return SavedPlayback(
            trackIds = ids,
            index = player.currentMediaItemIndex,
            currentTrackId = item?.mediaId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            // Сохраняем намерение (`playWhenReady`), а не факт (`isPlaying`): между треками
            // и на буферизации музыка «не играет», хотя её никто не останавливал.
            wasPlaying = player.playWhenReady,
            savedAtEpochMs = System.currentTimeMillis(),
            current = item?.let { snapshotOf(it) },
        )
    }

    /**
     * Снимок текущего трека целиком, а не одним идентификатором.
     *
     * По идентификатору трек ищется в зеркале, а зеркало может его не отдать: база
     * пересоздалась, синхронизация не дошла, трек уехал из каталога. Снимок делает
     * возвращение к прошлому треку независимым от всего этого.
     */
    private fun snapshotOf(item: MediaItem) = SavedTrack(
        id = item.mediaId,
        title = item.mediaMetadata.title?.toString().orEmpty(),
        artist = item.mediaMetadata.artist?.toString().orEmpty(),
        album = item.mediaMetadata.albumTitle?.toString(),
        coverUrl = item.mediaMetadata.artworkUri?.toString(),
        url = item.localConfiguration?.uri?.toString(),
        liked = (item.mediaMetadata.userRating as? HeartRating)?.isHeart == true,
    )

    /** Сохранить, не задерживая главный поток: запись идёт на диск и синхронно. */
    private fun persist(player: Player) {
        val state = snapshot(player) ?: return
        serviceScope.launch { playbackState.save(state) }
    }

    /** То же, но немедленно: на прощании службы ждать своей очереди уже некому. */
    private fun persistNow(player: Player) {
        snapshot(player)?.let { runCatching { playbackState.save(it) } }
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
        if (restoring) return
        val saved = playbackState.load()
        if (saved == null) {
            Log.i(TAG, "Восстанавливать нечего: сохранённой очереди нет")
            return
        }
        restoring = true
        serviceScope.launch {
            // Флаг снимается на любом исходе, включая исключение по дороге: оставленный
            // поднятым, он выключает сохранение позиции до конца жизни процесса — поездка
            // пишется в никуда, а следующая начинается с позапрошлого места.
            try {
                val fromMirror = runCatching { mirror.tracksByIds(saved.trackIds) }
                    .onFailure { Log.w(TAG, "Очередь прошлой поездки не прочиталась", it) }
                    .getOrDefault(emptyList())
                    .associateBy { it.id }
                val items = buildQueue(saved, fromMirror)

                withContext(Dispatchers.Main) {
                    if (items.isEmpty()) {
                        // Молчание здесь ни от чего не отличимо, а причина ровно одна:
                        // зеркало не знает этих треков и снимка тоже не осталось.
                        Log.w(
                            TAG,
                            "Очередь не собралась: сохранено ${saved.trackIds.size}, " +
                                "в зеркале ${fromMirror.size}, играбельных 0",
                        )
                        resyncAndRetry(player)
                        return@withContext
                    }
                    // Пока читали зеркало, пользователь мог уже включить что-то сам.
                    if (player.mediaItemCount > 0) return@withContext

                    val at = startIndexOf(saved, items)
                    if (at < 0) Log.w(TAG, "Трек ${wantedIdOf(saved)} не собрался — начинаю очередь сначала")
                    player.setMediaItems(
                        items,
                        at.coerceAtLeast(0),
                        // Чужой трек с чужой секунды хуже, чем начало: если тот самый не
                        // нашёлся, сохранённое время ничего не значит.
                        if (at >= 0) saved.positionMs else 0L,
                    )
                    player.prepare()
                    Log.i(
                        TAG,
                        "Очередь восстановлена: ${items.size} из ${saved.trackIds.size}, " +
                            "трек ${wantedIdOf(saved)} на позиции $at",
                    )

                    if (!settings.resumeOnStart) {
                        Log.i(TAG, "Автостарт выключен настройкой")
                        return@withContext
                    }
                    if (!saved.wasPlaying && !settings.autoplayOnStart) {
                        Log.i(TAG, "В прошлый раз стояла пауза, автостарт после паузы выключен")
                        return@withContext
                    }
                    Log.i(TAG, "Продолжаю прошлую поездку с ${saved.positionMs} мс")
                    applyStartVolume()
                    startPlayback(player)
                }
            } catch (failed: Exception) {
                // Падать нельзя: это убило бы службу посреди поездки, а восстановление —
                // не то, ради чего стоит замолчать совсем.
                Log.w(TAG, "Восстановление прошлой поездки сорвалось", failed)
            } finally {
                restoring = false
            }
        }
    }

    /**
     * Очередь не собралась — просим синхронизацию и пробуем ещё раз.
     *
     * Без этого автозапуск при включении зажигания оставался молчать до конца поездки:
     * зеркало наполняет только экран библиотеки, а при автозапуске экрана нет вовсе —
     * ни синхронизации, ни второй попытки, пока водитель сам не откроет приложение.
     * Чаще всего так и бывает после самообновления: смена версии базы пересоздаёт её
     * пустой, и собирать очередь не из чего.
     *
     * Одна попытка: нет сети или не вошли — значит, и повторять нечего.
     */
    private fun resyncAndRetry(player: ExoPlayer) {
        if (resyncTried) return
        resyncTried = true
        serviceScope.launch {
            val received = runCatching { app.sync.sync() }
                .onFailure { Log.w(TAG, "Синхронизация ради очереди не прошла", it) }
                .getOrDefault(-1)
            if (received < 0) {
                Log.w(TAG, "Зеркало наполнить не удалось — возвращаться по-прежнему не к чему")
                return@launch
            }
            Log.i(TAG, "Зеркало наполнено ($received), пробую собрать очередь ещё раз")
            withContext(Dispatchers.Main) { restoreLastPlayback(player) }
        }
    }

    /**
     * Очередь из сохранённого состояния.
     *
     * Идём по сохранённому порядку, а не по тому, что вернуло зеркало: трек, которого там
     * не оказалось, подменяем снимком, а не выбрасываем молча. Ради этого снимок и пишется —
     * чтобы «вернуться к тому, что играло» не зависело от состояния базы.
     */
    private fun buildQueue(
        saved: SavedPlayback,
        fromMirror: Map<String, TrackDto>,
    ): List<MediaItem> = saved.trackIds.mapNotNull { id ->
        fromMirror[id]?.toMediaItem(downloads.localPath(id))
            ?: saved.current?.takeIf { it.id == id }?.toMediaItem(downloads.localPath(id))
    }

    private fun wantedIdOf(saved: SavedPlayback): String? =
        saved.currentTrackId ?: saved.trackIds.getOrNull(saved.index)

    /**
     * Где в собранной очереди тот самый трек. `-1`, если его в ней нет.
     *
     * Считаем по идентификатору, а не по сохранённому индексу: стоит выпасть одному треку
     * до текущего — и индекс показывает на соседнюю песню. Ровно из-за этого плеер и
     * «возвращался» не туда, куда обещал.
     */
    private fun startIndexOf(saved: SavedPlayback, items: List<MediaItem>): Int {
        val wanted = wantedIdOf(saved) ?: return -1
        return items.indexOfFirst { it.mediaId == wanted }
    }

    /**
     * Пуск с догоном.
     *
     * Одного `play()` мало: магнитола просыпается со своим источником — радио, навигацией,
     * приветствием, — и первый запрос аудиофокуса ей проигрывает. ExoPlayer в ответ сам
     * снимает `playWhenReady`, и музыка молча не начинается: ни ошибки, ни уведомления,
     * просто тишина, за которой снаружи не видно ничего.
     *
     * Поэтому повторяем попытку, пока голова не отдаст звук. Минута с лишним — не запас
     * ради запаса: столько у головы занимает собственная загрузка с приветствием, и
     * прежние двадцать секунд кончались ровно посреди неё.
     *
     * Прекращаем, как только заиграло или как только за плеер взялись руками: пауза
     * водителя важнее нашего намерения.
     */
    private fun startPlayback(player: ExoPlayer) {
        handsOn = false
        player.play()

        startRetry?.cancel()
        startRetry = serviceScope.launch {
            repeat(START_ATTEMPTS) { attempt ->
                delay(START_RETRY_MS)
                val done = withContext(Dispatchers.Main) {
                    when {
                        player.isPlaying -> true
                        handsOn -> true
                        else -> {
                            Log.i(
                                TAG,
                                "Автостарт не взлетел (попытка ${attempt + 1}): " +
                                    "playWhenReady=${player.playWhenReady}, состояние=${player.playbackState}",
                            )
                            if (player.playbackState == Player.STATE_IDLE) player.prepare()
                            player.play()
                            false
                        }
                    }
                }
                if (done) return@launch
            }
            Log.w(TAG, "Автостарт так и не начался — звук, похоже, занят другим источником")
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

    /**
     * Передний план на время торга за звук.
     *
     * Своё уведомление — расписка системе в том, что служба, поднятая через
     * `startForegroundService`, уже на переднем плане. Настоящее, с обложкой и кнопками,
     * поставит Media3, когда музыка заиграет; наше к тому моменту снимаем.
     *
     * Если музыка так и не пошла — сворачиваемся: висеть в шторке молча служба не должна.
     */
    private fun holdForegroundUntilPlaying() {
        runCatching { startForeground(BOOT_NOTIFICATION_ID, bootNotification()) }
            .onFailure { Log.w(TAG, "Не удержал передний план после включения: ${it.message}") }

        bootHold?.cancel()
        bootHold = serviceScope.launch {
            repeat(BOOT_HOLD_ATTEMPTS) {
                delay(BOOT_HOLD_STEP_MS)
                val playing = withContext(Dispatchers.Main) { session?.player?.isPlaying == true }
                if (playing) {
                    // Media3 уже поставила своё уведомление и стала передним планом сама,
                    // поэтому наше снимаем как обычное — её не трогая.
                    getSystemService(NotificationManager::class.java)?.cancel(BOOT_NOTIFICATION_ID)
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                // Успело заиграть на последнем шаге — снимать передний план нельзя:
                // он теперь принадлежит Media3, и снятие убрало бы уведомление плеера.
                if (session?.player?.isPlaying == true) {
                    getSystemService(NotificationManager::class.java)?.cancel(BOOT_NOTIFICATION_ID)
                    return@withContext
                }
                Log.w(TAG, "После включения магнитолы музыка не пошла — сворачиваюсь")
                ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun bootNotification(): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(BOOT_CHANNEL_ID, "Возобновление", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Пока плеер возвращает музыку прошлой поездки"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, BOOT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Возвращаю музыку прошлой поездки")
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * Сохраняем на всём, что меняет «что играет» и «откуда».
     *
     * Прежде сохранение висело на паре событий, и мимо проходили целые повороты: перемотка,
     * набранная и не запущенная очередь, снятое головой намерение играть. Каждый такой
     * пропуск — это следующая поездка, начатая не с того места или не с того трека.
     */
    private inner class PersistingListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val meaningful = events.containsAny(
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
            if (meaningful) persist(player)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Пользователь смахнул приложение из недавних. Музыка на паузе — останавливаем службу,
     * иначе в шторке навсегда повиснет уведомление, из которого нечего играть.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            player?.let { persistNow(it) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Последнее слово: службу могли снять и на ходу, а несохранённые секунды — это
        // ровно то место, к которому плеер обещал вернуться.
        session?.player?.let { persistNow(it) }
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
         * Любая команда снаружи — из приложения, шторки, с руля — означает, что за плеер
         * взялись руками. Догонять после этого автостарт нельзя: пауза, поставленная
         * водителем, не должна отменяться сама собой через пару секунд.
         *
         * Заодно отмечаем, остановили ли музыку руками: по этому признаку пробуждение
         * отличает свою тишину от чужой.
         */
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            handsOn = true
            when (playerCommand) {
                // Команда одна и на «играть», и на «паузу», а что именно просят — видно
                // только по тому, что происходит сейчас: команда ещё не выполнена.
                Player.COMMAND_PLAY_PAUSE -> pausedByHand = session.player.playWhenReady
                Player.COMMAND_STOP -> pausedByHand = true
            }
            return SessionResult.RESULT_SUCCESS
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
            if (event.action == KeyEvent.ACTION_DOWN) {
                handsOn = true
                pausedByHand = true
                session.player.pause()
            }
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
                if (saved == null) {
                    future.setException(UnsupportedOperationException("Нечего возобновлять"))
                    return@launch
                }
                val fromMirror = runCatching { mirror.tracksByIds(saved.trackIds) }
                    .getOrDefault(emptyList())
                    .associateBy { it.id }
                val items = buildQueue(saved, fromMirror)
                if (items.isEmpty()) {
                    future.setException(UnsupportedOperationException("Нечего возобновлять"))
                    return@launch
                }
                // Тот же счёт по идентификатору, что и при обычном восстановлении: индекс
                // врёт ровно там, где из очереди выпал хоть один трек.
                val at = startIndexOf(saved, items)
                future.set(
                    MediaSession.MediaItemsWithStartPosition(
                        items,
                        at.coerceAtLeast(0),
                        if (at >= 0) saved.positionMs else 0L,
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

    companion object {
        private const val TAG = "PlaybackService"

        /** Служба поднята после включения магнитолы или обновления приложения. */
        const val ACTION_RESUME_AFTER_BOOT = "com.vitz.music.app.RESUME_AFTER_BOOT"

        /** Компромисс между износом хранилища и тем, сколько секунд теряется при обрыве питания. */
        private const val SAVE_INTERVAL_MS = 5_000L

        /** Сколько раз и с каким шагом повторять пуск, пока голова занимает звук. */
        private const val START_ATTEMPTS = 30
        private const val START_RETRY_MS = 2_000L

        /** Сон, после которого даже ручную паузу считаем прошлой поездкой, а не «отвернулся». */
        private const val LONG_SLEEP_MS = 5 * 60_000L

        /** Сколько держим передний план после включения магнитолы. Дольше догона автостарта. */
        private const val BOOT_HOLD_ATTEMPTS = 70
        private const val BOOT_HOLD_STEP_MS = 1_000L
        private const val BOOT_NOTIFICATION_ID = 4711
        private const val BOOT_CHANNEL_ID = "vitz-music-resume"

        private val ALLOWED_PACKAGES = setOf(
            "com.vitz.music.app",
            "com.vitz.music.app.debug",
            "com.vitz.dashboard",
            "com.vitz.dashboard.debug",
            // Системный медиа-контроллер: без него не работают шторка и кнопки на руле.
            "com.android.systemui",
        )
    }
}
