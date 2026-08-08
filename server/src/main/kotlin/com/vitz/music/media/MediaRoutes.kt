package com.vitz.music.media

import com.vitz.music.AppServices
import com.vitz.music.badRequest
import com.vitz.music.db.dbRead
import com.vitz.music.db.selectOne
import com.vitz.music.forbidden
import com.vitz.music.notFound
import io.ktor.http.ContentType
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.nio.file.Files
import java.util.UUID

fun contentTypeForExt(ext: String): ContentType = when (ext.lowercase()) {
    "mp3" -> ContentType.parse("audio/mpeg")
    "opus", "ogg", "oga" -> ContentType.parse("audio/ogg")
    "flac" -> ContentType.parse("audio/flac")
    "m4a", "mp4", "aac" -> ContentType.parse("audio/mp4")
    "wav" -> ContentType.parse("audio/wav")
    "webp" -> ContentType.parse("image/webp")
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "png" -> ContentType.Image.PNG
    else -> ContentType.Application.OctetStream
}

fun Route.mediaRoutes(services: AppServices) = route("/media") {
    val store = services.store

    // Аудио: только по подписанной ссылке. Range/206 добавляет плагин PartialContent.
    get("/t/{trackId}/{rendition}") {
        val trackId = call.parameters["trackId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: badRequest("Некорректный идентификатор трека")
        val rendition = call.parameters["rendition"] ?: badRequest("Не указана рендиция")
        val userId = call.request.queryParameters["u"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: badRequest("Нет параметра u")
        val exp = call.request.queryParameters["exp"]?.toLongOrNull() ?: badRequest("Нет параметра exp")
        val sig = call.request.queryParameters["sig"] ?: badRequest("Нет подписи")

        if (!services.signer.verify(trackId, rendition, userId, exp, sig)) forbidden()

        val row = dbRead { c ->
            c.selectOne(
                """
                select r.sha256, r.ext
                from renditions r
                join tracks t on t.id = r.track_id
                where r.track_id = ? and r.kind = ? and t.deleted_at is null
                """.trimIndent(),
                trackId, rendition,
            ) { rs -> rs.getString("sha256") to rs.getString("ext") }
        } ?: notFound("rendition_not_found")

        val kind = if (rendition == "original") "audio" else "rendition"
        val file = store.path(kind, row.first, row.second)
        if (!Files.isRegularFile(file)) notFound("file_missing")
        call.respond(LocalFileContent(file.toFile(), contentTypeForExt(row.second)))
    }

    // Обложки: адрес неугадываем (sha256 содержимого), отдельная подпись не нужна.
    get("/cover/{sha}/{size}") {
        val sha = call.parameters["sha"]?.takeIf { it.length == 64 && it.all { ch -> ch.isDigit() || ch in 'a'..'f' } }
            ?: badRequest("Некорректный идентификатор обложки")
        val size = call.parameters["size"]?.substringBefore('.')?.toIntOrNull() ?: badRequest("Некорректный размер")
        if (size !in COVER_SIZES) badRequest("Недоступный размер обложки")

        val file = services.store.path("cover", sha, "$size.webp")
        if (!Files.isRegularFile(file)) notFound("cover_not_found")
        call.respond(LocalFileContent(file.toFile(), ContentType.parse("image/webp")))
    }
}

val COVER_SIZES = listOf(64, 256, 1024)

fun coverUrl(publicUrl: String, sha: String?, size: Int = 256): String? =
    sha?.let { "$publicUrl/media/cover/$it/$size.webp" }
