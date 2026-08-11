package com.vitz.music.app.data

import android.net.Uri
import android.util.Log
import com.vitz.music.api.MediaLink
import com.vitz.music.api.TrackDto
import com.vitz.music.app.data.local.MirrorDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Продление подписанных ссылок на медиа.
 *
 * Ссылка на файл подписана и живёт шесть часов, а зеркало помнит ту, что приехала с
 * последней синхронизацией. Синхронизация же присылает только изменившиеся треки — значит
 * для всего каталога, кроме свежедобавленного, ссылки в зеркале протухшие почти всегда.
 * По такой ссылке сервер отвечает `403` и телом в двадцать один байт, и без продления
 * это выглядит то как загрузка, которая «прошла», то как трек, который молча не играет.
 *
 * Отсюда правило: перед тем как идти по ссылке, смотрим на её срок и, если он вышел,
 * берём у сервера свежую карточку трека. Свежие ссылки кладём обратно в зеркало — соседний
 * запрос по тому же треку сети уже не потребует.
 */
class MediaLinks(private val api: ApiClient, private val mirror: MirrorDb) {

    /**
     * Тот же трек, но с живыми ссылками. Если ссылки ещё не протухли, сети не касаемся вовсе.
     *
     * @param force продлить, даже если по сроку ссылка ещё жива, — для повтора после отказа
     * сервера: часы устройства и сервера могут разойтись, и тогда «живой» она только выглядит.
     */
    suspend fun fresh(track: TrackDto, force: Boolean = false): TrackDto {
        if (!force && track.media.isNotEmpty() && track.media.all { it.isFresh() }) return track

        val fromServer: TrackDto = api.get("/api/v1/tracks/${track.id}")
        withContext(Dispatchers.IO) { mirror.updateMedia(track.id, fromServer.media) }
        // Берём с сервера только ссылки: остальное в зеркале и так верно, а отметку
        // «нравится» карточка могла принести ещё не догнавшую локальное нажатие.
        return track.copy(media = fromServer.media)
    }

    /**
     * Свежий адрес для проигрывателя.
     *
     * ExoPlayer открывает поток на своём потоке загрузки и ждать умеет только блокируясь,
     * поэтому здесь [runBlocking], а не suspend. Возвращаем `null`, если менять нечего или
     * не вышло: тогда пойдём по исходному адресу и получим обычную ошибку воспроизведения
     * вместо загадочного молчания.
     */
    fun freshUrlBlocking(url: String): String? {
        val parsed = parseMediaUrl(url) ?: return null
        if (parsed.expiresAtEpochSeconds - FRESH_MARGIN_SECONDS > nowSeconds()) return null

        return runBlocking {
            runCatching {
                val fromServer: TrackDto = api.get<TrackDto>("/api/v1/tracks/${parsed.trackId}")
                withContext(Dispatchers.IO) { mirror.updateMedia(parsed.trackId, fromServer.media) }
                fromServer.media.firstOrNull { it.rendition == parsed.rendition }?.url
            }.getOrElse { error ->
                Log.w(TAG, "Не продлил ссылку ${parsed.trackId}: ${error.message}")
                null
            }
        }
    }

    private class MediaUrl(val trackId: String, val rendition: String, val expiresAtEpochSeconds: Long)

    /** `…/media/t/{trackId}/{rendition}?u=…&exp=…&sig=…`. Всё остальное нас не касается. */
    private fun parseMediaUrl(url: String): MediaUrl? = runCatching {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val segments = uri.pathSegments
        val at = segments.indexOf("media")
        if (at < 0 || segments.getOrNull(at + 1) != "t") return null
        val trackId = segments.getOrNull(at + 2) ?: return null
        val rendition = segments.getOrNull(at + 3) ?: return null
        val exp = uri.getQueryParameter("exp")?.toLongOrNull() ?: return null
        MediaUrl(trackId, rendition, exp)
    }.getOrNull()

    private companion object {
        const val TAG = "MediaLinks"
    }
}

/** Запас перед сроком: файл качается минутами, и подпись не должна истечь на середине. */
private const val FRESH_MARGIN_SECONDS = 15 * 60L

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

/**
 * Жива ли подпись. Ссылка без срока — это не подписанный адрес, и продлевать в нём нечего:
 * такой считаем годным, иначе продление уходило бы в холостую на каждом запросе.
 */
fun MediaLink.isFresh(): Boolean {
    val exp = runCatching { Uri.parse(url).getQueryParameter("exp")?.toLongOrNull() }.getOrNull() ?: return true
    return exp - FRESH_MARGIN_SECONDS > nowSeconds()
}
