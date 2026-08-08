package com.vitz.music.app.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.util.Log

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

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
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
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * К сессии пускаем только свои приложения. Открытая сессия означает, что любое
     * приложение на планшете сможет и управлять воспроизведением, и читать, что играет.
     */
    private inner class RestrictedCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val allowed = controller.packageName in ALLOWED_PACKAGES
            if (!allowed) {
                Log.w(TAG, "Отказано в подключении к сессии: ${controller.packageName}")
                return MediaSession.ConnectionResult.reject()
            }
            return super.onConnect(session, controller)
        }
    }

    private companion object {
        const val TAG = "PlaybackService"

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
