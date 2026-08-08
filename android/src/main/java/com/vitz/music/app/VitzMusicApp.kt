package com.vitz.music.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.vitz.music.app.data.ApiClient
import com.vitz.music.app.data.CatalogRepository
import com.vitz.music.app.data.TokenStore

/**
 * Контейнер зависимостей. DI-фреймворк здесь пока не окупается: объектов, живущих
 * всё время работы приложения, единицы.
 */
class VitzMusicApp : Application(), SingletonImageLoader.Factory {
    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val api: ApiClient by lazy { ApiClient(tokenStore) }
    val catalog: CatalogRepository by lazy { CatalogRepository(api) }

    /**
     * Загрузчик обложек объявлен явно, а не автоопределением: адреса обложек внешние,
     * и лучше видеть в коде, чем именно они тянутся.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
}
