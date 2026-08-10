package com.vitz.music.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * Ответ системного установщика.
 *
 * Ключевой случай — [PackageInstaller.STATUS_PENDING_USER_ACTION]: система не ставит APK
 * молча, а возвращает интент с диалогом подтверждения, который нужно показать самому.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java
                )
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { confirmation?.let(context::startActivity) }
                    .onFailure { Log.w(TAG, "Диалог подтверждения не открылся", it) }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit // приложение перезапустится само

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Установка не удалась, статус $status: $message")
                Toast.makeText(context, explain(status, message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun explain(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Подпись обновления не совпадает с установленной версией — обновить поверх нельзя"

        PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места для установки"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Сборка несовместима с устройством"
        else -> message ?: "Не удалось установить обновление"
    }

    private companion object {
        const val TAG = "InstallResultReceiver"
    }
}
