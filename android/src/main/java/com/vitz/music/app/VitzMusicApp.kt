package com.vitz.music.app

import android.app.Application
import com.vitz.music.app.data.ApiClient
import com.vitz.music.app.data.TokenStore

/**
 * Контейнер зависимостей. DI-фреймворк здесь пока не окупается: объектов, живущих
 * всё время работы приложения, ровно два.
 */
class VitzMusicApp : Application() {
    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val api: ApiClient by lazy { ApiClient(tokenStore) }
}
