package com.vitz.music.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.vitz.music.app.data.ApiClient
import com.vitz.music.app.data.AppSettings
import com.vitz.music.app.data.AutoDownloader
import com.vitz.music.app.data.CatalogRepository
import com.vitz.music.app.data.Downloads
import com.vitz.music.app.data.PlaybackStateStore
import com.vitz.music.app.data.SyncService
import com.vitz.music.app.data.TokenStore
import com.vitz.music.app.data.local.MirrorDb
import com.vitz.music.app.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Контейнер зависимостей. DI-фреймворк здесь пока не окупается: объектов, живущих
 * всё время работы приложения, единицы.
 */
class VitzMusicApp : Application(), SingletonImageLoader.Factory {
    val tokenStore: TokenStore by lazy { TokenStore(this) }
    val api: ApiClient by lazy { ApiClient(tokenStore) }
    val mirror: MirrorDb by lazy { MirrorDb(this) }
    val downloads: Downloads by lazy { Downloads(this, api, mirror) }
    val catalog: CatalogRepository by lazy {
        CatalogRepository(api, mirror, downloads, onLiked = { autoDownloader.onTrackLiked() })
    }
    val sync: SyncService by lazy { SyncService(api, mirror, downloads) }
    val settings: AppSettings by lazy { AppSettings(this) }
    val playbackState: PlaybackStateStore by lazy { PlaybackStateStore(this) }

    /** Самообновление из публичного репозитория сборок — исходники остаются приватными. */
    val updates: UpdateManager by lazy { UpdateManager(this) }

    /** Живёт столько же, сколько процесс: автозагрузка не должна обрываться сменой экрана. */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val autoDownloader: AutoDownloader by lazy {
        AutoDownloader(this, mirror, downloads, settings, appScope)
    }

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
