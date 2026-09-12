package com.vitz.music.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.vitz.music.app.VitzMusicApp

/**
 * Включение магнитолы.
 *
 * Самый частый случай «плеер не вернулся к треку» — не забытая позиция, а то, что
 * возвращаться некому: зажигание выключили, голова обесточилась, и при следующей поездке
 * приложение просто не запущено. Ни службы, ни сессии, ни уведомления в шторке — до тех
 * пор, пока водитель сам не найдёт иконку.
 *
 * Поэтому на загрузку системы поднимаем службу воспроизведения: очередь и позицию она
 * восстановит сама, тем же кодом, что и при обычном запуске.
 *
 * `ACTION_MY_PACKAGE_REPLACED` здесь по той же причине: плеер обновляется сам, а обновление
 * убивает процесс — без этого поездка обрывалась бы ровно на самообновлении.
 *
 * Запуск службы из фона Android разрешает не всякому, но приёмнику этих двух событий —
 * разрешает: они в списке исключений именно для таких случаев.
 */
class ResumeOnBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val app = context.applicationContext as? VitzMusicApp ?: return

        // Автостарт выключен — значит и поднимать нечего: служба всё равно только
        // восстановит очередь и замолчит, а в шторке останется висеть уведомление.
        if (!app.settings.resumeOnStart || !app.settings.autoplayOnStart) {
            Log.i(TAG, "Включение (${intent.action}): автостарт выключен настройкой")
            return
        }
        if (app.playbackState.load() == null) {
            Log.i(TAG, "Включение (${intent.action}): возвращаться не к чему")
            return
        }

        val start = Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_RESUME_AFTER_BOOT)
        runCatching { ContextCompat.startForegroundService(context, start) }
            .onSuccess { Log.i(TAG, "Включение (${intent.action}): поднимаю службу воспроизведения") }
            .recoverCatching { denied ->
                // С Android 15 служба воспроизведения не поднимается из приёмника загрузки
                // системы: тип `mediaPlayback` там запрещён, и система бросает отказ.
                // Обычный запуск в этом случае — единственное, что остаётся попробовать.
                Log.w(TAG, "Передний план после включения не дали: ${denied.message}")
                context.startService(start)
            }
            .onFailure { Log.w(TAG, "Не поднял службу после включения: ${it.message}") }
    }

    private companion object {
        const val TAG = "ResumeOnBoot"

        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
