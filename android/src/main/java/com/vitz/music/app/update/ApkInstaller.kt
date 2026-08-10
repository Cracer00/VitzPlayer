package com.vitz.music.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Установка APK через [PackageInstaller].
 *
 * Не через `ACTION_VIEW` с FileProvider: сессионный установщик сообщает результат
 * (успех, отказ пользователя, несовпадение подписи), и по нему видно, что пошло не так.
 *
 * Установка обновления поверх возможна только при совпадении подписи с уже установленным
 * приложением — отсюда постоянный ключ в настройках сборки.
 */
class ApkInstaller(private val context: Context) {

    fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Разрешение выдаётся не диалогом, а отдельным экраном системных настроек. */
    fun openPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Экран разрешения на установку не открылся", it) }
    }

    fun install(apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite(APK_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }

            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
        true
    }.onFailure { Log.w(TAG, "Установка не запустилась", it) }.getOrDefault(false)

    private companion object {
        const val TAG = "ApkInstaller"
        const val APK_NAME = "update.apk"
    }
}
