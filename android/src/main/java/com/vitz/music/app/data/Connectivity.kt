package com.vitz.music.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Есть ли сеть и какая.
 *
 * Отдельный класс, потому что вопрос задают трое: автозагрузка (качать ли пачку), ручное
 * скачивание (что показать человеку вместо галочки) и синхронизация. Ответ «нет интернета»
 * обязан звучать именно так, а не превращаться в молчаливую неудачу где-то в глубине.
 */
class Connectivity(private val context: Context) {

    /**
     * Есть ли сеть, через которую вообще возможен интернет.
     *
     * Проверяется наличие сети, а не её работоспособность: `NET_CAPABILITY_VALIDATED`
     * на магнитоле зависит от того, дотянулась ли система до своей проверочной страницы,
     * и по нему рабочий домашний Wi-Fi иногда числится «без интернета». Ошибиться в эту
     * сторону хуже: тогда приложение отказывалось бы качать по живой сети.
     */
    fun online(): Boolean = capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    /** Wi-Fi или провод — то, по чему не жалко качать гигабайты. */
    fun unmetered(): Boolean = capabilities()?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } == true

    private fun capabilities(): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return manager.getNetworkCapabilities(manager.activeNetwork)
    }
}
