package com.vitz.music

import com.vitz.music.admin.AdminSession
import com.vitz.music.admin.adminRoutes
import com.vitz.music.auth.Tokens
import com.vitz.music.auth.UserPrincipal
import com.vitz.music.auth.Users
import com.vitz.music.auth.authRoutes
import com.vitz.music.catalog.catalogRoutes
import com.vitz.music.catalog.playlistRoutes
import com.vitz.music.catalog.syncRoutes
import com.vitz.music.db.Db
import com.vitz.music.ingest.Ffmpeg
import com.vitz.music.ingest.IngestService
import com.vitz.music.jobs.JobQueue
import com.vitz.music.jobs.JobWorker
import com.vitz.music.media.MediaGc
import com.vitz.music.media.MediaStore
import com.vitz.music.media.UrlSigner
import com.vitz.music.media.mediaRoutes
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID
import com.vitz.music.api.ErrorDto

class AppServices(val cfg: Config) {
    val tokens = Tokens(cfg)
    val signer = UrlSigner(cfg.signSecret, cfg.signTtlSeconds)
    val store = MediaStore(cfg.mediaRoot)
    val ffmpeg = Ffmpeg(cfg.ffmpeg, cfg.ffprobe)
    val queue = JobQueue()
    val ingest = IngestService(store, ffmpeg, cfg)
    val gc = MediaGc(store, cfg)
}

fun Application.module(cfg: Config) {
    val services = AppServices(cfg)
    val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "same-origin")
    }
    install(XForwardedHeaders)
    install(CallLogging) {
        level = Level.INFO
        // Раздача медиа шумит и не несёт информации — логируем только API и админку.
        filter { call -> !call.request.local.uri.startsWith("/media/") }
    }
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }
    install(PartialContent)
    install(CachingHeaders) {
        options { call, _ ->
            when {
                call.request.local.uri.startsWith("/media/") ->
                    CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Private))
                else -> null
            }
        }
    }
    install(Sessions) {
        cookie<AdminSession>("vm_admin") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = 12 * 3600
            cookie.secure = !cfg.isLocal
            cookie.extensions["SameSite"] = "Strict"
            serializer = AdminSession.Serializer
            transform(SessionTransportTransformerMessageAuthentication(cfg.jwtSecret.toByteArray()))
        }
    }
    install(Authentication) {
        jwt("api") {
            realm = "vitz-music"
            verifier(services.tokens.verifier)
            validate { credential ->
                val jwt = JWTPrincipal(credential.payload)
                val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return@validate null
                val role = jwt.getClaim("role", String::class) ?: return@validate null
                val sessionId = jwt.getClaim("sid", String::class)?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                } ?: return@validate null
                if (!Users.sessionIsActive(sessionId)) return@validate null
                UserPrincipal(userId, role, sessionId)
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized"))
            }
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", cause.message))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorDto(cause.code, cause.message))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Необработанная ошибка на ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal_error"))
        }
    }

    Users.bootstrapAdmin(cfg)

    routing {
        get("/healthz") {
            val ok = Db.healthy()
            call.respond(
                if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                mapOf("status" to if (ok) "ok" else "degraded"),
            )
        }
        authRoutes(services)
        catalogRoutes(services)
        playlistRoutes(services)
        syncRoutes(services)
        mediaRoutes(services)
        adminRoutes(services)
    }

    JobWorker(services, workerScope).start()
    services.gc.start(workerScope)
}

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String? = null,
) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "bad_request", message)
fun notFound(what: String = "not_found"): Nothing = throw ApiException(HttpStatusCode.NotFound, what)
fun forbidden(): Nothing = throw ApiException(HttpStatusCode.Forbidden, "forbidden")
fun conflict(code: String, message: String? = null): Nothing = throw ApiException(HttpStatusCode.Conflict, code, message)

val JSON_CONTENT: ContentType = ContentType.Application.Json
